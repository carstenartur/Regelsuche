package de.regelsuche.build;

import static de.regelsuche.build.StudentSdkArchive.canonicalJson;
import static de.regelsuche.build.StudentSdkArchive.regularFiles;
import static de.regelsuche.build.StudentSdkArchive.require;
import static de.regelsuche.build.StudentSdkArchive.sha256;
import static de.regelsuche.build.StudentSdkArchive.utf8;
import static de.regelsuche.build.StudentSdkArchive.validateMember;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

final class OptimizationSdkArchive {
    private static final List<String> MODULES = List.of("regelsuche-core", "regelsuche-egraph", "regelsuche-search",
        "regelsuche-validation", "regelsuche-math-algorithms", "regelsuche-optimization-sdk");
    private static final List<String> FORBIDDEN =
        List.of("org/eclipse/", "de/regelsuche/example/", "org/springframework/", "org/hibernate/");
    private static final Set<String> SIGNATURE_SUFFIXES = Set.of(".SF", ".RSA", ".DSA", ".EC");
    private static final String SDK = "regelsuche-optimization-sdk";
    private static final Path CONSUMER = Path.of("examples/external-consumers/java-optimization-java25");
    private static final Set<String> CONSUMER_BUILD_DIRS = Set.of("target", "build", ".gradle");

    private OptimizationSdkArchive() {}

    record Result(String version, String revision, Path jar, String sha256, Path archive) {}

    static Result create(Path root, Path repository, Path external, Path output, Path scratch) throws Exception {
        output = output.toAbsolutePath().normalize();
        require(Files.notExists(output), "standalone optimizer output already exists: " + output);
        String revision = run(root, scratch, "git", "rev-parse", "HEAD").strip();
        require(revision.matches("[0-9a-f]{40}"), "standalone optimizer requires an exact Git revision");
        require(run(root, scratch, "git", "status", "--porcelain", "--untracked-files=normal").isBlank(),
            "qualified distribution requires a clean committed source tree");
        Files.createDirectories(output.getParent());
        Path staging = Files.createTempDirectory(output.getParent(), ".optimizer-archive-");
        try {
            Result result = assemble(root, repository, external, staging, scratch, revision);
            Files.move(staging, output);
            return new Result(result.version(), result.revision(), output.resolve(staging.relativize(result.jar())),
                result.sha256(), output.resolve(staging.relativize(result.archive())));
        } catch (Exception failure) {
            try (var files = Files.walk(staging)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static Result assemble(Path root, Path repository, Path external, Path output, Path scratch,
            String revision) throws Exception {
        var pom = MavenPomTestSupport.parse(root.resolve("pom.xml")).getDocumentElement();
        String product = MavenPomTestSupport.directChildText(pom, "version");
        require(product != null && product.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-SNAPSHOT)?"),
            "standalone optimizer requires an explicit product version");
        String version = product.replaceFirst("-SNAPSHOT$", "") + "-issue1657-" + revision.substring(0, 12);
        String basename = SDK + "-" + version;
        String coordinate = "repository/io/github/carstenartur/" + SDK + "/" + version + "/";

        var inputs = new ArrayList<Path>();
        for (String module : MODULES) {
            inputs.add(stagedArtifact(root, repository, product, module, ".jar"));
        }
        try (var dependencies = Files.newDirectoryStream(external, "*.jar")) {
            for (Path dependency : dependencies) inputs.add(dependency);
        }
        require(inputs.size() > MODULES.size(), "external runtime dependency directory is empty");
        Path snapshot = Files.createTempDirectory(scratch, "packaging-inputs-");
        var copied = new ArrayList<Path>();
        var inputHashes = new TreeMap<String, String>();
        for (Path input : inputs) {
            require(Files.isRegularFile(input), "install the SDK runtime reactor first; missing " + input);
            Path copy = Files.copy(input, snapshot.resolve(input.getFileName()));
            inputHashes.put(copy.getFileName().toString(), sha256(Files.readAllBytes(copy)));
            copied.add(copy);
        }

        var provenance = new TreeMap<String, Object>(Map.of("schema", "regelsuche.optimization-distribution/v1",
            "sourceRevision", revision, "version", version, "apiRevision", "1",
            "semanticsRevision", "java25-numeric/v1", "javaRelease", 25, "publiclyPublished", false,
            "inputs", inputHashes));
        var notices = Map.of("META-INF/licenses/Regelsuche-LICENSE", Files.readAllBytes(root.resolve("LICENSE")),
            "META-INF/licenses/Sandbox-EPL-2.0.txt", Files.readAllBytes(root.resolve(SDK)
                .resolve("src/main/resources/META-INF/licenses/Sandbox-EPL-2.0.txt")),
            "META-INF/THIRD_PARTY_NOTICES.md", Files.readAllBytes(root.resolve("THIRD_PARTY_NOTICES.md")));
        var runtime = shade(copied);
        runtime.putAll(notices);
        runtime.put("META-INF/MANIFEST.MF", utf8(("Manifest-Version: 1.0\n"
            + "Implementation-Title: Regelsuche Optimization SDK\nImplementation-Version: " + version + "\n"
            + "Optimization-API-Revision: 1\nNumeric-Semantics-Revision: java25-numeric/v1\n"
            + "Source-Revision: " + revision + "\nMulti-Release: true\n\n").replace("\n", "\r\n")));
        runtime.put("META-INF/regelsuche/optimization-provenance.json", canonicalJson(provenance));

        var sources = new TreeMap<String, byte[]>();
        for (String module : MODULES) {
            Path source = root.resolve(module).resolve("src/main/java");
            for (Path file : regularFiles(source, Set.of())) {
                if (file.toString().endsWith(".java")) {
                    String member = memberName(source, file);
                    require(sources.putIfAbsent(member, Files.readAllBytes(file)) == null,
                        "duplicate SDK source: " + member);
                }
            }
        }
        sources.putAll(notices);
        Path javadoc = stagedArtifact(root, repository, product, SDK, "-javadoc.jar");
        var documentation = readZip(javadoc);
        documentation.putAll(notices);

        Path binary = output.resolve(coordinate + basename + "-all.jar");
        writeZip(binary, runtime);
        Path consumer = root.resolve(CONSUMER);
        String transcript = qualifyConsumer(consumer, binary, scratch);
        String digest = sha256(Files.readAllBytes(binary));
        provenance.put("standaloneJarSha256", digest);
        provenance.put("consumer", Map.of("freshDirectory", true, "emptyDependencyCache", true, "output", transcript));

        Path sourceJar = output.resolve(coordinate + basename + "-sources.jar");
        Path javadocJar = output.resolve(coordinate + basename + "-javadoc.jar");
        writeZip(sourceJar, sources);
        writeZip(javadocJar, documentation);
        Path coordinatePom = output.resolve(coordinate + basename + ".pom");
        Files.writeString(coordinatePom, """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
            <groupId>io.github.carstenartur</groupId><artifactId>regelsuche-optimization-sdk</artifactId><version>%s</version>
            <name>Qualified standalone Regelsuche optimization SDK</name><description>Local revision %s; classifier all contains the runtime closure.</description>
            </project>
            """.formatted(version, revision), StandardOpenOption.CREATE_NEW);

        var members = new TreeMap<>(notices);
        for (Path artifact : List.of(binary, sourceJar, javadocJar, coordinatePom)) {
            byte[] bytes = Files.readAllBytes(artifact);
            String name = memberName(output, artifact);
            byte[] checksum = utf8(sha256(bytes) + "\n");
            members.put(name, bytes);
            members.put(name + ".sha256", checksum);
            Files.write(output.resolve(name + ".sha256"), checksum, StandardOpenOption.CREATE_NEW);
        }
        String report = basename + "-qualification.json";
        byte[] receipt = canonicalJson(provenance);
        Files.write(output.resolve(report), receipt, StandardOpenOption.CREATE_NEW);
        members.put(report, receipt);
        members.put("LICENSE", notices.get("META-INF/licenses/Regelsuche-LICENSE"));
        members.put("README.md", Files.readAllBytes(root.resolve(SDK).resolve("README.md")));
        for (Path file : regularFiles(consumer, CONSUMER_BUILD_DIRS)) {
            members.put("consumer/" + memberName(consumer, file), Files.readAllBytes(file));
        }
        Path archive = output.resolve(basename + ".zip");
        writeZip(archive, members);
        return new Result(version, revision, binary, digest, archive);
    }

    private static Path stagedArtifact(Path root, Path repository, String version, String module, String suffix)
            throws IOException {
        String name = module + "-" + version + suffix;
        Path target = root.resolve(module).resolve("target").resolve(name);
        Path installed = repository.resolve("io/github/carstenartur").resolve(module).resolve(version).resolve(name);
        require(Files.isRegularFile(installed) && Files.isRegularFile(target) && Files.mismatch(installed, target) == -1,
            "installed SDK artifact differs from the clean reactor build: " + name);
        return installed;
    }

    static Map<String, byte[]> shade(List<Path> jars) throws IOException {
        var entries = new TreeMap<String, byte[]>();
        var services = new TreeMap<String, TreeSet<String>>();
        for (Path jar : jars.stream().sorted(Comparator.comparing(Path::getFileName)).toList()) {
            for (var file : readZip(jar).entrySet()) {
                String name = file.getKey();
                String upper = name.toUpperCase(Locale.ROOT);
                if (name.endsWith("module-info.class") || upper.equals("META-INF/MANIFEST.MF")
                        || name.startsWith("META-INF/") && SIGNATURE_SUFFIXES.stream().anyMatch(upper::endsWith)) {
                    continue;
                }
                String runtimePath = name.replaceFirst("^META-INF/versions/[0-9]+/", "");
                require(FORBIDDEN.stream().noneMatch(runtimePath::startsWith),
                    "forbidden SDK runtime dependency: " + name);
                byte[] bytes = file.getValue();
                if (name.startsWith("META-INF/services/")) {
                    var lines = services.computeIfAbsent(name, ignored -> new TreeSet<>());
                    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().lines()
                        .filter(line -> !line.isBlank()).forEach(lines::add);
                } else if (entries.containsKey(name) && !Arrays.equals(entries.get(name), bytes)) {
                    require(!name.endsWith(".class"), "duplicate class with different bytecode: " + name);
                    String relocated = "META-INF/dependency-resources/" + jar.getFileName() + "/" + name;
                    require(entries.putIfAbsent(relocated, bytes) == null, "duplicate relocated resource: " + relocated);
                } else {
                    entries.put(name, bytes);
                }
            }
        }
        services.forEach((name, lines) -> entries.put(name, utf8(String.join("\n", lines) + "\n")));
        return entries;
    }

    static Map<String, byte[]> readZip(Path path) throws IOException {
        var entries = new TreeMap<String, byte[]>();
        try (var zip = new ZipFile(path.toFile())) {
            var files = zip.entries();
            while (files.hasMoreElements()) {
                var file = files.nextElement();
                if (file.isDirectory()) continue;
                String name = file.getName();
                validateMember(name, "unsafe archive entry");
                try (var content = zip.getInputStream(file)) {
                    require(entries.putIfAbsent(name, content.readAllBytes()) == null, "duplicate archive entry: " + name);
                }
            }
        }
        return entries;
    }

    static void writeZip(Path output, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (var file = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW);
                var zip = new ZipArchiveOutputStream(file)) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            for (var member : new TreeMap<>(entries).entrySet()) {
                var entry = new ZipArchiveEntry(member.getKey());
                entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                entry.setUnixMode(0100644);
                zip.putArchiveEntry(entry);
                zip.write(member.getValue());
                zip.closeArchiveEntry();
            }
        }
    }

    private static String qualifyConsumer(Path consumer, Path binary, Path scratch) throws Exception {
        Path working = Files.createTempDirectory(scratch, "fresh-consumer-");
        Path classes = Files.createDirectory(working.resolve("classes"));
        Path emptyHome = Files.createDirectory(working.resolve("empty-dependency-cache"));
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path source = consumer.resolve("src/main/java");
        var compile = new ArrayList<>(List.of(javaHome.resolve("bin/javac").toString(), "--release", "25",
            "-cp", binary.toAbsolutePath().toString(), "-d", classes.toString()));
        for (Path file : regularFiles(source, Set.of()).stream().sorted().toList()) {
            if (!file.toString().endsWith(".java")) continue;
            Path copy = working.resolve("src").resolve(source.relativize(file));
            Files.createDirectories(copy.getParent());
            Files.copy(file, copy);
            compile.add(copy.toString());
        }
        run(working, scratch, compile.toArray(String[]::new));
        String result = run(working, scratch, javaHome.resolve("bin/java").toString(),
            "-Duser.home=" + emptyHome, "-cp", classes + File.pathSeparator + binary.toAbsolutePath(),
            "example.JavaOptimization");
        for (String marker : List.of("optimization=VERIFIED", "checked=ORIGINAL_OVERFLOW_DETECTED",
                "pair=VERIFIED_ONE_PASS_54")) {
            require(result.lines().anyMatch(marker::equals), "standalone consumer did not produce " + marker);
        }
        return String.join("\n", result.lines().toList()).strip();
    }

    private static String run(Path directory, Path scratch, String... command) throws Exception {
        Path log = Files.createTempFile(scratch, "optimizer-", ".log");
        Process process = new ProcessBuilder(command).directory(directory.toFile())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            require(process.waitFor(2, TimeUnit.MINUTES), "standalone optimizer command timed out: " + log);
            String output = Files.readString(log);
            require(process.exitValue() == 0, command[0] + " failed: " + output);
            return output;
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private static String memberName(Path directory, Path member) {
        return directory.relativize(member).toString().replace(File.separatorChar, '/');
    }
}
