package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import de.regelsuche.polynomial.PolynomialWorkLedger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityRawWorkPartitionerTest {
    @Test
    void partitionsAllKnownStagesAndChargesUnknownWorkAsFactorization() {
        Map<String, Long> stages = new LinkedHashMap<>();
        stages.put("nested.position-preflight-path-navigation", 1L);
        stages.put("exact-parsed-view.node-visits", 2L);
        stages.put("native.factorization.step", 3L);
        stages.put("verify.product-comparisons", 1L);
        stages.put("render.factor-count", 1L);
        stages.put("transform.exact-reparse-source-code-units", 2L);
        stages.put("transform.structural-change-comparison", 1L);
        stages.put("nested.replacement-node-visits", 2L);
        stages.put("cache.lookup.entry", 1L);
        stages.put("cache.insertion.entry", 1L);
        stages.put("cache.eviction.entry", 1L);
        stages.put("cache.replay.entry", 1L);
        stages.put("study.evidence.payload-utf8-bytes", 64L);
        var ledger = new PolynomialWorkLedger(stages);

        var raw = PolynomialTheoryUtilityRawWorkPartitioner.partition(
            7L,
            ledger
        );
        var projection = PolynomialTheoryUtilityRawWorkPartitioner.project(
            input(),
            7L,
            ledger
        );

        assertEquals(ledger, raw.totalMechanicalWork());
        assertEquals(1L, raw.matchingWork().totalWorkUnits());
        assertEquals(2L, raw.sourceValidationWork().totalWorkUnits());
        assertEquals(3L, raw.factorizationWork().totalWorkUnits());
        assertEquals(1L, raw.verificationWork().totalWorkUnits());
        assertEquals(1L, raw.renderingWork().totalWorkUnits());
        assertEquals(2L, raw.reparseWork().totalWorkUnits());
        assertEquals(1L, raw.reconstructionWork().totalWorkUnits());
        assertEquals(2L, raw.occurrenceReplacementWork().totalWorkUnits());
        assertEquals(1L, raw.cacheLookupWork().totalWorkUnits());
        assertEquals(1L, raw.cacheInsertionWork().totalWorkUnits());
        assertEquals(1L, raw.cacheEvictionWork().totalWorkUnits());
        assertEquals(1L, raw.cacheReplayWork().totalWorkUnits());
        assertEquals(64L, raw.evidenceConstructionWork().totalWorkUnits());

        var work = projection.work();
        assertEquals(7L, work.primitiveWork());
        assertEquals(1L, work.matchingWork());
        assertEquals(2L, work.sourceValidationWork());
        assertEquals(3L, work.factorizationWork());
        assertEquals(1L, work.verificationWork());
        assertEquals(1L, work.renderingWork());
        assertEquals(2L, work.reparseWork());
        assertEquals(1L, work.reconstructionWork());
        assertEquals(2L, work.occurrenceReplacementWork());
        assertEquals(1L, work.cacheLookupWork());
        assertEquals(1L, work.cacheInsertionWork());
        assertEquals(1L, work.cacheEvictionWork());
        assertEquals(1L, work.cacheReplayWork());
        assertEquals(1L, work.evidenceConstructionWork());
    }

    @Test
    void preservesContentIdentityAndChangesItWithRawStageOwnership() {
        var input = input();
        var first = PolynomialTheoryUtilityRawWorkPartitioner.project(
            input,
            1L,
            ledger("native.step", 1L)
        );
        var repeated = PolynomialTheoryUtilityRawWorkPartitioner.project(
            input,
            1L,
            ledger("native.step", 1L)
        );
        var changed = PolynomialTheoryUtilityRawWorkPartitioner.project(
            input,
            1L,
            ledger("verify.product-comparisons", 1L)
        );

        assertEquals(first, repeated);
        assertNotEquals(first.projectionId(), changed.projectionId());
    }

    private static PolynomialWorkLedger ledger(String stage, long units) {
        return new PolynomialWorkLedger(Map.of(stage, units));
    }

    private static PolynomialTheoryUtilityExecutionInput input() {
        return PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(value -> "ON_DEMAND_VERIFIED_FACTORIZATION".equals(
                value.profileId()
            ))
            .filter(value -> "z02-difference-of-squares".equals(
                value.caseId()
            ))
            .filter(value -> "CP06_FULL".equals(value.checkpointId()))
            .findFirst()
            .orElseThrow();
    }
}
