package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.HistoricalProductionSearchComparison.ComparisonStatus;
import de.regelsuche.benchmark.HistoricalProductionSearchComparison.Report;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}
