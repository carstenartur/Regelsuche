package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.HistoricalProductionSearchComparison.CaseComparison;
import de.regelsuche.benchmark.HistoricalProductionSearchComparison.ComparisonStatus;
import de.regelsuche.benchmark.HistoricalProductionSearchComparison.DeclaredBudget;
import de.regelsuche.benchmark.HistoricalProductionSearchComparison.Report;
import de.regelsuche.benchmark.HistoricalProductionSearchComparison.SearchComparisonEvidence;
import de.regelsuche.benchmark.HistoricalProductionSearchComparison.Summary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class HistoricalProductionSearchComparisonTest {

    @Test
    @Timeout(240)
    void frozenPolicyControlIsPositiveAndByteStable(@TempDir Path directory)
            throws Exception {
        HistoricalRediscoveryCorpus.Corpus full = HistoricalRediscoveryCorpus.load();
        HistoricalRediscoveryCorpus.Case selected = full.cases().stream()
            .filter(value -> value.id().equals("distribution-fitness-valley-control"))
            .findFirst().orElseThrow();
        HistoricalRediscoveryCorpus.Corpus corpus = new HistoricalRediscoveryCorpus.Corpus(
            full.schema(), full.evidenceStatus(), full.inventoryRevision(),
            full.claimBoundary(), full.contentSha256(), List.of(selected));
        HistoricalRediscoveryAtlas.AtlasReport atlas =
            new HistoricalRediscoveryAtlas().run(corpus);
        DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic pruning =
            new DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic();
        var pruningCases = pruning.run(corpus, atlas);

        HistoricalProductionSearchComparison comparison =
            new HistoricalProductionSearchComparison();
        Report report = comparison.run(corpus, atlas, pruningCases);
        assertEquals(ComparisonStatus.DIVERSITY_RECOVERS_COMPLETE_WITNESS,
            report.cases().get(0).status());
        assertTrue(report.cases().get(0).prefixDelta() > 0);
        assertTrue(report.summary().diversityReachedRelationCount() > 0);
        assertTrue(report.toCanonicalJson().matches(
            ".*\"contentHash\":\"sha256:[0-9a-f]{64}\"}\\z"));

        Path first = comparison.write(directory.resolve("first"), report);
        Path second = comparison.write(directory.resolve("second"), report);
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }

    @Test
    void valueContractsRejectUnbalancedEvidence() {
        assertThrows(IllegalArgumentException.class,
            () -> new DeclaredBudget(-1, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class,
            () -> new SearchComparisonEvidence(
                false, -1, 0, 0, 0, "STATE_BUDGET"));

        SearchComparisonEvidence scalar = new SearchComparisonEvidence(
            false, 0, 1, 1, 1, "STATE_BUDGET");
        SearchComparisonEvidence diversity = new SearchComparisonEvidence(
            true, 1, 2, 1, 1, "COMPLETED_BOUNDED_SEARCH");
        assertThrows(IllegalArgumentException.class,
            () -> new CaseComparison(
                "case", ComparisonStatus.DIVERSITY_RECOVERS_COMPLETE_WITNESS,
                1, new DeclaredBudget(1, 2, 2, 1, 1), scalar, diversity,
                0));
        assertThrows(IllegalArgumentException.class,
            () -> new Summary(2,
                Map.of(ComparisonStatus.SCALAR_ALREADY_FOUND, 1),
                0, 0, 0, 0));
    }
}
