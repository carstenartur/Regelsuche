package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MavenReleaseMetadataTransitionTest {
    private static final String OPENAPI = "app/src/main/resources/web/openapi/openapi.json";

    @Test void releaseAndNextSnapshotUpdateOnlyTheCompleteDeclaredReactor(@TempDir Path root) throws Exception {
        fixture(root);
        var day = LocalDate.parse("2026-11-18");
        var before = Files.readString(root.resolve("unregistered/pom.xml"));
        ReleaseMetadataTransition.write(root, "9.8.7", day);
        MavenReleaseMetadataAlignmentTest.verify(root, "9.8.7");
        assertEquals("{\n  \"title\": \"Étude\",\n  \"version\": \"9.8.7\",\n  \"publication_date\": \"2026-11-18\"\n}\n",
            Files.readString(root.resolve(".zenodo.json")));
        assertTrue(Files.readString(root.resolve("CITATION.md")).contains("date         = {2026-11-18},"));
        assertEquals(before, Files.readString(root.resolve("unregistered/pom.xml")));
        ReleaseMetadataTransition.write(root, "9.9.0-SNAPSHOT", null);
        MavenReleaseMetadataAlignmentTest.verify(root, "9.9.0-SNAPSHOT");
        assertFalse(Files.readString(root.resolve("CITATION.md")).contains("date         ="));
        assertEquals("{\n  \"title\": \"Étude\",\n  \"version\": \"9.9.0-SNAPSHOT\"\n}\n", Files.readString(root.resolve(".zenodo.json")));
        assertEquals(before, Files.readString(root.resolve("unregistered/pom.xml")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void releaseAndSnapshotNormalizeCrLfInAllManagedInputs(boolean snapshot, @TempDir Path root) throws Exception {
        Path lf = Files.createDirectory(root.resolve("lf"));
        Path crlf = Files.createDirectory(root.resolve("crlf"));
        fixture(lf);
        fixture(crlf);
        var day = LocalDate.parse("2026-11-18");
        if (snapshot) {
            ReleaseMetadataTransition.write(lf, "9.8.7", day);
            ReleaseMetadataTransition.write(crlf, "9.8.7", day);
        }
        String current = snapshot ? "9.8.7" : "9.8.7-SNAPSHOT";
        var managed = new ArrayList<>(List.of("release.properties", "CITATION.cff", "CITATION.md", OPENAPI,
            ".zenodo.json", "codemeta.json"));
        MavenReleaseMetadataAlignmentTest.verify(crlf, current).forEach(p -> managed.add(crlf.relativize(p).toString()));
        for (String name : managed) {
            Path path = crlf.resolve(name);
            Files.writeString(path, Files.readString(path).replace("\n", "\r\n"));
        }
        String version = snapshot ? "9.9.0-SNAPSHOT" : "9.8.7";
        ReleaseMetadataTransition.write(lf, version, snapshot ? null : day);
        ReleaseMetadataTransition.write(crlf, version, snapshot ? null : day);
        for (String name : managed) assertArrayEquals(Files.readAllBytes(lf.resolve(name)), Files.readAllBytes(crlf.resolve(name)), name);
    }

    @Test void badPublicationInputLeavesAllEarlierSourcesUntouched(@TempDir Path root) throws Exception {
        fixture(root);
        Path child = root.resolve("profile/nested/pom.xml");
        String original = Files.readString(child);
        String citation = Files.readString(root.resolve("CITATION.md"));
        Files.writeString(child, original.replace("9.8.7-SNAPSHOT", "0.0.1"));
        assertThrows(Exception.class, () -> ReleaseMetadataTransition.write(root, "9.8.7", LocalDate.now()));
        assertEquals(citation, Files.readString(root.resolve("CITATION.md")));
        assertEquals("version=9.8.7-SNAPSHOT\n", Files.readString(root.resolve("release.properties")));
    }

    @Test void rejectsMalformedEscapingAndAliasedReactorMembersBeforePublication(@TempDir Path root) throws Exception {
        fixture(root);
        Path top = root.resolve("pom.xml");
        Path profile = root.resolve("profile/pom.xml");
        String original = Files.readString(top);
        String child = Files.readString(profile);
        for (String name : List.of("", "../outside", root.toAbsolutePath().toString(), "a/../profile", "${module}", ".", "missing")) {
            Files.writeString(top, original.replace("<module>profile</module>", "<module>" + name + "</module>"));
            assertThrows(Exception.class, () -> ReleaseMetadataTransition.write(root, "9.8.7", LocalDate.now()), name);
        }
        Files.writeString(top, original);
        for (String change : List.of("<project>", child.replace("http://maven.apache.org/POM/4.0.0", "urn:other"),
                "<!DOCTYPE project>" + child, child.replace("regelsuche-parent", "unknown-parent"))) {
            Files.writeString(profile, change);
            assertThrows(Exception.class, () -> ReleaseMetadataTransition.write(root, "9.8.7", LocalDate.now()));
        }
        Files.writeString(profile, child);
        Files.createSymbolicLink(root.resolve("alias"), root.resolve("profile"));
        Files.writeString(top, original.replace("<module>profile</module>", "<module>alias</module>"));
        assertThrows(Exception.class, () -> ReleaseMetadataTransition.write(root, "9.8.7", LocalDate.now()));
        Files.writeString(top, original);
        Path moved = root.resolve("profile/other.xml");
        Files.move(profile, moved);
        Files.createSymbolicLink(profile, moved);
        assertThrows(Exception.class, () -> ReleaseMetadataTransition.write(root, "9.8.7", LocalDate.now()));
        assertEquals("version=9.8.7-SNAPSHOT\n", Files.readString(root.resolve("release.properties")));
    }

    @Test @EnabledIfSystemProperty(named = "regelsuche.releaseMetadata.operation", matches = "release|follow-up")
    void executeRequestedTransition() throws Exception {
        Path root = Path.of(System.getProperty("regelsuche.releaseMetadata.root", MavenPomTestSupport.repositoryRoot().toString()));
        String version = System.getProperty("regelsuche.releaseMetadata.version");
        if ("release".equals(System.getProperty("regelsuche.releaseMetadata.operation"))) {
            ReleaseMetadataTransition.write(root, version, LocalDate.now(java.time.ZoneOffset.UTC));
        } else {
            boolean required = ReleaseFollowUp.prepare(root, System.getProperty("regelsuche.releaseMetadata.predecessor"), version);
            Files.writeString(Path.of(System.getenv("GITHUB_OUTPUT")), "required=" + required + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }
    }

    static void fixture(Path root) throws Exception {
        String old = "9.8.7-SNAPSHOT";
        Path source = MavenPomTestSupport.repositoryRoot();
        for (String name : List.of("CITATION.cff", "CITATION.md", OPENAPI)) {
            Path out = root.resolve(name);
            Files.createDirectories(out.getParent());
            Files.writeString(out, Files.readString(source.resolve(name))
                .replace(MavenReleaseMetadataAlignmentTest.oneVersion(Files.readString(source.resolve("release.properties"))), old));
        }
        for (String name : List.of("codemeta.json", ".zenodo.json"))
            Files.writeString(root.resolve(name), "{\"title\":\"Étude\",\"version\":\"" + old + "\"}");
        Files.writeString(root.resolve("release.properties"), "version=" + old + "\n");
        String project = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>";
        String coordinates = "<groupId>de.regelsuche</groupId><artifactId>regelsuche-parent</artifactId><version>" + old + "</version>";
        String parent = "<parent>" + coordinates + "</parent><artifactId>child</artifactId>";
        Files.writeString(root.resolve("pom.xml"), project + coordinates
            + "<profiles><profile><id>full</id><modules><module>profile</module></modules></profile></profiles></project>");
        Files.createDirectories(root.resolve("profile/nested"));
        Files.writeString(root.resolve("profile/pom.xml"), project + parent + "<modules><module>nested</module></modules></project>");
        Files.writeString(root.resolve("profile/nested/pom.xml"), project + parent + "</project>");
        Files.createDirectories(root.resolve("unregistered"));
        Files.writeString(root.resolve("unregistered/pom.xml"), "unrelated build");
    }
}
