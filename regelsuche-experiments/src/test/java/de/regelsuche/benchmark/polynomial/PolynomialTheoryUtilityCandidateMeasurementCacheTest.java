package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityCacheEvent.Kind;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityCandidateResult.TerminalStatus;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityTransitionOutcome.CacheDisposition;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityTransitionTrace.PrimitiveStep;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityCandidateMeasurementCacheTest {
    private static final String CASE_ID = "z02-difference-of-squares";
    private static final String CACHE = "VERIFIED_DERIVED_MACRO_CACHE";
    private static final String FACTORED = "(x-1)*(x+1)";

    @Test
    void retainsCompleteCacheMissInsertionAndEvictionSequence() {
        var result = transitionResult(
            CacheDisposition.CACHE_MISS_INSERTED,
            true
        );
        var transition = result.transitions().getFirst();
        var measurements = PolynomialTheoryUtilityCandidateMeasurements.create(
            result,
            List.of(trace(transition)),
            List.of(attempt(result)),
            List.of(
                event(0, result, transition, Kind.LOOKUP_MISS),
                event(1, result, transition, Kind.INSERTION),
                event(2, result, transition, Kind.EVICTION)
            )
        );

        assertEquals(1, measurements.cacheMissCount());
        assertEquals(1, measurements.cacheInsertionCount());
        assertEquals(1, measurements.cacheEvictionCount());
        assertEquals(0, measurements.cacheHitCount());
        assertEquals(0, measurements.cacheReplayCount());
        assertEquals(1, measurements.factorizationRequestCount());
    }

    @Test
    void retainsCompleteCacheHitReplayWithoutANewFactorizationAttempt() {
        var result = transitionResult(
            CacheDisposition.CACHE_HIT_REPLAYED,
            false
        );
        var transition = result.transitions().getFirst();
        var measurements = PolynomialTheoryUtilityCandidateMeasurements.create(
            result,
            List.of(trace(transition)),
            List.of(),
            List.of(
                event(0, result, transition, Kind.LOOKUP_HIT),
                event(1, result, transition, Kind.REPLAY)
            )
        );

        assertEquals(1, measurements.cacheHitCount());
        assertEquals(1, measurements.cacheReplayCount());
        assertEquals(0, measurements.cacheMissCount());
        assertEquals(0, measurements.cacheInsertionCount());
        assertEquals(0, measurements.factorizationRequestCount());
        assertEquals(0L, result.work().factorizationWork());
    }

    @Test
    void retainsAnUnboundMissWhenNoValidatedTransitionFollows() {
        var result = noTransitionResultWithLookupWork();
        var event = PolynomialTheoryUtilityCacheEvent.create(
            0,
            result.input().inputId(),
            PolynomialTheoryUtilityCacheEvent.NO_TRANSITION,
            Kind.LOOKUP_MISS,
            PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
            hash("unbound-entry"),
            hash("unbound-miss-evidence")
        );
        var measurements = PolynomialTheoryUtilityCandidateMeasurements.create(
            result,
            List.of(),
            List.of(),
            List.of(event)
        );

        assertEquals(1, measurements.cacheMissCount());
        assertEquals(0, measurements.generatedTransitionCount());
        assertEquals(0, measurements.factorizationRequestCount());
    }

    @Test
    void rejectsMissingOrReorderedTransitionCacheSequence() {
        var result = transitionResult(
            CacheDisposition.CACHE_MISS_INSERTED,
            false
        );
        var transition = result.transitions().getFirst();
        var trace = trace(transition);
        var attempt = attempt(result);

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateMeasurements.create(
                result,
                List.of(trace),
                List.of(attempt),
                List.of(event(0, result, transition, Kind.LOOKUP_MISS))
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateMeasurements.create(
                result,
                List.of(trace),
                List.of(attempt),
                List.of(
                    event(0, result, transition, Kind.INSERTION),
                    event(1, result, transition, Kind.LOOKUP_MISS)
                )
            )
        );
    }

    @Test
    void rejectsEvictionEvidenceWhenTheTransitionDidNotEvict() {
        var result = transitionResult(
            CacheDisposition.CACHE_MISS_INSERTED,
            false
        );
        var transition = result.transitions().getFirst();
        var counterfeitEviction = PolynomialTheoryUtilityCacheEvent.create(
            2,
            result.input().inputId(),
            PolynomialTheoryUtilityCacheEvent.NO_TRANSITION,
            Kind.EVICTION,
            PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
            hash("counterfeit-eviction"),
            hash("counterfeit-eviction-evidence")
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateMeasurements.create(
                result,
                List.of(trace(transition)),
                List.of(attempt(result)),
                List.of(
                    event(0, result, transition, Kind.LOOKUP_MISS),
                    event(1, result, transition, Kind.INSERTION),
                    counterfeitEviction
                )
            )
        );
    }

    @Test
    void rejectsUnboundMutationOrReplayEvents() {
        var result = noTransitionResultWithLookupWork();
        var insertion = PolynomialTheoryUtilityCacheEvent.create(
            0,
            result.input().inputId(),
            PolynomialTheoryUtilityCacheEvent.NO_TRANSITION,
            Kind.INSERTION,
            PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
            hash("orphan-entry"),
            hash("orphan-insertion")
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateMeasurements.create(
                result,
                List.of(),
                List.of(),
                List.of(insertion)
            )
        );
    }

    @Test
    void rejectsCacheEventsThatDoNotMatchRetainedWork() {
        var zeroWork = PolynomialTheoryUtilityCandidateResult.noTransition(
            input(),
            formationCase(),
            "ZERO_WORK_RESULT"
        );
        var miss = PolynomialTheoryUtilityCacheEvent.create(
            0,
            zeroWork.input().inputId(),
            PolynomialTheoryUtilityCacheEvent.NO_TRANSITION,
            Kind.LOOKUP_MISS,
            PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
            hash("zero-work-entry"),
            hash("zero-work-miss")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateMeasurements.create(
                zeroWork,
                List.of(),
                List.of(),
                List.of(miss)
            )
        );

        var lookupWork = noTransitionResultWithLookupWork();
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateMeasurements.create(
                lookupWork,
                List.of(),
                List.of(),
                List.of()
            )
        );
    }

    private static PolynomialTheoryUtilityCandidateResult transitionResult(
        CacheDisposition disposition,
        boolean eviction
    ) {
        var input = input();
        var studyCase = formationCase();
        var profile = profile();
        var work = work(disposition, eviction);
        String entryId = hash("cache-entry:" + disposition);
        String evictedEntry = eviction
            ? hash("evicted-entry")
            : "NONE";
        var transition = PolynomialTheoryUtilityTransitionOutcome.create(
            0,
            input.inputId(),
            List.of(),
            studyCase.sourceExpression(),
            FACTORED,
            studyCase.sourceExpression(),
            FACTORED,
            profile.transformationId(),
            profile.engineId(),
            hash("source-evidence:" + disposition),
            hash("transition-evidence:" + disposition),
            disposition,
            PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
            entryId,
            evictedEntry,
            work
        );
        return PolynomialTheoryUtilityCandidateResult.create(
            input,
            studyCase,
            TerminalStatus.VALIDATED_TRANSITION,
            "VALIDATED_CACHE_TRANSITION",
            work,
            List.of(transition),
            "VERIFIED"
        );
    }

    private static PolynomialTheoryUtilityCandidateResult
            noTransitionResultWithLookupWork() {
        var work = new PolynomialTheoryUtilityWorkBreakdown(
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            1L,
            0L,
            0L,
            0L,
            0L
        );
        return PolynomialTheoryUtilityCandidateResult.create(
            input(),
            formationCase(),
            TerminalStatus.NO_TRANSITION,
            "CACHE_MISS_WITHOUT_TRANSITION",
            work,
            List.of(),
            "NOT_VERIFIED"
        );
    }

    private static PolynomialTheoryUtilityTransitionTrace trace(
        PolynomialTheoryUtilityTransitionOutcome transition
    ) {
        return PolynomialTheoryUtilityTransitionTrace.create(
            transition,
            1,
            List.of(
                PrimitiveStep.create(
                    transition,
                    0,
                    0,
                    "verified-factorization-transition",
                    hash("primitive-transition")
                )
            ),
            List.of()
        );
    }

    private static PolynomialTheoryUtilityFactorizationAttempt attempt(
        PolynomialTheoryUtilityCandidateResult result
    ) {
        String candidate = hash("factorization-candidate");
        return PolynomialTheoryUtilityFactorizationAttempt.create(
            0,
            result.input().inputId(),
            profile().engineId(),
            hash("factorization-request"),
            hash("factorization-request-evidence"),
            List.of(candidate),
            candidate,
            result.transitions().getFirst().transitionId(),
            "VERIFIED",
            hash("factorization-report")
        );
    }

    private static PolynomialTheoryUtilityCacheEvent event(
        int index,
        PolynomialTheoryUtilityCandidateResult result,
        PolynomialTheoryUtilityTransitionOutcome transition,
        Kind kind
    ) {
        String entryId = kind == Kind.EVICTION
            ? transition.evictedCacheEntryId()
            : transition.cacheEntryId();
        return PolynomialTheoryUtilityCacheEvent.create(
            index,
            result.input().inputId(),
            transition.transitionId(),
            kind,
            PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
            entryId,
            hash("cache-event:" + index + ":" + kind)
        );
    }

    private static PolynomialTheoryUtilityWorkBreakdown work(
        CacheDisposition disposition,
        boolean eviction
    ) {
        return new PolynomialTheoryUtilityWorkBreakdown(
            1L,
            1L,
            1L,
            disposition == CacheDisposition.CACHE_HIT_REPLAYED ? 0L : 1L,
            1L,
            1L,
            1L,
            1L,
            1L,
            1L,
            disposition == CacheDisposition.CACHE_MISS_INSERTED ? 1L : 0L,
            eviction ? 1L : 0L,
            disposition == CacheDisposition.CACHE_HIT_REPLAYED ? 1L : 0L,
            1L
        );
    }

    private static PolynomialTheoryUtilityExecutionProfile profile() {
        return PolynomialTheoryUtilityExecutionInputs.profile(CACHE);
    }

    private static PolynomialTheoryUtilityExecutionInput input() {
        return PolynomialTheoryUtilityExecutionInputs.freeze().inputs()
            .stream()
            .filter(value -> CACHE.equals(value.profileId()))
            .filter(value -> CASE_ID.equals(value.caseId()))
            .filter(value -> "CP06_FULL".equals(value.checkpointId()))
            .findFirst()
            .orElseThrow();
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
