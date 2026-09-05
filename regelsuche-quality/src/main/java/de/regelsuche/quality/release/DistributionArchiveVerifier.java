package de.regelsuche.quality.release;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;

/**
 * Compares actual distribution payloads with this build's resolved runtime files.
 * Does not extract archives, resolve dependencies, publish, or invoke host tools.
 * TAR support is deliberately limited to POSIX ustar regular files/directories;
 * extensions, links, corrupt headers and incomplete end markers fail closed.
 */
public final class DistributionArchiveVerifier {
    private static final int BLOCK = 512;
    private static final int MAX_ENTRIES = 8192;
    private static final int METADATA_LIMIT = 2 * 1024 * 1024;
    private static final long FILE_LIMIT = 1024L * 1024 * 1024;
    private static final long TOTAL_LIMIT = 4 * FILE_LIMIT;
    private static final List<String> DOCUMENTS = List.of(
        "README.md", "LICENSE", "CITATION.cff", "CITATION.md", "codemeta.json");
    private static final String OPENAPI = "web/openapi/openapi.json";

    private DistributionArchiveVerifier() {}

    /** Successful evidence is returned only after both complete archives pass. */
    public record Report(String version, int files, int runtimeLibraries,
                         int productModules, String inventorySha256) {
        public String summary() {
            return "distributionArchiveAudit=VERIFIED version=" + version
                + " files=" + files + " runtimeLibraries=" + runtimeLibraries
                + " productModules=" + productModules
                + " inventorySha256=" + inventorySha256;
        }
    }

    private record Fingerprint(long size, String sha256, long crc32) {}
    private record Expected(Map<String, Fingerprint> files, int libraries, int modules) {}

    public static Report verify(Path repositoryRoot, String version) throws IOException {
        return verify(repositoryRoot, version, repositoryRoot.resolve("app/target"));
    }

    /** artifactDirectory may be a separately retained copy of this same build. */
    public static Report verify(Path repositoryRoot, String version, Path artifactDirectory)
            throws IOException {
        check(version != null && version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-SNAPSHOT)?"),
            "Invalid distribution version");
        Path root = repositoryRoot.toAbsolutePath().normalize();
        String prefix = "regelsuche-" + version + "/";
        Expected expected = expectedFiles(root, version, prefix);
        Path stem = artifactDirectory.resolve("regelsuche-" + version);
        check(expected.files().get(prefix + "regelsuche.jar").equals(
            fingerprint(Path.of(stem + ".jar"))), "Standalone application JAR differs from build");
        verifyZip(Path.of(stem + ".zip"), prefix, expected.files());
        verifyTar(Path.of(stem + ".tar"), prefix, expected.files());
        StringBuilder inventory = new StringBuilder("regelsuche-distribution-inventory/v1\n");
        expected.files().forEach((name, value) -> inventory.append(name).append('\t')
            .append(value.size()).append('\t').append(value.sha256()).append('\n'));
        String hash = HexFormat.of().formatHex(sha256().digest(
            inventory.toString().getBytes(StandardCharsets.UTF_8)));
        return new Report(version, expected.files().size(), expected.libraries(),
            expected.modules(), hash);
    }

    public static void main(String[] args) throws IOException {
        check(args.length == 2 || args.length == 3,
            "Usage: DistributionArchiveVerifier <repository-root> <version> [artifact-directory]");
        Path root = Path.of(args[0]);
        Path artifacts = args.length == 3 ? Path.of(args[2]) : root.resolve("app/target");
        System.out.println(verify(root, args[1], artifacts).summary());
    }

    private static Expected expectedFiles(Path root, String version, String prefix)
            throws IOException {
        Map<String, Fingerprint> files = new TreeMap<>();
        Path application = root.resolve("app/target/regelsuche-" + version + ".jar");
        verifyApplication(root, application, version);
        addExpected(files, prefix + "regelsuche.jar", application);
        for (String name : DOCUMENTS) addExpected(files, prefix + name, root.resolve(name));
        for (String name : List.of("regelsuche", "regelsuche.bat")) {
            addExpected(files, prefix + "bin/" + name, root.resolve("app/src/main/scripts/" + name));
        }
        String classpath = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(
            readBounded(root.resolve("app/target/runtime-classpath.txt")))).toString().strip();
        check(!classpath.isEmpty(), "Runtime classpath is empty");
        String[] libraries = classpath.split(Pattern.quote(File.pathSeparator), -1);
        check(libraries.length < MAX_ENTRIES - files.size(), "Runtime classpath is too large");
        Set<String> modules = new HashSet<>();
        for (String value : libraries) {
            Path library = Path.of(value);
            check(!value.isBlank() && library.isAbsolute(), "Runtime classpath requires absolute files");
            String name = library.getFileName().toString();
            check(name.endsWith(".jar"), "Runtime classpath contains a non-JAR: " + name);
            if (name.startsWith("regelsuche-")) {
                String id = productIdentity(library, version);
                check(!id.equals("regelsuche-app") && modules.add(id),
                    "Duplicate product module or application in lib: " + id);
                check(name.equals(id + "-" + version + ".jar"),
                    "Product JAR filename/version mismatch: " + name);
            }
            addExpected(files, prefix + "lib/" + name, library);
        }
        check(!modules.isEmpty(), "Runtime classpath has no product modules");
        long total = files.values().stream().mapToLong(Fingerprint::size).sum();
        check(total <= TOTAL_LIMIT, "Distribution exceeds the four-GiB audit limit");
        return new Expected(files, libraries.length, modules.size());
    }

    private static void addExpected(Map<String, Fingerprint> files, String name, Path source)
            throws IOException {
        check(files.putIfAbsent(name, fingerprint(source)) == null,
            "Ambiguous runtime filename: " + name);
    }

    private static void verifyApplication(Path root, Path application, String version)
            throws IOException {
        check(productIdentity(application, version).equals("regelsuche-app"),
            "Unexpected application artifact identity");
        try (ZipFile jar = new ZipFile(application.toFile())) {
            Manifest manifest = new Manifest(new ByteArrayInputStream(readEntry(jar, "META-INF/MANIFEST.MF")));
            check(version.equals(manifest.getMainAttributes().getValue("Implementation-Version")),
                "Application manifest version mismatch");
            check("de.regelsuche.App".equals(manifest.getMainAttributes().getValue("Main-Class")),
                "Application entry point mismatch");
            check(Arrays.equals(readBounded(root.resolve("app/src/main/resources/" + OPENAPI)),
                readEntry(jar, OPENAPI)), "Embedded OpenAPI differs from source");
        }
    }

    private static String productIdentity(Path artifact, String version) throws IOException {
        try (ZipFile jar = new ZipFile(artifact.toFile())) {
            List<String> identities = jar.stream().map(entry -> entry.getName())
                .filter(name -> name.startsWith("META-INF/maven/de.regelsuche/")
                    && name.endsWith("/pom.properties")).toList();
            check(identities.size() == 1, "Missing or ambiguous product metadata: " + artifact);
            Properties properties = new Properties();
            properties.load(new ByteArrayInputStream(readEntry(jar, identities.getFirst())));
            String id = properties.getProperty("artifactId", "");
            check("de.regelsuche".equals(properties.getProperty("groupId"))
                    && version.equals(properties.getProperty("version"))
                    && id.startsWith("regelsuche-")
                    && identities.getFirst().equals("META-INF/maven/de.regelsuche/" + id + "/pom.properties"),
                "Product metadata/version mismatch: " + artifact);
            return id;
        }
    }

    private static byte[] readEntry(ZipFile zip, String name) throws IOException {
        check(zip.stream().filter(entry -> entry.getName().equals(name)).count() == 1,
            "Missing or duplicate embedded entry: " + name);
        try (InputStream input = zip.getInputStream(zip.getEntry(name))) {
            return readBounded(input);
        }
    }

    private static byte[] readBounded(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) { return readBounded(input); }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(METADATA_LIMIT + 1);
        check(bytes.length <= METADATA_LIMIT, "Metadata exceeds the two-MiB audit limit");
        return bytes;
    }

    private static void verifyZip(Path path, String prefix, Map<String, Fingerprint> expected)
            throws IOException {
        Set<String> seen = new HashSet<>();
        Set<String> files = new HashSet<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = checkedName(entry.getName(), entry.isDirectory(), prefix, seen, expected);
                if (entry.isDirectory()) {
                    check(entry.getSize() == 0, "ZIP directory has a payload: " + name);
                } else {
                    Fingerprint wanted = expected.get(name);
                    check(entry.getSize() == wanted.size(), "ZIP size mismatch: " + name);
                    try (InputStream input = zip.getInputStream(entry)) {
                        Fingerprint actual = fingerprint(input, wanted.size());
                        check(input.read() == -1 && wanted.equals(actual)
                                && entry.getCrc() == actual.crc32(), "ZIP payload/CRC mismatch: " + name);
                    }
                    files.add(name);
                }
            }
        }
        check(files.equals(expected.keySet()), "ZIP is missing expected files: " + missing(expected, files));
    }

    private static void verifyTar(Path path, String prefix, Map<String, Fingerprint> expected)
            throws IOException {
        Set<String> seen = new HashSet<>();
        Set<String> files = new HashSet<>();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            while (true) {
                byte[] header = readBlock(input);
                if (allZero(header)) {
                    finishTar(input);
                    break;
                }
                verifyTarHeader(header);
                readTarEntry(input, header, prefix, seen, files, expected);
            }
        }
        check(files.equals(expected.keySet()), "TAR is missing expected files: " + missing(expected, files));
    }

    private static void readTarEntry(InputStream input, byte[] header, String prefix,
            Set<String> seen, Set<String> files, Map<String, Fingerprint> expected) throws IOException {
        boolean directory = header[156] == '5';
        check(directory || header[156] == '0' || header[156] == 0,
            "Unsupported TAR entry type (only regular ustar files/directories are accepted)");
        String parent = field(header, 345, 155);
        String rawName = (parent.isEmpty() ? "" : parent + "/") + field(header, 0, 100);
        String name = checkedName(rawName, directory, prefix, seen, expected);
        long size = octal(header, 124, 12);
        if (directory) {
            check(size == 0, "TAR directory has a payload: " + name);
            verifyTarMode(header, name, 0755, 040000, "directory");
            return;
        }
        Fingerprint wanted = expected.get(name);
        check(size == wanted.size(), "TAR size mismatch: " + name);
        verifyTarMode(header, name, name.equals(prefix + "bin/regelsuche") ? 0755 : 0644,
            0100000, "file");
        check(wanted.equals(fingerprint(input, size)), "TAR payload mismatch: " + name);
        int padding = (int) ((BLOCK - size % BLOCK) % BLOCK);
        byte[] bytes = input.readNBytes(padding);
        check(bytes.length == padding && allZero(bytes), "Invalid TAR padding: " + name);
        files.add(name);
    }

    private static void verifyTarMode(byte[] header, String name, long permissions,
            long fileType, String kind) throws IOException {
        long mode = octal(header, 100, 8);
        // Plexus preserves Unix file-type bits; other ustar writers store only permissions.
        // Accept exactly these two encodings, not a mask that hides special or foreign bits.
        long typedMode = fileType | permissions;
        check(mode == permissions || mode == typedMode,
            "TAR " + kind + " mode mismatch: " + name + " (got " + Long.toOctalString(mode)
                + ", expected " + Long.toOctalString(permissions) + " or "
                + Long.toOctalString(typedMode) + ")");
    }

    private static String checkedName(String raw, boolean directory, String prefix,
            Set<String> seen, Map<String, Fingerprint> expected) throws IOException {
        String name = directory && !raw.endsWith("/") ? raw + "/" : raw;
        check(!name.contains("\\") && name.startsWith(prefix), "Archive path outside root: " + name);
        String relative = name.substring(prefix.length());
        String stripped = directory && !relative.isEmpty()
            ? relative.substring(0, relative.length() - 1) : relative;
        if (!stripped.isEmpty()) {
            for (String part : stripped.split("/", -1)) {
                check(!part.isEmpty() && !part.equals(".") && !part.equals(".."),
                    "Noncanonical archive path: " + name);
            }
        }
        check(seen.size() < MAX_ENTRIES && seen.add(name), "Duplicate/excessive archive entry: " + name);
        if (directory) {
            check(name.equals(prefix) || name.equals(prefix + "lib/") || name.equals(prefix + "bin/"),
                "Unexpected archive directory: " + name);
        } else {
            check(expected.containsKey(name), "Unexpected archive file: " + name);
        }
        return name;
    }

    private static void verifyTarHeader(byte[] header) throws IOException {
        long checksum = 0;
        for (int i = 0; i < header.length; i++) {
            checksum += i >= 148 && i < 156 ? 32 : Byte.toUnsignedInt(header[i]);
        }
        check(octal(header, 148, 8) == checksum, "TAR header checksum mismatch");
        check(field(header, 257, 6).equals("ustar") && header[262] == 0
                && field(header, 263, 2).equals("00"), "Unsupported TAR format (POSIX ustar required)");
    }

    private static void finishTar(InputStream input) throws IOException {
        check(allZero(readBlock(input)), "TAR requires two end blocks");
        for (int blocks = 0; ; blocks++) {
            byte[] trailer = input.readNBytes(BLOCK);
            if (trailer.length == 0) return;
            check(blocks < 1024 && trailer.length == BLOCK && allZero(trailer),
                "Nonzero, truncated or excessive TAR trailer");
        }
    }

    private static byte[] readBlock(InputStream input) throws IOException {
        byte[] block = input.readNBytes(BLOCK);
        check(block.length == BLOCK, "Truncated TAR header/end marker");
        return block;
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) if (value != 0) return false;
        return true;
    }

    private static String field(byte[] bytes, int offset, int length) throws IOException {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) end++;
        return StandardCharsets.UTF_8.newDecoder().decode(
            ByteBuffer.wrap(bytes, offset, end - offset)).toString();
    }

    private static long octal(byte[] bytes, int offset, int length) throws IOException {
        String text = field(bytes, offset, length).strip();
        check(text.matches("[0-7]{1,11}"), "Invalid TAR octal field");
        return Long.parseLong(text, 8);
    }

    private static Fingerprint fingerprint(Path path) throws IOException {
        check(Files.isRegularFile(path), "Missing regular build input: " + path);
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            Fingerprint result = fingerprint(input, Files.size(path));
            check(input.read() == -1, "Build input changed while reading: " + path);
            return result;
        }
    }

    private static Fingerprint fingerprint(InputStream input, long size) throws IOException {
        check(size >= 0 && size <= FILE_LIMIT, "File exceeds the one-GiB audit limit");
        MessageDigest digest = sha256();
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[8192];
        long remaining = size;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            check(count > 0, "Truncated archive/file payload");
            digest.update(buffer, 0, count);
            crc.update(buffer, 0, count);
            remaining -= count;
        }
        return new Fingerprint(size, HexFormat.of().formatHex(digest.digest()), crc.getValue());
    }

    private static Set<String> missing(Map<String, Fingerprint> expected, Set<String> actual) {
        Set<String> missing = new java.util.TreeSet<>(expected.keySet());
        missing.removeAll(actual);
        return missing;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void check(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
