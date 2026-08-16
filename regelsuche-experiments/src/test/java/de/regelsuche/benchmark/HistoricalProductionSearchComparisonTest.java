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
import de.regelsuche.benchmark.HistoricalProductionSearchComparison.SearchPolicy;
import de.regelsuche.benchmark.HistoricalProductionSearchComparison.Summary;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class HistoricalProductionSearchComparisonTest {

    @Test
    @Timeout(360)
    void checkoutRunnerWritesBalancedMatchedBudgetComparison(
            @TempDir Path directory) throws Exception {
        DiscoveryExperimentRunner.main(new String[] {
            "historical-rediscovery",
            directory.resolve("historical-rediscovery").toString()
        });

        Path comparison = directory.resolve(
            "historical-rediscovery-production-search-comparison")
            .resolve(HistoricalProductionSearchComparison.FILE_NAME);
        assertTrue(Files.isRegularFile(comparison));
        String json = Files.readString(comparison, StandardCharsets.UTF_8);
        assertTrue(json.startsWith(
            "{\"schema\":\"regelsuche.production-search-comparison/v1\""));
        assertTrue(json.contains(
            "\"informationBoundary\":\"TARGET_BLIND_SEARCHES_ORACLE_POST_HOC_DIAGNOSTIC\""));
        assertTrue(json.contains(
            "\"status\":\"DIVERSITY_RECOVERS_COMPLETE_WITNESS\""));
        assertTrue(json.matches(".*\"contentHash\":\"sha256:[0-9a-f]{64}\"}\\z"));
    }

    @Test
    @Timeout(240)
    void directComparisonIsByteStableForFrozenPolicyControl(
            @TempDir Path directory) throws Exception {
        HistoricalRediscoveryCorpus.Corpus full =
            HistoricalRediscoveryCorpus.load();
        HistoricalRediscoveryCorpus.Case selected = full.cases().stream()
            .filter(value -> value.id().equals(
                "distribution-fitness-valley-control"))
            .findFirst()
            .orElseThrow();
        HistoricalRediscoveryCorpus.Corpus corpus =
            new HistoricalRediscoveryCorpus.Corpus(
                full.schema(),
                full.evidenceStatus(),
                full.inventoryRevision(),
                full.claimBoundary(),
                full.contentSha256(),
                List.of(selected));
        HistoricalRediscoveryAtlas.AtlasReport atlas =
            new HistoricalRediscoveryAtlas().run(corpus);
        DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic pruning =
            new DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic();
        List<DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic
            .CaseDiagnostic> pruningCases = pruning.run(corpus, atlas);

        HistoricalProductionSearchComparison comparison =
            new HistoricalProductionSearchComparison();
        Report report = comparison.run(corpus, atlas, pruningCases);
        assertEquals(1, report.cases().size());
        assertEquals(
            ComparisonStatus.DIVERSITY_RECOVERS_COMPLETE_WITNESS,
            report.cases().get(0).status());
        assertTrue(report.cases().get(0).prefixDelta() > 0);
        assertTrue(report.summary().diversityReachedRelationCount() > 0);

        Path first = comparison.write(directory.resolve("first"), report);
        Path second = comparison.write(directory.resolve("second"), report);
        assertArrayEquals(
            Files.readAllBytes(first),
            Files.readAllBytes(second));
    }

    @Test
    void valueContractsRejectUnbalancedEvidence() {
        assertThrows(IllegalArgumentException.class,
            () -> new DeclaredBudget(-1, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class,
            () -> new SearchComparisonEvidence(
                SearchPolicy.SCALAR_BEST_FIRST_TARGET_BLIND,
                false,
                -1,
                0,
                0,
                0,
                "STATE_BUDGET"));

        SearchComparisonEvidence scalar = new SearchComparisonEvidence(
            SearchPolicy.SCALAR_BEST_FIRST_TARGET_BLIND,
            false,
            0,
            1,
            1,
            1,
            "STATE_BUDGET");
        SearchComparisonEvidence diversity = new SearchComparisonEvidence(
            SearchPolicy.STRUCTURAL_DIVERSITY_TARGET_BLIND,
            true,
            1,
            2,
            1,
            1,
            "COMPLETED_BOUNDED_SEARCH");
        assertThrows(IllegalArgumentException.class,
            () -> new CaseComparison(
                "case",
                ComparisonStatus.DIVERSITY_RECOVERS_COMPLETE_WITNESS,
                1,
                new DeclaredBudget(1, 2, 2, 1, 1),
                scalar,
                diversity,
                0,
                "inconsistent delta"));
        assertThrows(IllegalArgumentException.class,
            () -> new Summary(
                2,
                Map.of(ComparisonStatus.SCALAR_ALREADY_FOUND, 1),
                0,
                0,
                0,
                0));
    }
}
