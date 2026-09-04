package de.regelsuche.demo;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Protects the attention-first, evidence-backed README entry path. */
class ReadmeShowcaseDocumentationTest {
    private static final Path REPO_ROOT = locateRepoRoot();

    @Test
    void readmeLeadsWithVisualEvidenceAndExecutableExtensionExamples() throws IOException {
        String readme = Files.readString(REPO_ROOT.resolve("README.md"), StandardCharsets.UTF_8);
        int quickstart = readme.indexOf("## Schnellstart");
        assertTrue(quickstart > 0, "README must contain the supported quickstart");

        assertAppearsBeforeQuickstart(readme, quickstart, "docs/assets/ast-rule-radar.svg");
        assertAppearsBeforeQuickstart(
            readme,
            quickstart,
            "docs/generated/discovery/sophie-germain/search-space.svg"
        );
        assertAppearsBeforeQuickstart(
            readme,
            quickstart,
            "docs/generated/autonomous-discovery-walkthrough/representative-search.svg"
        );
        assertAppearsBeforeQuickstart(readme, quickstart, "docs/assets/screenshots/macro-learning-summary.png");
        assertAppearsBeforeQuickstart(readme, quickstart, "rule difference_of_squares:");
        assertAppearsBeforeQuickstart(readme, quickstart, "firstApplicable(");

        String normalized = readme.replaceAll("\\s+", " ");
        assertTrue(
            normalized.contains("keine Behauptung externer mathematischer Neuheit"),
            "The showcase must retain the external-novelty claim boundary"
        );

        for (String relative : List.of(
            "docs/assets/ast-rule-radar.svg",
            "docs/generated/discovery/sophie-germain/search-space.svg",
            "docs/generated/autonomous-discovery-walkthrough/representative-search.svg",
            "docs/assets/screenshots/macro-learning-summary.png",
            "docs/assets/screenshots/rational-summary.png",
            "docs/assets/screenshots/math-matrix-preview.png"
        )) {
            assertTrue(Files.isRegularFile(REPO_ROOT.resolve(relative)), "README showcase asset must exist: " + relative);
        }
    }

    private static void assertAppearsBeforeQuickstart(String readme, int quickstart, String needle) {
        int index = readme.indexOf(needle);
        assertTrue(index >= 0, "README showcase must contain: " + needle);
        assertTrue(index < quickstart, "README must show " + needle + " before installation details");
    }

    private static Path locateRepoRoot() {
        Path candidate = Paths.get(".").toAbsolutePath().normalize();
        for (int i = 0; i < 6; i++) {
            if (Files.exists(candidate.resolve("README.md"))
                && Files.exists(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
            Path parent = candidate.getParent();
            if (parent == null) {
                break;
            }
            candidate = parent;
        }
        throw new IllegalStateException("Could not locate repository root");
    }
}
