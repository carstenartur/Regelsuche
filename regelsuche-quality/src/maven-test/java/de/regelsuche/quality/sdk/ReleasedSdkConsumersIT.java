package de.regelsuche.quality.sdk;

import static de.regelsuche.quality.sdk.ReleasedSdkArchive.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Current, standalone Maven consumers against the actual released SDK and three isolated empty caches. */
class ReleasedSdkConsumersIT {
    static final URI RELEASE = URI.create("https://github.com/carstenartur/Regelsuche/releases/download/v0.5.2/regelsuche-sdk-0.5.2.zip");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> REMOVED_ENV = List.of("MAVEN_ARGS", "MAVEN_OPTS", "MAVEN_PROJECTBASEDIR",
        "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS");
    @TempDir Path scratch;

    @Test void qualifyCurrentCentralConsumerWithUnmodifiedDefaults() throws Exception {
        Path root = Path.of(System.getProperty("regelsuche.repositoryRoot")).toRealPath();
        Path base = root.resolve("build/reports/released-sdk-maven-consumers");
        noSymlinks(base);
        Files.createDirectories(base);
        Path evidence = Files.createDirectory(base.resolve(UUID.randomUUID().toString().replace("-", "") + "-central"));
        var summary = new LinkedHashMap<String, Object>();
        summary.put("schema", "regelsuche.central-sdk-consumer/v1");
        summary.put("status", "RUNNING");
        var commands = new ArrayList<Map<String, Object>>();
        summary.put("commands", commands);
        Path report = evidence.resolve("qualification.json");
        save(report, summary);
        try {
            require(!scratch.toRealPath().startsWith(root), "qualification scratch must be outside the repository");
            summary.put("checkoutCommit", revision(root));
            boolean windows = File.separatorChar == '\\';
            String java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
            String maven = Path.of(System.getProperty("regelsuche.mavenHome"), "bin", windows ? "mvn.cmd" : "mvn").toString();
            summary.put("toolchain", ReleasedSdkChecks.toolchain(
                execute(List.of(maven, "--version"), root, evidence.resolve("maven-version.log"), commands),
                execute(List.of(java, "-version"), root, evidence.resolve("java-version.log"), commands)));
            Path work = scratch.resolve("current-central-consumer");
            summary.put("sourceSha256", ReleasedSdkChecks.copyInputs(
                root.resolve("examples/external-consumers/hello-rule-java25"), work));
            ReleasedSdkChecks.requireStandalonePom(work.resolve("pom.xml"));
            Path cache = Files.createDirectory(scratch.resolve("empty-central-cache"));
            summary.put("cacheInitiallyEmpty", true);
            Path settings = Files.writeString(scratch.resolve("empty-settings.xml"),
                "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.2.0\"/>\n");
            var arguments = new ArrayList<>(List.of(maven, "--batch-mode", "--no-transfer-progress",
                "--settings", settings.toString(), "--global-settings", settings.toString(),
                "-Dmaven.repo.local=" + cache, "-DskipTests=false", "-Dmaven.test.skip=false"));
            configureMavenTransport(arguments);
            var build = new ArrayList<>(arguments);
            build.addAll(List.of("clean", "package"));
            execute(build, work, evidence.resolve("maven-package.log"), commands);
            JsonNode contract = ReleasedSdkChecks.contracts().required("hello-rule-java25");
            summary.put("surefire", ReleasedSdkChecks.surefire(work.resolve("target/surefire-reports"),
                ReleasedSdkChecks.strings(contract.required("tests"))));
            summary.put("surefireReportSha256", ReleasedSdkChecks.copyInputs(
                work.resolve("target/surefire-reports"), evidence.resolve("surefire-reports")));
            Path graph = evidence.resolve("runtime-dependencies.tgf");
            var tree = new ArrayList<>(arguments);
            tree.addAll(List.of("org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree", "-Dscope=runtime",
                "-DoutputType=tgf", "-DoutputFile=" + graph));
            execute(tree, work, evidence.resolve("maven-runtime-tree.log"), commands);
            JsonNode published = ReleasedSdkChecks.centralRelease();
            summary.put("publishedSdk", published);
            var hashes = new java.util.TreeMap<String, String>();
            published.required("jarSha256").properties().forEach(entry -> hashes.put(entry.getKey(), entry.getValue().textValue()));
            summary.put("runtimeClosure", ReleasedSdkChecks.closure(graph, work.resolve("target/dependency"),
                published.required("groupId").textValue(), published.required("version").textValue(), hashes));
            String actual = execute(List.of(java, "-cp", "target/classes" + File.pathSeparator + "target/dependency/*",
                "example.HelloRule"), work, evidence.resolve("cli.log"), commands);
            summary.put("stdout", actual);
            summary.put("cliIdentity", ReleasedSdkChecks.cli(contract, actual, null));
            summary.put("status", "PASSED");
        } catch (Exception failure) {
            summary.put("status", "FAILED");
            summary.put("error", failure.toString());
            throw failure;
        } finally {
            save(report, summary);
            System.out.println("centralSdkEvidence=" + evidence);
        }
    }

    @Test void qualifyActualReleasedConsumersAndRulePackage() throws Exception {
        Path root = Path.of(System.getProperty("regelsuche.repositoryRoot")).toRealPath();
        Path base = root.resolve("build/reports/released-sdk-maven-consumers");
        noSymlinks(base);
        Files.createDirectories(base);
        Path evidence = Files.createDirectory(base.resolve(UUID.randomUUID().toString().replace("-", "")));
        System.out.println("releasedSdkEvidence=" + evidence);
        Map<String, Object> summary = new LinkedHashMap<>(Map.of("schema", "regelsuche.released-sdk-maven-consumers/v1",
            "status", "RUNNING", "releaseUrl", RELEASE.toString(), "releaseVersion", VERSION, "expectedSha256", SHA256));
        Map<String, Object> consumers = new LinkedHashMap<>();
        List<Map<String, Object>> commands = new ArrayList<>();
        summary.put("commands", commands);
        summary.put("consumers", consumers);
        Path output = evidence.resolve("qualification.json");
        save(output, summary);
        try {
            require(!scratch.toRealPath().startsWith(root), "qualification scratch must be outside the repository");
            summary.put("checkoutCommit", revision(root));
            boolean windows = File.separatorChar == '\\';
            String java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
            String maven = Path.of(System.getProperty("regelsuche.mavenHome"), "bin", windows ? "mvn.cmd" : "mvn").toString();
            String mvnVersion = execute(List.of(maven, "--version"), root, evidence.resolve("maven-version.log"), commands);
            String jdk = execute(List.of(java, "-version"), root, evidence.resolve("java-version.log"), commands);
            summary.put("toolchain", ReleasedSdkChecks.toolchain(mvnVersion, jdk));
            Path archive = scratch.resolve("sdk.zip");
            download(archive);
            Path extracted = scratch.resolve("extracted");
            summary.put("archive", extract(archive, extracted, SHA256, CHECKSUM_COUNT, PREFIX));
            Path repository = extracted.resolve(PREFIX).resolve("repository");
            Path settings = scratch.resolve("empty-settings.xml");
            Files.writeString(settings, "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.2.0\"/>\n");
            List<String> failures = new ArrayList<>();
            for (var contract : ReleasedSdkChecks.contracts().properties()) {
                String name = contract.getKey();
                var result = new LinkedHashMap<String, Object>();
                consumers.put(name, result);
                Path work = scratch.resolve(name);
                Path out = Files.createDirectory(evidence.resolve(name));
                try {
                    qualifyConsumer(root, work, out, repository, settings, maven, java, name, contract.getValue(), result);
                    result.put("status", "PASSED");
                } catch (Exception error) {
                    result.put("status", "FAILED");
                    result.put("error", error.toString());
                    failures.add(name);
                } finally {
                    Path reports = work.resolve("target/surefire-reports");
                    if (Files.isDirectory(reports)) result.put("surefireReportSha256", ReleasedSdkChecks.copyInputs(reports, out.resolve("surefire-reports")));
                    save(output, summary);
                }
            }
            Map<String, Object> rule = new LinkedHashMap<>();
            summary.put("rulePackage", rule);
            try {
                validateRulePackage(root, repository, java, evidence, rule);
                rule.put("status", "PASSED");
            } catch (Exception error) {
                rule.put("status", "FAILED");
                rule.put("error", error.toString());
                failures.add("factorization-rule-package");
            }
            require(failures.isEmpty(), "failed released SDK qualification: " + failures + " in " + evidence);
            summary.put("status", "PASSED");
        } catch (Exception error) {
            summary.put("status", "FAILED");
            summary.put("error", error.toString());
            throw error;
        } finally { save(output, summary); }
    }

    private static void qualifyConsumer(Path root, Path work, Path logs, Path repository, Path settings, String maven, String java,
            String name, JsonNode contract, Map<String, Object> result) throws Exception {
        List<Map<String, Object>> commands = new ArrayList<>();
        result.put("commands", commands);
        result.put("sourceSha256", ReleasedSdkChecks.copyInputs(root.resolve("examples/external-consumers").resolve(name), work));
        ReleasedSdkChecks.requireStandalonePom(work.resolve("pom.xml"));
        Path cache = Files.createDirectory(work.resolveSibling(name + "-empty-cache"));
        try (var entries = Files.list(cache)) { require(entries.findAny().isEmpty(), "fresh Maven repository is not empty"); }
        result.put("cacheInitiallyEmpty", true);
        var arguments = new ArrayList<>(List.of(maven, "--batch-mode", "--no-transfer-progress", "--settings", settings.toString(),
            "--global-settings", settings.toString(), "-Dmaven.repo.local=" + cache, "-Dregelsuche.repository=" + repository.toUri(),
            "-Dregelsuche.version=" + VERSION, "-Dregelsuche.groupId=de.regelsuche", "-DskipTests=false", "-Dmaven.test.skip=false"));
        configureMavenTransport(arguments);
        var build = new ArrayList<>(arguments);
        build.addAll(List.of("clean", "package"));
        execute(build, work, logs.resolve("maven-package.log"), commands);
        result.put("surefire", ReleasedSdkChecks.surefire(work.resolve("target/surefire-reports"), ReleasedSdkChecks.strings(contract.required("tests"))));
        Path graph = logs.resolve("runtime-dependencies.tgf");
        var tree = new ArrayList<>(arguments);
        tree.addAll(List.of("org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree", "-Dscope=runtime",
            "-DoutputType=tgf", "-DoutputFile=" + graph));
        execute(tree, work, logs.resolve("maven-runtime-tree.log"), commands);
        result.put("runtimeClosure", ReleasedSdkChecks.closure(graph, work.resolve("target/dependency"), repository, ReleasedSdkChecks.strings(contract.required("modules"))));
        var cli = List.of(java, "-cp", "target/classes" + File.pathSeparator + "target/dependency/*", contract.required("main").textValue());
        String actual = execute(cli, work, logs.resolve("cli.log"), commands);
        result.put("stdout", actual);
        String repeat = null;
        if (contract.has("repeatHash")) {
            repeat = execute(cli, work, logs.resolve("cli-repeat.log"), commands);
            result.put("repeatStdout", repeat);
        }
        result.put("cliIdentity", ReleasedSdkChecks.cli(contract, actual, repeat));
    }

    private void validateRulePackage(Path root, Path repository, String java, Path evidence, Map<String, Object> result) throws Exception {
        String name = "factorization-rule-package";
        Path work = Files.createDirectory(scratch.resolve(name));
        Path logs = Files.createDirectory(evidence.resolve(name));
        Map<String, String> sources = new HashMap<>();
        for (String input : List.of("examples/factorization.regelsuche", "examples/factorization-export-profile.rules",
                "scripts/VerifyReleasedRulePackage.java")) {
            Path source = root.resolve(input);
            noSymlinks(source);
            Path copied = Files.copy(source, work.resolve(source.getFileName()));
            sources.put(copied.getFileName().toString(), digest(copied));
        }
        Path incomplete = Files.writeString(work.resolve("incomplete.regelsuche"), "rule incomplete:\n  pattern: X\n");
        sources.put(incomplete.getFileName().toString(), digest(incomplete));
        result.put("sourceSha256", sources);
        List<String> jars = new ArrayList<>();
        Map<String, String> hashes = new HashMap<>();
        for (String module : List.of("regelsuche-plugin-api", "regelsuche-core")) {
            Path jar = repository.resolve("de/regelsuche").resolve(module).resolve(VERSION).resolve(module + "-" + VERSION + ".jar");
            hashes.put(jar.getFileName().toString(), digest(jar));
            jars.add(jar.toString());
        }
        result.put("jarSha256", hashes);
        List<Map<String, Object>> commands = new ArrayList<>();
        result.put("commands", commands);
        result.put("stdout", execute(List.of(java, "-cp", String.join(File.pathSeparator, jars), "VerifyReleasedRulePackage.java",
            "factorization.regelsuche", "factorization-export-profile.rules", "incomplete.regelsuche"), work, logs.resolve("cli.log"), commands));
    }

    static String execute(List<String> command, Path cwd, Path log, List<Map<String, Object>> records) throws Exception {
        Files.createFile(log);
        var builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        REMOVED_ENV.forEach(builder.environment()::remove);
        builder.environment().put("LC_ALL", "C.UTF-8");
        int exit;
        try {
            var process = builder.start();
            if (process.waitFor(20, TimeUnit.MINUTES)) exit = process.exitValue();
            else {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly().waitFor();
                Files.writeString(log, "\nQualification command timed out.\n", java.nio.file.StandardOpenOption.APPEND);
                exit = 124;
            }
        } catch (IOException failure) {
            Files.writeString(log, failure.toString() + "\n", java.nio.file.StandardOpenOption.APPEND);
            exit = 127;
        }
        records.add(Map.of("command", List.copyOf(command), "cwd", cwd.toString(), "log", log.getFileName().toString(), "exitCode", exit));
        require(exit == 0, "command exit " + exit + " in " + log);
        return Files.readString(log);
    }

    private static void save(Path output, Map<String, Object> summary) throws IOException {
        Path staged = Files.createTempFile(output.getParent(), ".qualification-", ".json");
        try {
            Files.writeString(staged, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n");
            Files.move(staged, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(staged); }
    }

    private static void configureMavenTransport(List<String> arguments) {
        // A network proxy changes transport only. Consumer caches remain empty
        // and Maven still reads two empty settings files with no mirrors or servers.
        URI route = anonymousHttpsProxy();
        if (route == null) return;
        arguments.add("-Daether.connector.http.useSystemProperties=true");
        arguments.add("-Dhttps.proxyHost=" + route.getHost());
        arguments.add("-Dhttps.proxyPort=" + (route.getPort() > 0 ? route.getPort() : 80));
        String trust = System.getProperty("javax.net.ssl.trustStore");
        if (trust != null) arguments.add("-Djavax.net.ssl.trustStore=" + trust);
    }

    private static URI anonymousHttpsProxy() {
        String raw = System.getenv("HTTPS_PROXY");
        if (raw == null || raw.isBlank()) return null;
        URI uri = URI.create(raw);
        return "http".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null ? uri : null;
    }

    private static void download(Path destination) throws Exception {
        String supplied = System.getenv("REGELSUCHE_RELEASED_SDK_ZIP");
        if (supplied != null) {
            Path source = Path.of(supplied);
            noSymlinks(source);
            require(Files.size(source) <= MAX_DOWNLOAD, "oversized supplied release");
            Files.copy(source, destination);
            return;
        }
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NORMAL);
        URI route = anonymousHttpsProxy();
        if (route != null && System.getProperty("https.proxyHost") == null) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(route.getHost(), route.getPort() > 0 ? route.getPort() : 80)));
        }
        try (var client = builder.build()) {
            var request = HttpRequest.newBuilder(RELEASE).timeout(Duration.ofMinutes(2)).header("User-Agent", "Regelsuche-release-consumer-verifier").build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body(); var out = Files.newOutputStream(destination)) {
                require(response.statusCode() == 200 && "https".equals(response.uri().getScheme()), "unavailable release ZIP: HTTP " + response.statusCode());
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                for (int n; (n = in.read(buffer)) >= 0;) {
                    total += n;
                    require(total <= MAX_DOWNLOAD, "release download exceeds limit");
                    out.write(buffer, 0, n);
                }
            }
        }
    }

    static String revision(Path root) throws IOException {
        String supplied = System.getenv("REGELSUCHE_PREPARATION_REVISION");
        Path git = root.resolve(".git");
        if (!Files.exists(git)) {
            require(supplied != null && supplied.matches("[0-9a-f]{40}"), "Git-free source must supply its exact revision");
            return supplied;
        }
        if (Files.isRegularFile(git)) {
            String pointer = Files.readString(git).strip();
            require(pointer.startsWith("gitdir: "), "invalid worktree pointer");
            git = root.resolve(pointer.substring(8)).normalize();
        }
        String id = Files.readString(git.resolve("HEAD")).strip();
        if (id.startsWith("ref: ")) {
            String ref = id.substring(5);
            require(ref.startsWith("refs/") && !Path.of(ref).normalize().startsWith(".."), "invalid HEAD ref");
            Path common = Files.isRegularFile(git.resolve("commondir")) ? git.resolve(Files.readString(git.resolve("commondir")).strip()).normalize() : git;
            if (Files.isRegularFile(common.resolve(ref))) id = Files.readString(common.resolve(ref)).strip();
            else try (var lines = Files.lines(common.resolve("packed-refs"))) {
                id = lines.filter(line -> line.endsWith(" " + ref)).findFirst().orElseThrow(() -> new IOException("missing HEAD ref")).split(" ")[0];
            }
        }
        require(id.matches("[0-9a-f]{40}") && (supplied == null || supplied.equals(id)), "checkout revision does not match supplied source identity");
        return id;
    }
}
