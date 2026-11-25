package de.regelsuche.build;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.w3c.dom.Element;

final class StudentSdkArchive {
    private static final String POM_NAMESPACE = "http://maven.apache.org/POM/4.0.0";
    private static final String GROUP_PATH = "repository/io/github/carstenartur/";
    private static final String BOM = "regelsuche-bom";
    private static final String PARENT = "regelsuche-parent";
    private static final String EXAMPLES_ROOT = "examples/external-consumers";
    private static final String PINNED_EXAMPLE = "number-theory-plan-java25";
    private static final List<String> EXAMPLES = List.of("hello-rule-java25", "geometric-sequence-domain-java25",
        "finite-difference-domain-java25", "solver-adapter-java25", PINNED_EXAMPLE,
        "extension-runtime-java25", "java-optimization-java25", "checked-math-java25");
    private static final List<String> INPUTS = List.of("LICENSE", "release.properties", "gradlew", "gradlew.bat",
        "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
        "scripts/create-student-discovery-domain.py", "docs/java-discovery-sdk.md", "docs/java-sdk-quickstart.md",
        "docs/java-sdk-api-policy.md", "docs/java-sdk-domain-tutorial.md", "docs/java-sdk-human-dx.md",
        "docs/generic-extensions.md", "regelsuche-optimization-sdk/README.md",
        "docs/java-optimization-sdk-qualification.md",
        "docs/superpowers/specs/2026-09-11-generic-extension-program-discovery-design.md");
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final DefaultPrettyPrinter PRETTY = new DefaultPrettyPrinter().withSeparators(
        Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER)
            .withObjectEmptySeparator("").withArrayEmptySeparator(""))
        .withObjectIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE.withLinefeed("\n"))
        .withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE.withLinefeed("\n"));

    private StudentSdkArchive() {}

    static void create(Path root, Path repository, String version, Path output) throws Exception {
        require(version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-SNAPSHOT)?"), "SDK version must be a product version");
        Map<String, Object> policy = JSON.readValue(root.resolve("config/sdk/public-api.json").toFile(), new TypeReference<>() {});
        if (!(policy.get("modules") instanceof List<?> declared)) throw new IOException("SDK policy has no module inventory");
        var modules = new ArrayList<Object>(declared);
        modules.add(BOM);
        var files = new TreeMap<String, byte[]>();
        var ledger = new TreeMap<String, Map<String, Object>>();
        boolean requiresParent = false;
        for (Object item : modules) {
            if (!(item instanceof String module) || !module.matches("regelsuche-[a-z0-9-]+"))
                throw new IOException("Invalid public SDK module: " + item);
            Path coordinate = repository.resolve("io/github/carstenartur").resolve(module).resolve(version);
            Path pom = single(coordinate, module + "-*.pom");
            var model = MavenPomTestSupport.parse(pom).getDocumentElement();
            var parent = pomChild(model, "parent");
            requiresParent |= parent != null;
            String declaredVersion = exactVersion(model);
            if (declaredVersion == null) declaredVersion = exactVersion(parent);
            require(version.equals(declaredVersion), module + " POM version differs from SDK version");
            artifact(files, ledger, module, version, ".pom", pom);
            if (!BOM.equals(module)) {
                artifact(files, ledger, module, version, ".jar", binary(coordinate, module));
                artifact(files, ledger, module, version, "-sources.jar", single(coordinate, module + "-*-sources.jar"));
                Path javadoc = single(coordinate, module + "-*-javadoc.jar");
                artifact(files, ledger, module, version, "-javadoc.jar", javadoc);
                unpackJavadoc(files, module, javadoc);
            }
        }
        Path parent = repository.resolve("io/github/carstenartur").resolve(PARENT).resolve(version);
        if (Files.exists(parent)) {
            Path pom = single(parent, PARENT + "-*.pom");
            require(version.equals(exactVersion(MavenPomTestSupport.parse(pom).getDocumentElement())),
                "parent POM version differs from SDK version");
            files.put(coordinate(PARENT, version, ".pom"), Files.readAllBytes(pom));
        } else {
            require(!requiresParent, "published SDK POM requires missing regelsuche-parent");
        }
        policy.put("productVersion", version);
        policy.put("artifacts", ledger);
        policy.put("distribution", "GitHub Release SDK archive");
        policy.put("publicMavenCentral", false);
        files.put("sdk-compatibility.json", canonicalJson(policy));
        addSources(root, version, files);
        files.put("README.md", utf8("# Regelsuche Java SDK " + version + "\n\n"
            + "Run the [seven checked calculations](examples/external-consumers/checked-math-java25/README.md) with Java 25 and Maven 3.9.9+:\n\n"
            + "```sh\nmvn -f examples/external-consumers/checked-math-java25/pom.xml clean verify\n```\n\n"
            + "The bundled Maven projects use this archive's SDK version without overrides.\n"
            + "The [SDK guide](docs/java-sdk-quickstart.md) also documents the published Central release and historical archives.\n\n"
            + "Use the `repository` directory as a Maven repository; `sdk-compatibility.json`\n"
            + "records coordinates, API revisions and artifact hashes. Each public module has\n"
            + "sources and Javadoc JARs; browse `javadoc/<module>/index.html` locally.\n"));
        var checksums = new StringBuilder();
        files.forEach((name, bytes) -> checksums.append(sha256(bytes)).append("  ").append(name).append('\n'));
        files.put("SHA256SUMS.txt", utf8(checksums.toString()));
        writeZip(output, version, files);
    }

    private static String exactVersion(Element pom) {
        var version = pomChild(pom, "version");
        return version == null ? null : version.getTextContent();
    }

    private static Element pomChild(Element parent, String name) {
        if (parent == null) return null;
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getLocalName())
                    && POM_NAMESPACE.equals(element.getNamespaceURI())) return element;
        }
        return null;
    }

    private static Path single(Path directory, String glob) throws IOException {
        var files = new ArrayList<Path>();
        if (Files.isDirectory(directory)) {
            try (var paths = Files.newDirectoryStream(directory, glob)) {
                for (Path file : paths) if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) files.add(file);
            }
        }
        require(files.size() == 1, "expected one " + glob + " in " + directory + ", found " + files.size());
        return files.getFirst();
    }

    private static Path binary(Path directory, String module) throws IOException {
        var files = new ArrayList<Path>();
        try (var paths = Files.newDirectoryStream(directory, module + "-*.jar")) {
            for (Path file : paths) {
                String name = file.getFileName().toString();
                if (!name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar")) files.add(file);
            }
        }
        require(files.size() == 1, module + " binary JAR missing or ambiguous");
        require(Files.isRegularFile(files.getFirst(), LinkOption.NOFOLLOW_LINKS), "SDK artifact must be a regular file");
        return files.getFirst();
    }

    private static String coordinate(String module, String version, String suffix) {
        return GROUP_PATH + module + "/" + version + "/" + module + "-" + version + suffix;
    }

    private static void artifact(Map<String, byte[]> files, Map<String, Map<String, Object>> ledger,
            String module, String version, String suffix, Path source) throws IOException {
        String target = coordinate(module, version, suffix);
        byte[] bytes = Files.readAllBytes(source);
        String hash = sha256(bytes);
        files.put(target, bytes);
        files.put(target + ".sha256", utf8(hash + "\n"));
        ledger.put(target, Map.of("bytes", bytes.length, "sha256", hash));
    }

    private static void unpackJavadoc(Map<String, byte[]> files, String module, Path file) throws IOException {
        try (var zip = new ZipFile(file.toFile())) {
            var index = zip.getEntry("index.html");
            require(index != null && index.getName().equals("index.html") && !index.isDirectory(),
                module + " Javadoc has no index.html");
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("META-INF/")) continue;
                validateMember(name, "invalid Javadoc path");
                try (var input = zip.getInputStream(entry)) {
                    byte[] bytes = input.readAllBytes();
                    var crc = new CRC32();
                    crc.update(bytes);
                    require(crc.getValue() == entry.getCrc(), "invalid Javadoc CRC for " + module + "/" + name);
                    files.put("javadoc/" + module + "/" + name, bytes);
                }
            }
        }
    }

    private static void addSources(Path root, String version, Map<String, byte[]> members) throws IOException {
        for (String name : INPUTS) {
            byte[] content = Files.readAllBytes(root.resolve(name));
            members.put(name, name.endsWith(".md") ? replace(content, "0.5.0-SNAPSHOT", version) : content);
        }
        for (String example : EXAMPLES) {
            String prefix = EXAMPLES_ROOT + "/" + example;
            Path source = root.resolve(prefix);
            require(Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS), "required SDK example is missing or invalid: " + example);
            if (PINNED_EXAMPLE.equals(example)) {
                StudentSdkPinnedConsumer.files(source).forEach((name, data) -> members.put(prefix + "/" + name, data));
                continue;
            }
            for (Path file : regularFiles(source, Set.of("build", ".gradle", "target", "repository"))) {
                String name = source.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                byte[] data = Files.readAllBytes(file);
                if (name.equals("pom.xml")) data = StudentSdkConsumerPom.stage(file, version);
                if (name.endsWith(".gradle") || name.endsWith(".md")) {
                    data = replace(replace(data, "0.5.0-SNAPSHOT", version), "0.4.0-SNAPSHOT", version);
                }
                members.put(prefix + "/" + name, data);
            }
        }
    }

    static List<Path> regularFiles(Path source, Set<String> excluded) throws IOException {
        var result = new ArrayList<Path>();
        try (var paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (source.equals(path)) continue;
                boolean skip = false;
                for (Path part : source.relativize(path)) if (excluded.contains(part.toString())) skip = true;
                if (skip) continue;
                require(!Files.isSymbolicLink(path), "SDK examples must not contain symbolic links: " + path);
                if (Files.isRegularFile(path)) result.add(path);
            }
        }
        return result;
    }

    private static void writeZip(Path output, String version, Map<String, byte[]> members) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (var stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                var zip = new ZipArchiveOutputStream(stream)) {
            zip.setMethod(ZipArchiveOutputStream.DEFLATED);
            for (var file : members.entrySet()) {
                var entry = new ZipArchiveEntry("regelsuche-sdk-" + version + "/" + file.getKey());
                entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                entry.setUnixMode(file.getKey().equals("gradlew") ? 0100755 : 0100644);
                zip.putArchiveEntry(entry);
                zip.write(file.getValue());
                zip.closeArchiveEntry();
            }
        }
    }

    static void validateMember(String name, String message) throws IOException {
        Path path = Path.of(name);
        require(!name.isBlank() && !path.isAbsolute() && !name.contains("\\") && !path.startsWith("..")
            && path.equals(path.normalize()), message + ": " + name);
    }

    static byte[] canonicalJson(Map<String, ?> value) throws IOException {
        return utf8(JSON.writer(PRETTY).writeValueAsString(value) + "\n");
    }

    static byte[] replace(byte[] data, String old, String replacement) {
        // ISO-8859-1 is a bijection over bytes. Replace only the ASCII contract text.
        return new String(data, StandardCharsets.ISO_8859_1).replace(old, replacement).getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
