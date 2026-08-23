package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Guards the release policy that only completed delivery issues enter release notes. */
class MavenReleaseIssuePolicyContractTest {
    @Test
    void releaseNotesContainOnlyClosedDeliveredIssueScopes()
            throws IOException {
        Path root = MavenPomTestSupport.repositoryRoot();
        String operations = Files.readString(
            root.resolve("docs/release-operations.md"),
            StandardCharsets.UTF_8
        );
        String notes = Files.readString(
            root.resolve("docs/releases/0.3.0.md"),
            StandardCharsets.UTF_8
        );

        assertTrue(
            operations.contains(
                "Offene Issues erscheinen weder als abgeschlossen noch als teilweise"
            ),
            "release operations must reject open or partial issues as release scope"
        );
        assertTrue(
            operations.contains("Nachfolge-Issues übertragen"),
            "unfinished umbrella scope must be moved to explicit successor issues"
        );
        assertFalse(
            notes.contains("Partial progress on open umbrella issues"),
            "curated notes must not present open umbrella work as partial release scope"
        );
        assertFalse(
            notes.contains("Teilfortschritte offener Sammel-Issues"),
            "curated notes must list completed delivery issues only"
        );

        for (int issue : List.of(521, 620, 708, 718, 721)) {
            assertTrue(
                notes.contains("/issues/" + issue + ")"),
                () -> "0.3.0 notes must contain completed issue #" + issue
            );
        }
        for (int successor : List.of(220, 235, 533, 745, 746, 747)) {
            assertFalse(
                notes.contains("/issues/" + successor + ")"),
                () -> "open successor #" + successor
                    + " must remain outside the 0.3.0 issue list"
            );
        }
    }
}
