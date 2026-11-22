package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

class MavenStudentSdkArchiveTest {
    @Test
    @EnabledIfSystemProperty(named = "regelsuche.studentSdk.package", matches = "true")
    void packageConfiguredRepository() throws Exception {
        StudentSdkArchive.create(MavenPomTestSupport.repositoryRoot(),
            Path.of(System.getProperty("regelsuche.studentSdk.repository")),
            System.getProperty("regelsuche.studentSdk.version"),
            Path.of(System.getProperty("regelsuche.studentSdk.output")));
    }

    @Test
    @EnabledIfSystemProperty(named = "regelsuche.studentSdk.qualify", matches = "true")
    void qualifyConfiguredArchive() throws Exception {
        Path root = MavenPomTestSupport.repositoryRoot();
        Path evidence = Path.of(System.getProperty("regelsuche.studentSdk.evidence"));
        var process = new ProcessBuilder("python3", "-B", "scripts/test_student_sdk_package.py",
            "--qualify-staged-archive", System.getProperty("regelsuche.studentSdk.repository"),
            System.getProperty("regelsuche.studentSdk.version"), System.getProperty("regelsuche.studentSdk.output"),
            evidence.toString()).directory(root.toFile()).redirectErrorStream(true)
            .redirectOutput(evidence.resolve("parity-and-consumer.log").toFile()).start();
        try {
            assertTrue(process.waitFor(Duration.ofMinutes(25).toSeconds(), TimeUnit.SECONDS), "Student SDK qualification timed out");
            assertEquals(0, process.exitValue(), "Student SDK packaging parity or isolated consumer failed");
            for (String name : List.of("parity.json", "consumer.log", "consumer-report.json")) {
                Path receipt = evidence.resolve(name);
                assertTrue(Files.isRegularFile(receipt) && Files.size(receipt) > 0, "missing or empty " + name);
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test void stagesExactArtifactBytesAndReproducibleZipMetadata(@TempDir Path temp) throws Exception {
        Path root = MavenPomTestSupport.repositoryRoot();
        String version = "0.5.4-SNAPSHOT";
        Path repository = fixture(root, temp, version);
        Path first = temp.resolve("one.zip");
        Path second = temp.resolve("two.zip");
        StudentSdkArchive.create(root, repository, version, first);
        StudentSdkArchive.create(root, repository, version, second);
        assertEquals(-1L, Files.mismatch(first, second));
        assertThrows(IOException.class, () -> StudentSdkArchive.create(root, repository, version, first));
        String prefix = "regelsuche-sdk-" + version + "/";
        String coordinate = "io/github/carstenartur/regelsuche-core/" + version + "/regelsuche-core-" + version + ".jar";
        try (var zip = ZipFile.builder().setPath(first).get()) {
            var entries = zip.getEntriesInPhysicalOrder();
            var names = new ArrayList<String>();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                names.add(entry.getName());
                assertEquals(LocalDateTime.of(1980, 1, 1, 0, 0), entry.getTimeLocal(), entry.getName());
                assertEquals(ZipEntry.DEFLATED, entry.getMethod(), entry.getName());
                assertEquals(entry.getName().equals(prefix + "gradlew") ? 0100755 : 0100644, entry.getUnixMode(), entry.getName());
            }
            assertEquals(names.stream().sorted().toList(), names);
            var member = zip.getEntry(prefix + "repository/" + coordinate);
            assertArrayEquals(Files.readAllBytes(repository.resolve(coordinate)), zip.getInputStream(member).readAllBytes());
            var mapping = StudentSdkArchive.JSON.readTree(zip.getInputStream(zip.getEntry(prefix + "sdk-compatibility.json")));
            assertEquals(version, mapping.required("productVersion").textValue());
            assertEquals(StudentSdkArchive.sha256(Files.readAllBytes(repository.resolve(coordinate))),
                mapping.required("artifacts").required("repository/" + coordinate).required("sha256").textValue());
            assertNotNull(zip.getEntry(prefix + "javadoc/regelsuche-core/index.html"));
            assertNotNull(zip.getEntry(prefix + "examples/external-consumers/number-theory-plan-java25/SDK-ADAPTATION.json"));
        }
    }

    @Test void refusesVersionDriftOrAbsentSourcesBeforeWriting(@TempDir Path temp) throws Exception {
        Path root = MavenPomTestSupport.repositoryRoot();
        String version = "0.5.4";
        Path repository = fixture(root, temp, version);
        Path path = repository.resolve("io/github/carstenartur/regelsuche-core/" + version);
        Path output = temp.resolve("invalid.zip");
        Files.writeString(path.resolve("regelsuche-core-" + version + ".pom"), pom("0.5.5", false));
        var drift = assertThrows(IOException.class, () -> StudentSdkArchive.create(root, repository, version, output));
        assertTrue(drift.getMessage().contains("POM version"));
        Files.writeString(path.resolve("regelsuche-core-" + version + ".pom"), pom(version, true));
        Files.delete(path.resolve("regelsuche-core-" + version + "-sources.jar"));
        var source = assertThrows(IOException.class, () -> StudentSdkArchive.create(root, repository, version, output));
        assertTrue(source.getMessage().contains("sources.jar"));
        assertFalse(Files.exists(output));
    }

    @Test void rejectsVersionsOutsideTheMavenNamespace(@TempDir Path temp) throws Exception {
        Path root = MavenPomTestSupport.repositoryRoot();
        String version = "0.5.4";
        Path repository = fixture(root, temp, version);
        Path module = repository.resolve("io/github/carstenartur/regelsuche-core/" + version
            + "/regelsuche-core-" + version + ".pom");
        for (String invalid : List.of("<project><version>0.5.4</version></project>",
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><parent xmlns=\"urn:foreign\">"
                    + "<version>0.5.4</version></parent></project>")) {
            Files.writeString(module, invalid);
            var failure = assertThrows(IOException.class,
                () -> StudentSdkArchive.create(root, repository, version, temp.resolve("invalid.zip")));
            assertTrue(failure.getMessage().contains("POM version"));
        }
        Files.writeString(module, pom(version, true));
        Path parent = repository.resolve("io/github/carstenartur/regelsuche-parent/" + version
            + "/regelsuche-parent-" + version + ".pom");
        Files.writeString(parent, "<project><version>0.5.4</version></project>");
        var failure = assertThrows(IOException.class,
            () -> StudentSdkArchive.create(root, repository, version, temp.resolve("invalid.zip")));
        assertTrue(failure.getMessage().contains("parent POM version"));
    }

    @Test void rejectsDirectoryIndex(@TempDir Path temp) throws Exception {
        Path root = MavenPomTestSupport.repositoryRoot();
        String version = "0.5.4";
        Path repository = fixture(root, temp, version);
        Path docs = repository.resolve("io/github/carstenartur/regelsuche-core/" + version
            + "/regelsuche-core-" + version + "-javadoc.jar");
        try (var jar = new ZipOutputStream(Files.newOutputStream(docs))) {
            jar.putNextEntry(new ZipEntry("index.html/"));
        }
        Path output = temp.resolve("invalid.zip");
        var directory = assertThrows(IOException.class, () -> StudentSdkArchive.create(root, repository, version, output));
        assertTrue(directory.getMessage().contains("index.html"));
        assertFalse(Files.exists(output));
    }

    @Test void rejectsCorruptJavadocCrc(@TempDir Path temp) throws Exception {
        Path root = MavenPomTestSupport.repositoryRoot();
        String version = "0.5.4";
        Path repository = fixture(root, temp, version);
        Path docs = repository.resolve("io/github/carstenartur/regelsuche-core/" + version
            + "/regelsuche-core-" + version + "-javadoc.jar");
        byte[] valid = Files.readAllBytes(docs);
        Path output = temp.resolve("invalid.zip");
        int centralHeader = 0;
        while (centralHeader < valid.length - 20 && !(valid[centralHeader] == 'P' && valid[centralHeader + 1] == 'K'
                && valid[centralHeader + 2] == 1 && valid[centralHeader + 3] == 2)) centralHeader++;
        assertTrue(centralHeader < valid.length - 20);
        valid[centralHeader + 16] ^= 1; // Central directory CRC32, leaving the stream bytes intact.
        Files.write(docs, valid);
        var crc = assertThrows(IOException.class, () -> StudentSdkArchive.create(root, repository, version, output));
        assertTrue(crc.getMessage().contains("CRC"));
        assertFalse(Files.exists(output));
    }

    @Test void rejectsAlteredOrExtraIndependentConsumerSources(@TempDir Path temp) throws Exception {
        Path source = MavenPomTestSupport.repositoryRoot().resolve("examples/external-consumers/number-theory-plan-java25");
        for (Path file : StudentSdkArchive.regularFiles(source, Set.of("build", "target", ".gradle"))) {
            Path copy = temp.resolve(source.relativize(file));
            Files.createDirectories(copy.getParent());
            Files.copy(file, copy);
        }
        assertNotNull(StudentSdkPinnedConsumer.files(temp).get("SDK-ADAPTATION.json"));
        Path extra = Files.writeString(temp.resolve("Unexpected.java"), "class Unexpected {}");
        var inventory = assertThrows(IOException.class, () -> StudentSdkPinnedConsumer.files(temp));
        assertTrue(inventory.getMessage().contains("source set"));
        Files.delete(extra);
        Files.writeString(temp.resolve("build.gradle"), "changed");
        var modified = assertThrows(IOException.class, () -> StudentSdkPinnedConsumer.files(temp));
        assertTrue(modified.getMessage().contains("pinned Git blob"));
    }

    private static Path fixture(Path root, Path temp, String version) throws Exception {
        Path repository = temp.resolve("staged");
        var policy = StudentSdkArchive.JSON.readTree(root.resolve("config/sdk/public-api.json").toFile());
        var modules = new ArrayList<>(List.of("regelsuche-parent", "regelsuche-bom"));
        policy.required("modules").forEach(module -> modules.add(module.textValue()));
        for (String module : modules) {
            Path directory = Files.createDirectories(repository.resolve("io/github/carstenartur").resolve(module).resolve(version));
            Files.writeString(directory.resolve(module + "-" + version + ".pom"), pom(version, !module.equals("regelsuche-parent")));
            if (module.equals("regelsuche-parent") || module.equals("regelsuche-bom")) continue;
            for (String suffix : List.of(".jar", "-sources.jar", "-javadoc.jar")) {
                try (var jar = new ZipOutputStream(Files.newOutputStream(directory.resolve(module + "-" + version + suffix)))) {
                    jar.putNextEntry(new ZipEntry(suffix.equals("-javadoc.jar") ? "index.html" : "fixture"));
                    jar.write(StudentSdkArchive.utf8(module + suffix));
                }
            }
        }
        return repository;
    }

    private static String pom(String version, boolean inherited) {
        String coordinate = "<version>" + version + "</version>";
        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
            + (inherited ? "<parent>" + coordinate + "</parent>" : coordinate) + "</project>";
    }
}
