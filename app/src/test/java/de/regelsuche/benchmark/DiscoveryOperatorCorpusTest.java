package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.ScientificDiscoveryWorkflow;
import de.regelsuche.example.SeedExpression;
import de.regelsuche.persistence.PersistenceConfig;
import de.regelsuche.validation.DiscoveryEvidenceKind;
import de.regelsuche.validation.DiscoveryResultKind;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryOperatorCorpusTest {
    @TempDir
    Path tempDir;

    @Test
    void operatorCorpusProducesReplayRowsAndDashboardFromActualWorkflowResults() {
        List<CorpusCase> corpus = List.of(
            new CorpusCase("telescoping-fraction", "1 / (n * (n + 1))",
                Expectation.REQUIRE_TRANSFORMED, Set.of(DiscoveryEvidenceKind.EQUIVALENCE_VALIDATED), "unit step"),
            new CorpusCase("telescoping-fraction", "1 / ((x + 2) * (x + 3))",
                Expectation.REQUIRE_TRANSFORMED, Set.of(DiscoveryEvidenceKind.EQUIVALENCE_VALIDATED), "compound unit step"),
            new CorpusCase("telescoping-fraction", "1 / (n * (n + 2))",
                Expectation.REQUIRE_NO_FALSE_POSITIVE, Set.of(), "non-adjacent telescoping near miss"),
            new CorpusCase("telescoping-fraction", "1 / (n * (m + 1))",
                Expectation.REQUIRE_NO_FALSE_POSITIVE, Set.of(), "mixed-symbol unsupported form"),
            new CorpusCase("rationalization", "1 / (sqrt(x) + 1)",
                Expectation.REQUIRE_TRANSFORMED, Set.of(DiscoveryEvidenceKind.EQUIVALENCE_VALIDATED), "assumption x != 1"),
            new CorpusCase("rationalization", "1 / (sqrt(x) - 1)",
                Expectation.REQUIRE_TRANSFORMED, Set.of(DiscoveryEvidenceKind.EQUIVALENCE_VALIDATED), "assumption x != 1"),
            new CorpusCase("rationalization", "1 / (sqrt(x) + sqrt(y))",
                Expectation.DOCUMENT_ONLY, Set.of(), "future two-radical form"),
            new CorpusCase("rationalization", "1 / (sqrt(x) + y)",
                Expectation.REQUIRE_NO_FALSE_POSITIVE, Set.of(), "symbolic conjugate not enabled")
        );

        List<SeedExpression> seeds = corpus.stream().map(this::toSeed).toList();
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report;
        try (ScientificDiscoveryWorkflow workflow = ScientificDiscoveryWorkflow.boot(PersistenceConfig.inMemory(), null)) {
            report = workflow.run("operator-corpus", seeds, seeds.size(), 1, tempDir.resolve("artifacts")).report();
        }
        Map<String, DeterministicDiscoveryExperimentRunner.SeedRunReport> rows = report.rows().stream()
            .collect(Collectors.toMap(row -> row.seed().expression(), Function.identity()));

        for (CorpusCase seed : corpus) {
            DeterministicDiscoveryExperimentRunner.SeedRunReport row = rows.get(seed.expression());
            if (seed.expectation() == Expectation.REQUIRE_TRANSFORMED) {
                assertEquals(DiscoveryResultKind.TRANSFORMED, row.resultKind(), row.toString());
                assertTrue(row.success(), row.toString());
                assertTrue(row.evidence().containsAll(seed.expectedEvidence()), row.toString());
                assertEquals(seed.expression(), row.replayPath().getFirst());
                if (seed.operatorId().equals("rationalization")) {
                    assertTrue(row.inferredAssumptions().contains("x != 1"), row.toString());
                }
            }
            if (seed.expectation() == Expectation.REQUIRE_NO_FALSE_POSITIVE) {
                assertFalse(row.success(), row.toString());
                assertFalse(row.resultKind() == DiscoveryResultKind.FALSE_POSITIVE, row.toString());
            }
        }

        DiscoveryBenchmarkDashboard dashboard = new DiscoveryBenchmarkDashboard();
        List<DiscoveryBenchmarkDashboard.Row> dashboardRows = dashboard.aggregate(report.rows());
        String table = dashboard.renderMarkdown(dashboardRows);

        assertTrue(table.contains("| Operator | Cases | Candidates | Bridge | Transformed | Macro learned | Macro reused | False positives | Avg time | Notes |"));
        assertTrue(table.contains("telescoping-fraction"));
        assertTrue(table.contains("rationalization"));
        assertEquals(2, dashboardRows.stream()
            .filter(row -> row.operator().equals("telescoping-fraction"))
            .findFirst().orElseThrow().transformed());
        assertEquals(2, dashboardRows.stream()
            .filter(row -> row.operator().equals("rationalization"))
            .findFirst().orElseThrow().transformed());
        assertTrue(dashboardRows.stream().allMatch(row -> row.falsePositives() == 0), report.renderDeterministicJson());
    }

    private SeedExpression toSeed(CorpusCase seed) {
        return new SeedExpression(
            seed.expression(),
            seed.expression(),
            "operator-corpus",
            "operator-corpus",
            List.of("operator:" + seed.operatorId(), "note:" + seed.notes()),
            List.of()
        );
    }

    private enum Expectation {
        REQUIRE_TRANSFORMED,
        REQUIRE_NO_FALSE_POSITIVE,
        DOCUMENT_ONLY
    }

    private record CorpusCase(
        String operatorId,
        String expression,
        Expectation expectation,
        Set<DiscoveryEvidenceKind> expectedEvidence,
        String notes
    ) {
    }
}
