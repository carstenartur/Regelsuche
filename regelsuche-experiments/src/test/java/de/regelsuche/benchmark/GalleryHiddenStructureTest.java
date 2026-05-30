package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.example.SeedExpression;
import de.regelsuche.validation.CounterexampleSearchService;
import de.regelsuche.validation.DiscoveryEvidenceKind;
import de.regelsuche.validation.DiscoveryResultKind;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GalleryHiddenStructureTest {
    @Test
    void galleryEntryIsGeneratedOnlyFromReplayAndRuleData() {
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report = new DeterministicDiscoveryExperimentRunner.DiscoveryReport(
            List.of(new DeterministicDiscoveryExperimentRunner.SeedRunReport(
                new SeedExpression("hidden-sophie-germain", "x^4 + 4", "test", "hidden-structure", List.of(), List.of()),
                true,
                "hidden-structure reproduced",
                List.of("(x ^ 2 + 2) ^ 2 - (2 * x) ^ 2"),
                List.of(),
                CounterexampleSearchService.Status.INCONCLUSIVE,
                List.of(),
                List.of(),
                "",
                List.of("x^4 + 4", "(x ^ 2 + 2) ^ 2 - (2 * x) ^ 2", "(x ^ 2 + 2 - 2 * x) * (x ^ 2 + 2 + 2 * x)"),
                DiscoveryResultKind.TRANSFORMED,
                List.of("hypothesis_difference_of_squares_preparation", "ast_square_difference_factor"),
                0L,
                0L,
                Set.of(DiscoveryEvidenceKind.EQUIVALENCE_VALIDATED, DiscoveryEvidenceKind.FACTORED)
            )),
            new DeterministicDiscoveryExperimentRunner.DiscoveryMetrics(1, 1, 1, 0, 0L, 0L),
            0L
        );

        String markdown = new DiscoveryReplayArtifactWriter().renderMarkdown(report);

        assertTrue(markdown.contains("Generated Discovery Gallery"));
        assertTrue(markdown.contains("Sophie-Germain discovery replay"));
        assertTrue(markdown.contains("hypothesis_difference_of_squares_preparation"));
        assertTrue(markdown.contains("ast_square_difference_factor"));
        assertTrue(markdown.contains("```mermaid"));
        assertFalse(markdown.contains("static Mermaid"));
    }

    @Test
    void galleryUsesPerfectSquareBridgeWhenReplayContainsNoSquareDifference() {
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report = new DeterministicDiscoveryExperimentRunner.DiscoveryReport(
            List.of(new DeterministicDiscoveryExperimentRunner.SeedRunReport(
                new SeedExpression("hidden-perfect-square", "x^4 + 4", "test", "hidden-structure", List.of(), List.of()),
                true,
                "hidden-structure reproduced through complete square bridge",
                List.of("(x^2 + 2*x + 2)^2"),
                List.of(),
                CounterexampleSearchService.Status.INCONCLUSIVE,
                List.of(),
                List.of(),
                "",
                List.of("x^4 + 4", "(x^2 + 2*x + 2)^2", "(x^2 + 2*x + 2) * (x^2 + 2*x + 2)"),
                DiscoveryResultKind.TRANSFORMED,
                List.of("hypothesis_difference_of_squares_preparation", "ast_square_difference_factor"),
                0L,
                0L,
                Set.of(DiscoveryEvidenceKind.EQUIVALENCE_VALIDATED, DiscoveryEvidenceKind.FACTORED)
            )),
            new DeterministicDiscoveryExperimentRunner.DiscoveryMetrics(1, 1, 1, 0, 0L, 0L),
            0L
        );

        String markdown = new DiscoveryReplayArtifactWriter().renderMarkdown(report);

        assertTrue(markdown.contains("- discovered bridge: `(x^2 + 2*x + 2)^2`"));
    }

    @Test
    void galleryDescriptorMatchesChangedInputWhenRuleAndEvidenceQualify() {
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report = new DeterministicDiscoveryExperimentRunner.DiscoveryReport(
            List.of(new DeterministicDiscoveryExperimentRunner.SeedRunReport(
                new SeedExpression("hidden-renamed", "renamed hidden-structure seed", "test", "hidden-structure", List.of(), List.of()),
                true,
                "hidden-structure reproduced from equivalent replay evidence",
                List.of("(u ^ 2 + 2) ^ 2 - (2 * u) ^ 2"),
                List.of(),
                CounterexampleSearchService.Status.INCONCLUSIVE,
                List.of(),
                List.of(),
                "",
                List.of("renamed hidden-structure seed", "(u ^ 2 + 2) ^ 2 - (2 * u) ^ 2", "(u ^ 2 + 2 - 2 * u) * (u ^ 2 + 2 + 2 * u)"),
                DiscoveryResultKind.TRANSFORMED,
                List.of("hypothesis_difference_of_squares_preparation", "ast_square_difference_factor"),
                0L,
                0L,
                Set.of(DiscoveryEvidenceKind.EQUIVALENCE_VALIDATED, DiscoveryEvidenceKind.FACTORED)
            )),
            new DeterministicDiscoveryExperimentRunner.DiscoveryMetrics(1, 1, 1, 0, 0L, 0L),
            0L
        );

        String markdown = new DiscoveryReplayArtifactWriter().renderMarkdown(report);

        assertTrue(markdown.contains("Sophie-Germain discovery replay"));
        assertTrue(markdown.contains("input: `renamed hidden-structure seed`"));
    }
}
