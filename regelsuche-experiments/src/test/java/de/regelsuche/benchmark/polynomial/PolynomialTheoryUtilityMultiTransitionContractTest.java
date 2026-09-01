package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityTransitionOutcome.CacheDisposition;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityMultiTransitionContractTest {
    private static final String CASE_ID = "two-identical-occurrences";
    private static final String ON_DEMAND =
        "ON_DEMAND_VERIFIED_FACTORIZATION";
    private static final String CACHE = "VERIFIED_DERIVED_MACRO_CACHE";
    private static final String FACTORED = "(x-1)*(x+1)";

    @Test
    void retainsDistinctOccurrenceBoundTransitionsAndTypedWork() {
        var input = input(ON_DEMAND);
        var studyCase = formationCase();
        var profile = profile(input);
        var firstWork = work(3L, 0L, 0L, 0L, 0L);
        var secondWork = work(5L, 0L, 0L, 0L, 0L);
        var first = transition(
            input,
            0,
            List.of(0),
            FACTORED + "+(x^2-1)",
            firstWork,
            CacheDisposition.CACHE_DISABLED,
            "NONE",
            "NONE",
            "NONE"
        );
        var second = transition(
            input,
            1,
            List.of(1),
            "(x^2-1)+" + FACTORED,
            secondWork,
            CacheDisposition.CACHE_DISABLED,
            "NONE",
            "NONE",
            "NONE"
        );

        first.validateAgainst(0, input, studyCase, profile);
        second.validateAgainst(1, input, studyCase, profile);
        assertEquals(
            "regelsuche.polynomial-theory-utility-transition-outcome/v1",
            first.schema()
        );
        assertNotEquals(first.transitionId(), second.transitionId());
        assertNotEquals(
            first.transitionEvidenceHash(),
            second.transitionEvidenceHash()
        );
        assertEquals(List.of(0), first.occurrencePath());
        assertThrows(
            UnsupportedOperationException.class,
            () -> first.occurrencePath().clear()
        );

        var aggregate = firstWork.plus(secondWork).plus(overheadWork());
        assertTrue(aggregate.covers(firstWork.plus(secondWork)));
        assertEquals(
            aggregate.primitiveWork() + aggregate.mechanicalWork(),
            aggregate.totalWork()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> first.validateAgainst(1, input, studyCase, profile)
        );
    }

    @Test
    void rejectsCounterfeitReboundAndInvalidOccurrenceEvidence() {
        var input = input(ON_DEMAND);
        var transition = transition(
            input,
            0,
            List.of(0),
            FACTORED + "+(x^2-1)",
            work(3L, 0L, 0L, 0L, 0L),
            CacheDisposition.CACHE_DISABLED,
            "NONE",
            "NONE",
            "NONE"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityTransitionOutcome(
                hash("counterfeit-transition"),
                transition.transitionIndex(),
                transition.executionInputId(),
                transition.occurrencePath(),
                transition.sourceOccurrenceExpression(),
                transition.transformedOccurrenceExpression(),
                transition.sourceRootExpression(),
                transition.transformedRootExpression(),
                transition.transformationId(),
                transition.backendId(),
                transition.sourceEvidenceHash(),
                transition.transitionEvidenceHash(),
                transition.cacheDisposition(),
                transition.cacheRevision(),
                transition.cacheEntryId(),
                transition.evictedCacheEntryId(),
                transition.work()
            )
        );

        var foreignInput = input(CACHE);
        assertThrows(
            IllegalArgumentException.class,
            () -> transition.validateAgainst(
                0,
                foreignInput,
                formationCase(),
                profile(foreignInput)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityTransitionOutcome.create(
                0,
                input.inputId(),
                List.of(-1),
                "x^2-1",
                FACTORED,
                formationCase().sourceExpression(),
                FACTORED + "+(x^2-1)",
                profile(input).transformationId(),
                profile(input).engineId(),
                hash("source"),
                hash("transition"),
                CacheDisposition.CACHE_DISABLED,
                "NONE",
                "NONE",
                "NONE",
                work(3L, 0L, 0L, 0L, 0L)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityTransitionOutcome.create(
                0,
                input.inputId(),
                List.of(),
                "x^2-1",
                FACTORED,
                formationCase().sourceExpression(),
                FACTORED + "+(x^2-1)",
                profile(input).transformationId(),
                profile(input).engineId(),
                hash("source-root-mismatch"),
                hash("transition-root-mismatch"),
                CacheDisposition.CACHE_DISABLED,
                "NONE",
                "NONE",
                "NONE",
                work(3L, 0L, 0L, 0L, 0L)
            )
        );
    }

    @Test
    void bindsCacheMissReplayAndEvictionToFrozenLineage() {
        var input = input(CACHE);
        var studyCase = formationCase();
        var profile = profile(input);
        String entryId = hash("verified-derived-macro-entry");
        String evictedId = hash("evicted-derived-macro-entry");

        var miss = transition(
            input,
            0,
            List.of(0),
            FACTORED + "+(x^2-1)",
            work(5L, 1L, 1L, 0L, 0L),
            CacheDisposition.CACHE_MISS_INSERTED,
            PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
            entryId,
            "NONE"
        );
        var hit = transition(
            input,
            1,
            List.of(1),
            "(x^2-1)+" + FACTORED,
            work(0L, 1L, 0L, 0L, 2L),
            CacheDisposition.CACHE_HIT_REPLAYED,
            PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
            entryId,
            "NONE"
        );
        var evictingMiss = transition(
            input,
            0,
            List.of(0),
            FACTORED + "+(x^2-1)",
            work(5L, 1L, 1L, 2L, 0L),
            CacheDisposition.CACHE_MISS_INSERTED,
            PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
            entryId,
            evictedId
        );

        miss.validateAgainst(0, input, studyCase, profile);
        hit.validateAgainst(1, input, studyCase, profile);
        evictingMiss.validateAgainst(0, input, studyCase, profile);
        assertEquals(entryId, hit.cacheEntryId());
        assertEquals(evictedId, evictingMiss.evictedCacheEntryId());
        assertEquals(2L, hit.work().cacheReplayWork());
        assertEquals(2L, evictingMiss.work().cacheEvictionWork());

        assertThrows(
            IllegalArgumentException.class,
            () -> transition(
                input,
                0,
                List.of(0),
                FACTORED + "+(x^2-1)",
                work(1L, 1L, 0L, 0L, 1L),
                CacheDisposition.CACHE_HIT_REPLAYED,
                PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
                entryId,
                "NONE"
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> transition(
                input,
                0,
                List.of(0),
                FACTORED + "+(x^2-1)",
                work(5L, 1L, 1L, 1L, 0L),
                CacheDisposition.CACHE_MISS_INSERTED,
                PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
                entryId,
                "NONE"
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> transition(
                input,
                0,
                List.of(0),
                FACTORED + "+(x^2-1)",
                work(5L, 1L, 1L, 0L, 0L),
                CacheDisposition.CACHE_MISS_INSERTED,
                "invented-cache-revision",
                entryId,
                "NONE"
            )
        );
    }

    @Test
    void rejectsNegativeAndOverflowingWork() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityWorkBreakdown(
                -1L, 0L, 0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, 0L, 0L
            )
        );
        assertThrows(
            ArithmeticException.class,
            () -> new PolynomialTheoryUtilityWorkBreakdown(
                0L, Long.MAX_VALUE, 1L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, 0L, 0L
            )
        );
        assertThrows(
            ArithmeticException.class,
            () -> new PolynomialTheoryUtilityWorkBreakdown(
                Long.MAX_VALUE, 1L, 0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, 0L, 0L
            )
        );
    }

    private static PolynomialTheoryUtilityTransitionOutcome transition(
        PolynomialTheoryUtilityExecutionInput input,
        int index,
        List<Integer> path,
        String transformedRoot,
        PolynomialTheoryUtilityWorkBreakdown work,
        CacheDisposition cacheDisposition,
        String cacheRevision,
        String cacheEntryId,
        String evictedCacheEntryId
    ) {
        var selectedProfile = profile(input);
        return PolynomialTheoryUtilityTransitionOutcome.create(
            index,
            input.inputId(),
            path,
            "x^2-1",
            FACTORED,
            formationCase().sourceExpression(),
            transformedRoot,
            selectedProfile.transformationId(),
            selectedProfile.engineId(),
            hash("source-evidence:" + input.inputId() + ":" + index),
            hash("transition-evidence:" + input.inputId() + ":" + index),
            cacheDisposition,
            cacheRevision,
            cacheEntryId,
            evictedCacheEntryId,
            work
        );
    }

    private static PolynomialTheoryUtilityWorkBreakdown work(
        long factorization,
        long cacheLookup,
        long cacheInsertion,
        long cacheEviction,
        long cacheReplay
    ) {
        return new PolynomialTheoryUtilityWorkBreakdown(
            2L,
            1L,
            1L,
            factorization,
            1L,
            1L,
            1L,
            1L,
            1L,
            cacheLookup,
            cacheInsertion,
            cacheEviction,
            cacheReplay,
            1L
        );
    }

    private static PolynomialTheoryUtilityWorkBreakdown overheadWork() {
        return new PolynomialTheoryUtilityWorkBreakdown(
            0L, 0L, 0L, 0L, 0L, 0L, 0L,
            0L, 0L, 0L, 0L, 0L, 0L, 1L
        );
    }

    private static PolynomialTheoryUtilityExecutionInput input(
        String profileId
    ) {
        return PolynomialTheoryUtilityExecutionInputs.freeze().inputs()
            .stream()
            .filter(value -> profileId.equals(value.profileId()))
            .filter(value -> CASE_ID.equals(value.caseId()))
            .filter(value -> "CP06_FULL".equals(value.checkpointId()))
            .findFirst()
            .orElseThrow();
    }

    private static PolynomialTheoryUtilityExecutionProfile profile(
        PolynomialTheoryUtilityExecutionInput input
    ) {
        return PolynomialTheoryUtilityExecutionInputs.profile(
            input.profileId()
        );
    }

    private static PolynomialTheoryUtilityCaseCorpus.FormationCase
            formationCase() {
        return PolynomialTheoryUtilityCaseCorpus.load().cases().stream()
            .filter(value -> CASE_ID.equals(value.caseId()))
            .findFirst()
            .orElseThrow();
    }

    private static String hash(String value) {
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            value.getBytes(StandardCharsets.UTF_8)
        );
    }
}
