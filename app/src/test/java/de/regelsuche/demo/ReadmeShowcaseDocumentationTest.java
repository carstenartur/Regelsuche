package de.regelsuche.demo;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Protects the workflow-first, evidence-backed README entry path. */
class ReadmeShowcaseDocumentationTest {
    private static final Path REPO_ROOT = locateRepoRoot();

    @Test
    void readmeLeadsWithWorkflowAndKeepsEvidenceBackedExtensionExamples() throws IOException {
        String readme = Files.readString(REPO_ROOT.resolve("README.md"), StandardCharsets.UTF_8);
        int quickstart = readme.indexOf("## Schnellstart");
        assertTrue(quickstart > 0, "README must contain the supported quickstart");
        int workflow = readme.indexOf("## Erster Ablauf: einen Rechenweg verstehen");
        int research = readme.indexOf("## Lernen muss sich auf unbekannten Fällen auszahlen");
        int extensions = readme.indexOf("## In wenigen Zeilen erweitern");
        assertTrue(workflow > quickstart,
            "README must lead from installation into the first user workflow");
        assertTrue(research > workflow && extensions > workflow,
            "README must present the first workflow before research and extension details");

        String firstWorkflow = readme.substring(workflow, readme.indexOf("\n## ", workflow + 1));
        for (String step : List.of("(x+3)^2", "**Pfade**", "**Replay**", "**Exporte**", "Markdown", "LaTeX")) {
            assertTrue(firstWorkflow.contains(step),
                "README first workflow must include: " + step);
        }

        assertContains(readme, "docs/assets/ast-rule-radar.svg");
        assertContains(
            readme,
            "docs/generated/discovery/sophie-germain/search-space.svg"
        );
        assertContains(
            readme,
            "docs/generated/autonomous-discovery-walkthrough-shared-core-v3/representative-search.svg"
        );
        assertContains(
            readme,
            "docs/assets/screenshots/macro-learning-summary.png"
        );
        assertContains(readme, "rule difference_of_squares:");
        assertContains(readme, "firstApplicable(");
        assertContains(readme, "docs/java-discovery-sdk.md");
        assertContains(
            readme, "GeometricSequenceDomainProvider.typedDomain()"
        );
        assertContains(readme, "var input = new Input(");
        assertContains(readme, ".seed(\"powers-of-two\", input,");
        assertContains(
            readme, "assertThat(run).isConfirmed().hasContentAddressedEvidence()"
        );

        String normalized = readme.replaceAll("\\s+", " ");
        assertTrue(
            normalized.contains("keine Behauptung externer mathematischer Neuheit"),
            "The showcase must retain the external-novelty claim boundary"
        );
        assertTrue(
            normalized.contains("CONFIRMED` gilt für diesen endlichen Prüfbereich"),
            "The SDK example must not present finite sequence checks as a general proof"
        );

        for (String relative : List.of(
            "docs/assets/ast-rule-radar.svg",
            "docs/generated/discovery/sophie-germain/search-space.svg",
            "docs/generated/autonomous-discovery-walkthrough-shared-core-v3/representative-search.svg",
            "docs/generated/autonomous-discovery-walkthrough-exact-v2/representative-search.svg",
            "docs/assets/screenshots/macro-learning-summary.png",
            "docs/assets/screenshots/rational-summary.png",
            "docs/assets/screenshots/math-matrix-preview.png",
            "docs/java-discovery-sdk.md",
            "examples/external-consumers/geometric-sequence-domain-java25/README.md"
        )) {
            assertTrue(
                Files.isRegularFile(REPO_ROOT.resolve(relative)),
                "README showcase asset must exist: " + relative
            );
        }
    }

    private static void assertContains(String readme, String needle) {
        assertTrue(readme.contains(needle), "README showcase must contain: " + needle);
    }

    private static Path locateRepoRoot() {
        Path start = Paths.get(".").toAbsolutePath().normalize();
        Path candidate = start;
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
        throw new IllegalStateException(
            "Could not locate repository root from " + start
        );
    }
}
