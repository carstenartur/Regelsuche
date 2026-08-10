package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.PrimaryStatus;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Corpus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Guards fail-closed capability-gap classification for bounded closures. */
class HistoricalRediscoveryClaimBoundaryTest {

    @Test
    @Timeout(240)
    void curatedHitDoesNotBecomeCapabilityGapBeforeProductionClosureCompletes() {
        Corpus full = HistoricalRediscoveryCorpus.load();
        HistoricalRediscoveryCorpus.Case benchmarkCase = full.cases().stream()
            .filter(value -> value.id().equals("regrouped-square"))
            .findFirst()
            .orElseThrow();
        Corpus subset = new Corpus(
            full.schema(),
            full.evidenceStatus(),
            full.inventoryRevision(),
            full.claimBoundary(),
            full.contentSha256(),
            List.of(benchmarkCase));

        HistoricalRediscoveryAtlas.CaseResult result =
            new HistoricalRediscoveryAtlas().run(subset).cases().get(0);

        assertTrue(result.production().oracle().inconclusive());
        assertTrue(result.curatedControl().oracle().reachable());
        assertEquals(PrimaryStatus.BUDGET_INCONCLUSIVE, result.status());
    }
}
