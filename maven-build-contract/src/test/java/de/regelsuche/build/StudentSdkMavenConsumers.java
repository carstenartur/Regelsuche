package de.regelsuche.build;

import static de.regelsuche.build.StudentSdkArchive.require;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Executes the packaged Maven projects without version overrides, checkout classes or a warm cache. */
final class StudentSdkMavenConsumers {
    private static final String GROUP = "io.github.carstenartur";
    private static final List<String> REMOVED_ENV = List.of("MAVEN_ARGS", "MAVEN_OPTS", "MAVEN_PROJECTBASEDIR",
        "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS");
    static final List<Consumer> CONSUMERS = List.of(
        new Consumer("hello-rule-java25", "example.HelloRule", Set.of("regelsuche-core", "regelsuche-egraph",
            "regelsuche-search", "regelsuche-plugin-api"), 2),
        new Consumer("checked-math-java25", "example.CheckedMathExamples", Set.of("regelsuche-core",
            "regelsuche-egraph", "regelsuche-search", "regelsuche-math-algorithms", "regelsuche-validation",
            "regelsuche-optimization-sdk"), 10));

    private StudentSdkMavenConsumers() {}

    static void qualify(Path archive, String version, Path evidence, Path scratch) throws Exception {
        require(Runtime.version().feature() == 25, "SDK consumers require Java 25");
        Files.createDirectories(evidence);
        var report = new LinkedHashMap<String, Object>();
        report.put("schema", "regelsuche.packaged-maven-consumers/v1");
        report.put("version", version);
        report.put("archiveSha256", StudentSdkArchive.sha256(Files.readAllBytes(archive)));
        report.put("status", "RUNNING");
        var consumers = new LinkedHashMap<String, Object>();
        report.put("consumers", consumers);
        try {
            Path extracted = scratch.resolve("sdk");
            JsonNode manifest = extract(archive, version, extracted);
            Path settings = Files.writeString(scratch.resolve("empty-settings.xml"),
                "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.2.0\"/>\n");
            for (var spec : CONSUMERS) {
                var result = new LinkedHashMap<String, Object>();
                consumers.put(spec.name(), result);
                Path logs = Files.createDirectory(evidence.resolve(spec.name()));
                Path project = extracted.resolve("examples/external-consumers").resolve(spec.name());
                require(Files.isRegularFile(project.resolve("pom.xml")), "missing packaged Maven project: " + spec.name());
                Path cache = Files.createDirectory(scratch.resolve(spec.name() + "-empty-cache"));
                result.put("cacheInitiallyEmpty", true);
                var commands = new ArrayList<Map<String, Object>>();
                result.put("commands", commands);
                var arguments = new ArrayList<>(List.of(System.getProperty("regelsuche.mavenExecutable", "mvn"),
                    "--batch-mode", "--no-transfer-progress", "--settings", settings.toString(), "--global-settings",
                    settings.toString(), "-Dmaven.repo.local=" + cache, "-DskipTests=false", "-Dmaven.test.skip=false"));
                transport(arguments);
                var build = new ArrayList<>(arguments);
                build.addAll(List.of("clean", "verify"));
                run(build, project, logs.resolve("maven-verify.log"), commands);
                result.put("testCases", testCases(project.resolve("target/surefire-reports"), spec.tests()));
                Path retained = Files.createDirectory(logs.resolve("surefire-reports"));
                try (var files = Files.newDirectoryStream(project.resolve("target/surefire-reports"))) {
                    for (Path file : files) if (Files.isRegularFile(file)) Files.copy(file, retained.resolve(file.getFileName()));
                }
                Path graph = logs.resolve("runtime-dependencies.tgf");
                var tree = new ArrayList<>(arguments);
                tree.addAll(List.of("org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree", "-Dscope=runtime",
                    "-DoutputType=tgf", "-DoutputFile=" + graph));
                run(tree, project, logs.resolve("maven-runtime-tree.log"), commands);
                result.put("runtimeJarSha256", verifyRuntime(graph, project.resolve("target/dependency"), manifest,
                    version, spec.modules()));
                String java = Path.of(System.getProperty("java.home"), "bin", File.separatorChar == '\\' ? "java.exe" : "java").toString();
                result.put("stdout", run(List.of(java, "-cp", "target/classes" + File.pathSeparator + "target/dependency/*",
                    spec.main()), project, logs.resolve("cli.log"), commands));
                result.put("status", "PASSED");
            }
            report.put("status", "PASSED");
        } catch (Exception failure) {
            report.put("status", "FAILED");
            report.put("error", failure.toString());
            throw failure;
        } finally {
            Files.write(evidence.resolve("maven-consumers.json"), StudentSdkArchive.canonicalJson(report));
        }
    }

    static JsonNode extract(Path archive, String version, Path destination) throws Exception {
        require(!Files.exists(destination), "SDK extraction requires a fresh directory");
        String prefix = "regelsuche-sdk-" + version + "/";
        var members = OptimizationSdkArchive.readZip(archive);
        byte[] descriptor = members.get(prefix + "sdk-compatibility.json");
        require(descriptor != null, "missing archive compatibility descriptor");
        JsonNode manifest = StudentSdkArchive.JSON.readTree(descriptor);
        require(version.equals(manifest.required("productVersion").textValue()), "archive version differs");
        for (var entry : manifest.required("artifacts").properties()) {
            byte[] bytes = members.get(prefix + entry.getKey());
            require(bytes != null && bytes.length == entry.getValue().required("bytes").asLong()
                && StudentSdkArchive.sha256(bytes).equals(entry.getValue().required("sha256").textValue()),
                "missing or altered packaged artifact: " + entry.getKey());
        }
        for (var entry : members.entrySet()) {
            require(entry.getKey().startsWith(prefix), "unexpected SDK archive root");
            String relative = entry.getKey().substring(prefix.length());
            StudentSdkArchive.validateMember(relative, "invalid SDK entry");
            Path output = destination.resolve(relative);
            Files.createDirectories(output.getParent());
            Files.write(output, entry.getValue(), java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        return manifest;
    }

    private static List<String> testCases(Path reports, int expected) throws Exception {
        var cases = new HashSet<String>();
        try (var files = Files.newDirectoryStream(reports, "TEST-*.xml")) {
            for (Path file : files) {
                var suite = MavenPomTestSupport.parse(file).getDocumentElement();
                for (String failure : List.of("failure", "error", "skipped"))
                    require(suite.getElementsByTagName(failure).getLength() == 0, "unsuccessful packaged consumer test");
                var tests = suite.getElementsByTagName("testcase");
                for (int index = 0; index < tests.getLength(); index++) {
                    var test = (org.w3c.dom.Element) tests.item(index);
                    require(cases.add(test.getAttribute("classname") + "#" + test.getAttribute("name")), "duplicate consumer test");
                }
            }
        }
        require(cases.size() == expected, "expected " + expected + " executed consumer tests, found " + cases.size());
        return cases.stream().sorted().toList();
    }

    static Map<String, String> verifyRuntime(Path graph, Path dependencies, JsonNode manifest, String version,
            Set<String> expectedModules) throws Exception {
        var hashes = new TreeMap<String, String>();
        try (var files = Files.newDirectoryStream(dependencies, "*.jar")) {
            for (Path file : files) hashes.put(file.getFileName().toString(), StudentSdkArchive.sha256(Files.readAllBytes(file)));
        }
        var declaredJars = new HashSet<String>();
        var modules = new HashSet<String>();
        var lines = Files.readAllLines(graph);
        require(lines.contains("#"), "invalid Maven dependency graph");
        for (String line : lines.subList(1, lines.indexOf("#"))) {
            String[] coordinate = line.substring(line.indexOf(' ') + 1).split(":");
            require(coordinate.length == 5 && "jar".equals(coordinate[2]), "unsupported runtime coordinate");
            String jar = coordinate[1] + "-" + coordinate[3] + ".jar";
            require(declaredJars.add(jar) && hashes.containsKey(jar), "missing or duplicate resolved runtime JAR: " + jar);
            if (coordinate[1].startsWith("regelsuche-")) {
                require(GROUP.equals(coordinate[0]) && version.equals(coordinate[3]) && modules.add(coordinate[1]),
                    "consumer resolved a different SDK version or namespace");
                String path = "repository/io/github/carstenartur/" + coordinate[1] + "/" + version + "/" + jar;
                require(hashes.get(jar).equals(manifest.required("artifacts").required(path).required("sha256").textValue()),
                    "consumer did not execute the packaged SDK bytes: " + jar);
            }
        }
        require(modules.equals(expectedModules), "unexpected packaged SDK modules: " + modules + "; expected " + expectedModules);
        require(declaredJars.equals(hashes.keySet()), "copied runtime JARs differ from the resolved Maven graph");
        return hashes;
    }

    private static void transport(List<String> arguments) {
        String raw = System.getenv("HTTPS_PROXY");
        if (raw != null && !raw.isBlank()) {
            URI proxy = URI.create(raw);
            if ("http".equals(proxy.getScheme()) && proxy.getHost() != null && proxy.getUserInfo() == null) {
                arguments.add("-Daether.connector.http.useSystemProperties=true");
                arguments.add("-Dhttps.proxyHost=" + proxy.getHost());
                arguments.add("-Dhttps.proxyPort=" + (proxy.getPort() > 0 ? proxy.getPort() : 80));
            }
        }
        String trust = System.getProperty("javax.net.ssl.trustStore");
        if (trust != null) arguments.add("-Djavax.net.ssl.trustStore=" + trust);
    }

    private static String run(List<String> command, Path directory, Path log,
            List<Map<String, Object>> commands) throws Exception {
        var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        REMOVED_ENV.forEach(builder.environment()::remove);
        var process = builder.start();
        int exit;
        if (process.waitFor(Duration.ofMinutes(10).toSeconds(), TimeUnit.SECONDS)) exit = process.exitValue();
        else {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly().waitFor();
            exit = 124;
        }
        commands.add(Map.of("command", command, "cwd", directory.toString(), "log", log.toString(), "exitCode", exit));
        require(exit == 0, "packaged consumer command failed: " + exit + " in " + log);
        return Files.readString(log);
    }

    record Consumer(String name, String main, Set<String> modules, int tests) {}
}
