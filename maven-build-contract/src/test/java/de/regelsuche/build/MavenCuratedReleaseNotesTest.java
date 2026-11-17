package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Maven/JUnit authority for the curated release-notes check.
 * Verifies only checkout-owned bytes; it never publishes or mutates metadata.
 */
class MavenCuratedReleaseNotesTest {
    private static final String VERSION_PROPERTY =
        "regelsuche.expectedCuratedReleaseNotesVersion";
    private static final String TEST_CLASS_PATH =
        "maven-build-contract/src/test/java/de/regelsuche/build/MavenCuratedReleaseNotesTest.java";

    @Test
    void requestedReleaseNotesAreCuratedAndVersionBound() throws IOException {
        String version = System.getProperty(VERSION_PROPERTY);
        if (version != null) {
            verify(MavenPomTestSupport.repositoryRoot(), version);
        }
    }

    @Test
    void acceptsExactCuratedVersionAndWindowsLineEndings(@TempDir Path root)
            throws IOException {
        Path notes = fixture(root, "1.2.3", "# Regelsuche 1.2.3\r\n\r\nNotes.\r\n");
        assertDoesNotThrow(() -> verify(root, "1.2.3"));
        Files.writeString(notes, "# Regelsuche 1.2.3\n\nNotes.\n", StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> verify(root, "1.2.3"));
    }

    @Test
    void rejectsMissingEmptyWrongOrUnboundNotes(@TempDir Path root) throws IOException {
        assertThrows(IllegalArgumentException.class, () -> verify(root, "1.2.3"));
        Path notes = fixture(root, "1.2.3", "");
        assertThrows(IllegalArgumentException.class, () -> verify(root, "1.2.3"));
        Files.writeString(notes, "# Regelsuche 1.2.4\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> verify(root, "1.2.3"));
        Files.writeString(notes, " \n# Regelsuche 1.2.3\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> verify(root, "1.2.3"));
        Files.writeString(notes, "# Regelsuche 1.2.3\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> verify(root, "1.2.3-SNAPSHOT"));
        assertThrows(IllegalArgumentException.class, () -> verify(root, "../1.2.3"));
    }

    @Test
    void rejectsSymlinkedReleaseNotes(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("docs/releases"));
        Path elsewhere = Files.writeString(root.resolve("external.md"), "# Regelsuche 1.2.3\n");
        Path link = root.resolve("docs/releases/1.2.3.md");
        try {
            Files.createSymbolicLink(link, elsewhere);
        } catch (UnsupportedOperationException | SecurityException | IOException unsupported) {
            return; // Some non-Linux local test environments prohibit creating symlinks.
        }
        assertThrows(IllegalArgumentException.class, () -> verify(root, "1.2.3"));
    }

    @Test
    void activeReleaseSelectsMavenWhileHistoricTagsKeepLegacyCheck() throws IOException {
        String workflow = Files.readString(MavenPomTestSupport.repositoryRoot()
            .resolve(".github/workflows/release.yml"), StandardCharsets.UTF_8);
        String heading = "      - name: Validate curated release notes\n";
        int start = workflow.indexOf(heading);
        int end = workflow.indexOf("\n      - name: ", start + heading.length());
        assertTrue(start >= 0 && end > start, "release-notes verification step missing");
        String step = workflow.substring(start, end);
        assertTrue(step.contains("if [[ -f " + TEST_CLASS_PATH + " ]]; then"),
            "current checkout must select the JUnit verifier");
        assertTrue(step.contains("mvn --batch-mode --no-transfer-progress")
                && step.contains("-Dtest=MavenCuratedReleaseNotesTest")
                && step.contains("-D" + VERSION_PROPERTY + "=\"$VERSION\""),
            "active release must check its exact curated version through Maven/JUnit");
        assertTrue(step.contains("EXISTING_TAG: ${{ steps.release-state.outputs.tag_exists }}")
                && step.contains("if [[ \"$EXISTING_TAG\" != 'true' ]]; then")
                && step.contains("else\n")
                && step.contains("NOTES=\"docs/releases/${VERSION}.md\""),
            "missing Java authority must fail for new releases; only existing tags may fall back");
        assertTrue(step.indexOf("mvn --batch-mode") < step.indexOf("else\n"),
            "Maven verification must be the primary authority");
    }

    static void verify(Path repository, String version) throws IOException {
        require(version != null && version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"),
            "curated notes require an exact release version");
        Path root = repository.toAbsolutePath().normalize();
        Path file = root.resolve("docs/releases/" + version + ".md");
        for (Path current = file; current != null && current.startsWith(root);
                current = current.getParent()) {
            require(!Files.isSymbolicLink(current), "symlink in curated notes path: " + current);
        }
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS),
            "missing curated release notes: " + file);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        require(!lines.isEmpty() && ("# Regelsuche " + version).equals(lines.get(0)),
            "curated release notes must begin with the exact version heading: " + file);
        System.out.println("curatedReleaseNotes=VERIFIED version=" + version);
    }

    private static Path fixture(Path root, String version, String contents) throws IOException {
        Path notes = root.resolve("docs/releases/" + version + ".md");
        Files.createDirectories(notes.getParent());
        return Files.writeString(notes, contents, StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
