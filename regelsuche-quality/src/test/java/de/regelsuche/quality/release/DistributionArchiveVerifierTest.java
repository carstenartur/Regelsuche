package de.regelsuche.quality.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DistributionArchiveVerifierTest {
    private static final String VERSION = "0.4.0";
    @TempDir Path temporary;

    @Test
    void acceptsCurrentReleaseAndSnapshotWithStableInventory() throws Exception {
        for (String version : List.of(VERSION, VERSION + "-SNAPSHOT")) {
            Fixture fixture = fixture(version);
            var report = DistributionArchiveVerifier.verify(fixture.root, version);
            assertEquals(10, report.files());
            assertEquals(2, report.runtimeLibraries());
            assertEquals(1, report.productModules());
            assertTrue(report.inventorySha256().matches("[0-9a-f]{64}"));
            assertEquals(report, DistributionArchiveVerifier.verify(fixture.root, version));
            assertTrue(report.summary().startsWith("distributionArchiveAudit=VERIFIED"));
            DistributionArchiveVerifier.main(new String[] {fixture.root.toString(), version});
        }
    }

    @Test
    void acceptsPermissionOnlyAndMatchingUnixTypeTarModes() throws Exception {
        Fixture f = fixture(VERSION);
        var expected = DistributionArchiveVerifier.verify(f.root, VERSION);
        for (int directoryMode : new int[] {0755, 040755}) {
            for (int regularMode : new int[] {0644, 0100644}) {
                for (int launcherMode : new int[] {0755, 0100755}) {
                    for (char regularType : new char[] {'0', 0}) {
                        f.directoryMode = directoryMode;
                        f.regularMode = regularMode;
                        f.launcherMode = launcherMode;
                        f.regularType = regularType;
                        f.writeArchives();
                        assertEquals(expected, DistributionArchiveVerifier.verify(f.root, VERSION));
                    }
                }
            }
        }
    }

    @Test
    void rejectsWrongTarPermissionsAndSpecialBitsInBothEncodings() throws Exception {
        Fixture f = fixture(VERSION);
        for (int type : new int[] {0, 0100000}) {
            for (int permissions : new int[] {0600, 0664, 0755, 04644, 02644, 01644}) {
                f.regularMode = type | permissions;
                f.writeArchives();
                rejected(f, "TAR file mode mismatch");
            }
        }
        f.regularMode = 0100644;
        for (int type : new int[] {0, 0100000}) {
            for (int permissions : new int[] {0644, 0700, 0777, 04755, 02755, 01755}) {
                f.launcherMode = type | permissions;
                f.writeArchives();
                rejected(f, "TAR file mode mismatch");
            }
        }
        f.launcherMode = 0100755;
        for (int type : new int[] {0, 040000}) {
            for (int permissions : new int[] {0644, 0700, 0777, 04755, 02755, 01755}) {
                f.directoryMode = type | permissions;
                f.writeArchives();
                rejected(f, "TAR directory mode mismatch");
            }
        }
    }

    @Test
    void rejectsContradictoryAndUnknownTarModeTypeBits() throws Exception {
        Fixture f = fixture(VERSION);
        for (int type : new int[] {040000, 0120000, 010000, 020000, 060000, 0140000, 0200000}) {
            f.regularMode = type | 0644;
            f.writeArchives();
            rejected(f, "TAR file mode mismatch");
        }
        f.regularMode = 0100644;
        for (int type : new int[] {0100000, 0120000, 010000, 020000, 060000, 0140000, 0200000}) {
            f.directoryMode = type | 0755;
            f.writeArchives();
            rejected(f, "TAR directory mode mismatch");
        }
    }

    @Test
    void checksAnIndependentlyCopiedArtifactDirectory() throws Exception {
        Fixture f = fixture(VERSION);
        Path copy = temporary.resolve("retained");
        Files.createDirectories(copy);
        for (String suffix : List.of(".jar", ".zip", ".tar")) {
            Files.copy(Path.of(f.stem + suffix), copy.resolve("regelsuche-" + VERSION + suffix));
        }
        assertEquals(DistributionArchiveVerifier.verify(f.root, VERSION),
            DistributionArchiveVerifier.verify(f.root, VERSION, copy));
        Files.writeString(copy.resolve("regelsuche-" + VERSION + ".jar"), "wrong standalone JAR");
        assertThrows(IOException.class, () -> DistributionArchiveVerifier.verify(f.root, VERSION, copy));
    }

    @Test
    void rejectsOldSnapshotRemovedDependencyAndDuplicateApplication() throws Exception {
        Fixture f = fixture(VERSION);
        for (String name : List.of("regelsuche-core-0.4.0-SNAPSHOT.jar", "removed-1.jar",
                "junit-test-only-1.jar", "regelsuche-app-0.4.0.jar")) {
            f.zipFiles.put(f.prefix + "lib/" + name, Files.readAllBytes(f.core));
            f.writeArchives();
            rejected(f, "Unexpected archive file");
            f.zipFiles.remove(f.prefix + "lib/" + name);
        }
    }

    @Test
    void rejectsMissingLibraryEvenWhenBothArchivesAgree() throws Exception {
        Fixture f = fixture(VERSION);
        f.zipFiles.remove(f.prefix + "lib/" + f.external.getFileName());
        f.tarFiles.remove(f.prefix + "lib/" + f.external.getFileName());
        f.writeArchives();
        rejected(f, "ZIP is missing expected files");
    }

    @Test
    void rejectsChangedZipPayloadWithAValidZipCrc() throws Exception {
        Fixture f = fixture(VERSION);
        f.zipFiles.put(f.prefix + "README.md", new byte[f.zipFiles.get(f.prefix + "README.md").length]);
        f.writeArchives();
        rejected(f, "ZIP payload/CRC mismatch");
    }

    @Test
    void rejectsChangedTarPayloadWhenZipIsCorrect() throws Exception {
        Fixture f = fixture(VERSION);
        f.tarFiles.put(f.prefix + "README.md", new byte[f.tarFiles.get(f.prefix + "README.md").length]);
        f.writeArchives();
        rejected(f, "TAR payload mismatch");
    }

    @Test
    void rejectsIncorrectZipCrcMetadata() throws Exception {
        Fixture f = fixture(VERSION);
        byte[] bytes = Files.readAllBytes(Path.of(f.stem + ".zip"));
        int eocd = bytes.length - 22; // Fixture has no ZIP comment or ZIP64 records.
        assertEquals(0x06054b50, ByteBuffer.wrap(bytes, eocd, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
        int central = ByteBuffer.wrap(bytes, eocd + 16, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        bytes[central + 16] ^= 1;
        Files.write(Path.of(f.stem + ".zip"), bytes);
        assertThrows(IOException.class, () -> DistributionArchiveVerifier.verify(f.root, VERSION));
    }

    @Test
    void rejectsDuplicateZipEntriesInsteadOfOverwritingTheFirst() throws Exception {
        Fixture f = fixture(VERSION);
        f.zipFiles.put(f.prefix + "FAKEME.md", f.zipFiles.get(f.prefix + "README.md"));
        f.writeArchives();
        byte[] bytes = Files.readAllBytes(Path.of(f.stem + ".zip"));
        byte[] from = "FAKEME.md".getBytes(StandardCharsets.UTF_8);
        byte[] to = "README.md".getBytes(StandardCharsets.UTF_8);
        int replacements = 0;
        for (int i = 0; i <= bytes.length - from.length; i++) {
            if (Arrays.equals(from, Arrays.copyOfRange(bytes, i, i + from.length))) {
                System.arraycopy(to, 0, bytes, i, to.length);
                replacements++;
            }
        }
        assertEquals(2, replacements, "local and central names must both be changed");
        Files.write(Path.of(f.stem + ".zip"), bytes);
        rejected(f, "Duplicate/excessive archive entry");
    }

    @Test
    void rejectsDuplicateTarEntries() throws Exception {
        Fixture f = fixture(VERSION);
        f.extras.add(new TarMember(f.prefix + "README.md", f.tarFiles.get(f.prefix + "README.md"), '0', 0644));
        f.writeArchives();
        rejected(f, "Duplicate/excessive archive entry");
    }

    @Test
    void rejectsTraversalAndUnexpectedPaths() throws Exception {
        Fixture f = fixture(VERSION);
        for (String name : List.of("outside.txt", f.prefix + "../escape", f.prefix + "lib/./extra.jar",
                f.prefix + "lib//extra.jar", f.prefix + "lib\\extra.jar")) {
            f.zipFiles.put(name, new byte[] {1});
            f.writeArchives();
            assertThrows(IOException.class, () -> DistributionArchiveVerifier.verify(f.root, VERSION));
            f.zipFiles.remove(name);
        }
    }

    @Test
    void rejectsTarLinksAndUnsupportedExtensions() throws Exception {
        Fixture f = fixture(VERSION);
        for (char type : new char[] {'1', '2', 'x', 'L', '6'}) {
            f.extras.clear();
            f.extras.add(new TarMember(f.prefix + "link", new byte[0], type, 0644));
            f.writeArchives();
            rejected(f, "Unsupported TAR entry type");
        }
    }

    @Test
    void rejectsUnknownTarDirectoryAndLostLauncherPermission() throws Exception {
        Fixture f = fixture(VERSION);
        f.extras.add(new TarMember(f.prefix + "unexpected/", new byte[0], '5', 0755));
        f.writeArchives();
        rejected(f, "Unexpected archive directory");
        f.extras.clear();
        f.launcherMode = 0644;
        f.writeArchives();
        rejected(f, "TAR file mode mismatch");
    }

    @Test
    void rejectsCorruptHeaderTruncatedTrailerAndConcatenatedTar() throws Exception {
        Fixture f = fixture(VERSION);
        Path path = Path.of(f.stem + ".tar");
        byte[] original = Files.readAllBytes(path);
        byte[] bad = original.clone();
        bad[0] ^= 1;
        Files.write(path, bad);
        rejected(f, "TAR header checksum mismatch");
        Files.write(path, Arrays.copyOf(original, original.length - 512));
        rejected(f, "Truncated TAR header/end marker");
        bad = Arrays.copyOf(original, original.length + 512);
        bad[original.length] = 1;
        Files.write(path, bad);
        rejected(f, "TAR trailer");
    }

    @Test
    void rejectsMissingTarAndWrongDeclaredSize() throws Exception {
        Fixture f = fixture(VERSION);
        f.tarFiles.remove(f.prefix + "README.md");
        f.writeArchives();
        rejected(f, "TAR is missing expected files");
        f.tarFiles.put(f.prefix + "README.md", new byte[] {1});
        f.writeArchives();
        rejected(f, "TAR size mismatch");
    }

    @Test
    void rejectsStaleRuntimeInventoryAndFilenameCollisions() throws Exception {
        Fixture f = fixture(VERSION);
        Path cp = f.root.resolve("app/target/runtime-classpath.txt");
        Files.writeString(cp, "");
        rejected(f, "Runtime classpath is empty");
        Files.writeString(cp, "relative.jar");
        rejected(f, "Runtime classpath requires absolute files");
        f.writeClasspath(List.of(f.core, f.external, f.external));
        rejected(f, "Ambiguous runtime filename");
        f.writeClasspath(List.of(f.core, f.external));
        writeJar(f.core, "regelsuche-core", "0.3.0", false, "0.3.0", new byte[0]);
        rejected(f, "Product metadata/version mismatch");
    }

    @Test
    void rejectsApplicationInClasspathAndMissingRuntimeFiles() throws Exception {
        Fixture f = fixture(VERSION);
        f.writeClasspath(List.of(f.core, f.external, Path.of(f.stem + ".jar")));
        rejected(f, "Duplicate product module or application in lib");
        f.writeClasspath(List.of(f.core, f.external));
        Files.delete(f.external);
        rejected(f, "Missing regular build input");
    }

    @Test
    void rejectsManifestAndEmbeddedOpenApiDrift() throws Exception {
        Fixture f = fixture(VERSION);
        Path source = f.root.resolve("app/src/main/resources/web/openapi/openapi.json");
        byte[] openapi = Files.readAllBytes(source);
        writeJar(Path.of(f.stem + ".jar"), "regelsuche-app", VERSION, true, "0.3.0", openapi);
        rejected(f, "Application manifest version mismatch");
        writeJar(Path.of(f.stem + ".jar"), "regelsuche-app", VERSION, true, VERSION, openapi);
        Files.writeString(source, "different source metadata");
        rejected(f, "Embedded OpenAPI differs from source");
    }

    @Test
    void rejectsInvalidCliAndVersions() {
        assertThrows(IOException.class, () -> DistributionArchiveVerifier.main(new String[0]));
        assertThrows(IOException.class, () -> DistributionArchiveVerifier.verify(temporary, "../../invalid"));
        assertThrows(IOException.class, () -> DistributionArchiveVerifier.verify(temporary, null));
    }

    private Fixture fixture(String version) throws Exception {
        return new Fixture(temporary.resolve("checkout with spaces " + version), version);
    }

    private static void rejected(Fixture f, String message) {
        IOException failure = assertThrows(IOException.class,
            () -> DistributionArchiveVerifier.verify(f.root, f.version));
        assertTrue(failure.getMessage().contains(message), failure::getMessage);
    }

    private static final class Fixture {
        final Path root;
        final String version;
        final String prefix;
        final Path stem;
        final Path core;
        final Path external;
        final Map<String, byte[]> zipFiles = new TreeMap<>();
        final Map<String, byte[]> tarFiles = new TreeMap<>();
        final List<TarMember> extras = new ArrayList<>();
        // Match Plexus ArchiveEntry/TarArchiver, including the Unix file-type bits.
        int directoryMode = 040755;
        int regularMode = 0100644;
        int launcherMode = 0100755;
        char regularType = '0';

        Fixture(Path root, String version) throws Exception {
            this.root = root;
            this.version = version;
            prefix = "regelsuche-" + version + "/";
            stem = root.resolve("app/target/regelsuche-" + version);
            core = root.resolve("repository/regelsuche-core-" + version + ".jar");
            external = root.resolve("repository/foreign-7.jar");
            for (String name : List.of("README.md", "LICENSE", "CITATION.cff", "CITATION.md", "codemeta.json")) {
                source(name, name, "fixture " + name + "\n");
            }
            source("bin/regelsuche", "app/src/main/scripts/regelsuche", "#!/bin/sh\nexec java example\n");
            source("bin/regelsuche.bat", "app/src/main/scripts/regelsuche.bat", "@echo off\r\njava example\r\n");
            Path openapi = root.resolve("app/src/main/resources/web/openapi/openapi.json");
            Files.createDirectories(openapi.getParent());
            Files.writeString(openapi, "{\"info\":{\"version\":\"" + version + "\"}}\n");
            writeJar(Path.of(stem + ".jar"), "regelsuche-app", version, true, version, Files.readAllBytes(openapi));
            writeJar(core, "regelsuche-core", version, false, version, new byte[0]);
            writeJar(external, null, "7", false, "7", new byte[0]);
            zipFiles.put(prefix + "regelsuche.jar", Files.readAllBytes(Path.of(stem + ".jar")));
            zipFiles.put(prefix + "lib/" + core.getFileName(), Files.readAllBytes(core));
            zipFiles.put(prefix + "lib/" + external.getFileName(), Files.readAllBytes(external));
            tarFiles.putAll(zipFiles);
            writeClasspath(List.of(core, external));
            writeArchives();
        }

        void source(String archiveName, String sourceName, String text) throws IOException {
            Path source = root.resolve(sourceName);
            Files.createDirectories(source.getParent());
            Files.writeString(source, text);
            zipFiles.put(prefix + archiveName, Files.readAllBytes(source));
        }

        void writeClasspath(List<Path> entries) throws IOException {
            Files.writeString(root.resolve("app/target/runtime-classpath.txt"), String.join(
                File.pathSeparator, entries.stream().map(Path::toString).toList()));
        }

        void writeArchives() throws IOException {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(Path.of(stem + ".zip")))) {
                for (var entry : zipFiles.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }
            ByteArrayOutputStream tar = new ByteArrayOutputStream();
            for (String dir : List.of(prefix, prefix + "bin/", prefix + "lib/")) {
                tarMember(tar, new TarMember(dir, new byte[0], '5', directoryMode));
            }
            for (var entry : tarFiles.entrySet()) {
                tarMember(tar, new TarMember(entry.getKey(), entry.getValue(), regularType,
                    entry.getKey().equals(prefix + "bin/regelsuche") ? launcherMode : regularMode));
            }
            for (TarMember extra : extras) tarMember(tar, extra);
            tar.write(new byte[1024]);
            Files.write(Path.of(stem + ".tar"), tar.toByteArray());
        }
    }

    private static void writeJar(Path path, String id, String version, boolean application,
            String manifestVersion, byte[] openapi) throws IOException {
        Files.createDirectories(path.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Implementation-Version", manifestVersion);
        if (application) manifest.getMainAttributes().putValue("Main-Class", "de.regelsuche.App");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            if (id != null) {
                jar.putNextEntry(new JarEntry("META-INF/maven/de.regelsuche/" + id + "/pom.properties"));
                jar.write(("groupId=de.regelsuche\nartifactId=" + id + "\nversion=" + version + "\n")
                    .getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
            if (application) {
                jar.putNextEntry(new JarEntry("web/openapi/openapi.json"));
                jar.write(openapi);
                jar.closeEntry();
            }
            jar.putNextEntry(new JarEntry("marker"));
            jar.write((String.valueOf(id) + version).getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private record TarMember(String name, byte[] bytes, char type, int mode) {}

    /** Tiny independent fixture writer; the normal Maven gate checks the real Maven TAR writer. */
    private static void tarMember(ByteArrayOutputStream output, TarMember entry) throws IOException {
        byte[] header = new byte[512];
        put(header, 0, entry.name());
        put(header, 100, String.format(java.util.Locale.ROOT, "%07o", entry.mode()));
        put(header, 108, "0000000");
        put(header, 116, "0000000");
        put(header, 124, String.format(java.util.Locale.ROOT, "%011o", entry.bytes().length));
        put(header, 136, "00000000000");
        Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = (byte) entry.type();
        put(header, 257, "ustar");
        put(header, 263, "00");
        long checksum = 0;
        for (byte value : header) checksum += Byte.toUnsignedInt(value);
        put(header, 148, String.format(java.util.Locale.ROOT, "%06o", checksum));
        header[154] = 0;
        header[155] = ' ';
        output.write(header);
        output.write(entry.bytes());
        output.write(new byte[(512 - entry.bytes().length % 512) % 512]);
    }

    private static void put(byte[] header, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }
}
