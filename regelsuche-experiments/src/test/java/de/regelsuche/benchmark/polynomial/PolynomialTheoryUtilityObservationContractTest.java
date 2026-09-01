package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityCacheEvent.Kind;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityTransitionOutcome.CacheDisposition;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityObservationContractTest {
    private static final String CASE_ID = "z02-difference-of-squares";
    private static final String ON_DEMAND =
        "ON_DEMAND_VERIFIED_FACTORIZATION";
    private static final String CACHE =
        "VERIFIED_DERIVED_MACRO_CACHE";

    @Test
    void retainsOrderedFactorizationRequestCandidatesAndTransition() {
        var result = transitionResult(
            ON_DEMAND,
            CacheDisposition.CACHE_DISABLED,
            false
        );
        var transition = result.transitions().getFirst();
        var profile = profile(ON_DEMAND);
        var firstCandidate = hash("candidate-1");
        var secondCandidate = hash("candidate-2");

        var attempt = PolynomialTheoryUtilityFactorizationAttempt.create(
            0,
            result.input().inputId(),
            profile.engineId(),
            hash("request"),
            hash("request-evidence"),
            List.of(firstCandidate, secondCandidate),
            secondCandidate,
            transition.transitionId(),
            "VERIFIED",
            hash("report-evidence")
        );

        assertEquals(
            "regelsuche.polynomial-theory-utility-"
                + "factorization-attempt/v1",
            attempt.schema()
        );
        assertEquals(2, attempt.candidateCount());
        assertTrue(attempt.selectedCandidate());
        assertTrue(attempt.producedTransition());
        assertEquals(secondCandidate, attempt.selectedCandidateId());
        assertEquals(transition.transitionId(), attempt.transitionId());
        attempt.validateAgainst(0, result, profile);
        assertThrows(
            UnsupportedOperationException.class,
            () -> attempt.candidateIds().clear()
        );

        var reordered = PolynomialTheoryUtilityFactorizationAttempt.create(
            0,
            result.input().inputId(),
            profile.engineId(),
            hash("request"),
            hash("request-evidence"),
            List.of(secondCandidate, firstCandidate),
            secondCandidate,
            transition.transitionId(),
            "VERIFIED",
            hash("report-evidence")
        );
        assertNotEquals(attempt.attemptId(), reordered.attemptId());
    }

    @Test
    void retainsAConclusiveAttemptWithoutCandidateOrTransition() {
        var result = noTransitionResult(ON_DEMAND);
        var profile = profile(ON_DEMAND);
        var attempt = PolynomialTheoryUtilityFactorizationAttempt.create(
            0,
            result.input().inputId(),
            profile.engineId(),
            hash("irreducible-request"),
            hash("irreducible-request-evidence"),
            List.of(),
            PolynomialTheoryUtilityFactorizationAttempt.NO_SELECTION,
            PolynomialTheoryUtilityFactorizationAttempt.NO_TRANSITION,
            "NO_CANDIDATE",
            hash("irreducible-report")
        );

        assertEquals(0, attempt.candidateCount());
        assertFalse(attempt.selectedCandidate());
        assertFalse(attempt.producedTransition());
        attempt.validateAgainst(0, result, profile);
    }

    @Test
    void rejectsInvalidFactorizationSelectionIdentityAndBinding() {
        var result = transitionResult(
            ON_DEMAND,
            CacheDisposition.CACHE_DISABLED,
            false
        );
        var transition = result.transitions().getFirst();
        var profile = profile(ON_DEMAND);
        var candidate = hash("candidate");
        var attempt = PolynomialTheoryUtilityFactorizationAttempt.create(
            0,
            result.input().inputId(),
            profile.engineId(),
            hash("request"),
            hash("request-evidence"),
            List.of(candidate),
            candidate,
            transition.transitionId(),
            "VERIFIED",
            hash("report")
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityFactorizationAttempt.create(
                0,
                result.input().inputId(),
                profile.engineId(),
                hash("request"),
                hash("request-evidence"),
                List.of(candidate),
                hash("absent-candidate"),
                transition.transitionId(),
                "VERIFIED",
                hash("report")
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityFactorizationAttempt.create(
                0,
                result.input().inputId(),
                profile.engineId(),
                hash("request"),
                hash("request-evidence"),
                List.of(candidate),
                candidate,
                transition.transitionId(),
                "NOT_VERIFIED",
                hash("report")
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityFactorizationAttempt.create(
                0,
                result.input().inputId(),
                profile.engineId(),
                hash("request"),
                hash("request-evidence"),
                List.of(candidate, candidate),
                candidate,
                transition.transitionId(),
                "VERIFIED",
                hash("report")
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityFactorizationAttempt.create(
                0,
                result.input().inputId(),
                profile.engineId(),
                hash("request"),
                hash("request-evidence"),
                List.of(candidate),
                PolynomialTheoryUtilityFactorizationAttempt.NO_SELECTION,
                transition.transitionId(),
                "NO_CANDIDATE",
                hash("report")
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityFactorizationAttempt(
                hash("counterfeit-attempt"),
                attempt.attemptIndex(),
                attempt.executionInputId(),
                attempt.backendId(),
                attempt.requestId(),
                attempt.requestEvidenceHash(),
                attempt.candidateIds(),
                attempt.selectedCandidateId(),
                attempt.transitionId(),
                attempt.verifierOutcome(),
                attempt.reportEvidenceHash()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> attempt.validateAgainst(1, result, profile)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> attempt.validateAgainst(
                0,
                result,
                profile(CACHE)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> attempt.validateAgainst(
                0,
                noTransitionResult("NO_FACTORIZATION"),
                profile("NO_FACTORIZATION")
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> attempt.validateAgainst(
                0,
                noTransitionResult(CACHE),
                profile(CACHE)
            )
        );
    }

    @Test
    void retainsMissInsertionEvictionAndHitReplayLineage() {
        var missResult = transitionResult(
            CACHE,
            CacheDisposition.CACHE_MISS_INSERTED,
            true
        );
        var missTransition = missResult.transitions().getFirst();
        var profile = profile(CACHE);
        var missEvents = List.of(
            cacheEvent(
                0,
                missResult,
                missTransition,
                Kind.LOOKUP_MISS,
                missTransition.cacheEntryId()
            ),
            cacheEvent(
                1,
                missResult,
                missTransition,
                Kind.INSERTION,
                missTransition.cacheEntryId()
            ),
            cacheEvent(
                2,
                missResult,
                missTransition,
                Kind.EVICTION,
                missTransition.evictedCacheEntryId()
            )
        );
        for (int index = 0; index < missEvents.size(); index++) {
            missEvents.get(index).validateAgainst(
                index,
                missResult,
                profile
            );
        }

        var hitResult = transitionResult(
            CACHE,
            CacheDisposition.CACHE_HIT_REPLAYED,
            false
        );
        var hitTransition = hitResult.transitions().getFirst();
        var hitEvents = List.of(
            cacheEvent(
                0,
                hitResult,
                hitTransition,
                Kind.LOOKUP_HIT,
                hitTransition.cacheEntryId()
            ),
            cacheEvent(
                1,
                hitResult,
                hitTransition,
                Kind.REPLAY,
                hitTransition.cacheEntryId()
            )
        );
        for (int index = 0; index < hitEvents.size(); index++) {
            hitEvents.get(index).validateAgainst(
                index,
                hitResult,
                profile
            );
        }

        assertTrue(missEvents.getFirst().lookup());
        assertTrue(hitEvents.getFirst().lookup());
        assertFalse(missEvents.get(1).lookup());
        assertTrue(missEvents.stream().allMatch(
            PolynomialTheoryUtilityCacheEvent::transitionBound
        ));
        assertEquals(
            "regelsuche.polynomial-theory-utility-cache-event/v1",
            missEvents.getFirst().schema()
        );
        assertEquals(3L, missEvents.stream()
            .map(PolynomialTheoryUtilityCacheEvent::eventId)
            .distinct()
            .count());
    }

    @Test
    void retainsAnUnboundCacheMissWithoutAResultTransition() {
        var result = noTransitionResult(CACHE);
        var profile = profile(CACHE);
        var event = PolynomialTheoryUtilityCacheEvent.create(
            0,
            result.input().inputId(),
            PolynomialTheoryUtilityCacheEvent.NO_TRANSITION,
            Kind.LOOKUP_MISS,
            PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
            hash("unbound-entry"),
            hash("unbound-cache-miss")
        );

        assertFalse(event.transitionBound());
        event.validateAgainst(0, result, profile);
    }

    @Test
    void rejectsCounterfeitCacheEventsAndTransitionRebinding() {
        var result = transitionResult(
            CACHE,
            CacheDisposition.CACHE_HIT_REPLAYED,
            false
        );
        var transition = result.transitions().getFirst();
        var profile = profile(CACHE);
        var event = cacheEvent(
            0,
            result,
            transition,
            Kind.LOOKUP_HIT,
            transition.cacheEntryId()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityCacheEvent(
                hash("counterfeit-event"),
                event.eventIndex(),
                event.executionInputId(),
                event.transitionId(),
                event.kind(),
                event.cacheRevision(),
                event.entryId(),
                event.evidenceHash()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> event.validateAgainst(1, result, profile)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> event.validateAgainst(
                0,
                noTransitionResult(ON_DEMAND),
                profile(ON_DEMAND)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> event.validateAgainst(
                0,
                noTransitionResult("NO_FACTORIZATION"),
                profile("NO_FACTORIZATION")
            )
        );

        var wrongKind = PolynomialTheoryUtilityCacheEvent.create(
            0,
            result.input().inputId(),
            transition.transitionId(),
            Kind.LOOKUP_MISS,
            PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
            transition.cacheEntryId(),
            hash("wrong-kind-evidence")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> wrongKind.validateAgainst(0, result, profile)
        );

        var wrongRevision = PolynomialTheoryUtilityCacheEvent.create(
            0,
            result.input().inputId(),
            transition.transitionId(),
            Kind.LOOKUP_HIT,
            "regelsuche.polynomial-theory-utility-derived-macro-cache/v0",
            transition.cacheEntryId(),
            hash("wrong-revision-evidence")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> wrongRevision.validateAgainst(0, result, profile)
        );
    }

    private static PolynomialTheoryUtilityCacheEvent cacheEvent(
        int eventIndex,
        PolynomialTheoryUtilityCandidateResult result,
        PolynomialTheoryUtilityTransitionOutcome transition,
        Kind kind,
        String entryId
    ) {
        return PolynomialTheoryUtilityCacheEvent.create(
            eventIndex,
            result.input().inputId(),
            transition.transitionId(),
            kind,
            PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
            entryId,
            hash("cache-event:" + eventIndex + ":" + kind)
        );
    }

    private static PolynomialTheoryUtilityCandidateResult noTransitionResult(
        String profileId
    ) {
        return PolynomialTheoryUtilityCandidateResult.noTransition(
            input(profileId),
            formationCase(),
            "TEST_OBSERVATION_WITHOUT_TRANSITION"
        );
    }

    private static PolynomialTheoryUtilityCandidateResult transitionResult(
        String profileId,
        CacheDisposition cacheDisposition,
        boolean eviction
    ) {
        var input = input(profileId);
        var formationCase = formationCase();
        var profile = profile(profileId);
        var work = transitionWork(cacheDisposition, eviction);
        String cacheRevision = cacheDisposition
                == CacheDisposition.CACHE_DISABLED
            ? "NONE"
            : PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION;
        String cacheEntryId = cacheDisposition
                == CacheDisposition.CACHE_DISABLED
            ? "NONE"
            : hash("cache-entry:" + cacheDisposition);
        String evictedEntryId = eviction
            ? hash("evicted-cache-entry")
            : "NONE";
        var transition = PolynomialTheoryUtilityTransitionOutcome.create(
            0,
            input.inputId(),
            List.of(),
            formationCase.sourceExpression(),
            "(x-1)*(x+1)",
            formationCase.sourceExpression(),
            "(x-1)*(x+1)",
            profile.transformationId(),
            profile.engineId(),
            hash("source-evidence:" + cacheDisposition),
            hash("transition-evidence:" + cacheDisposition),
            cacheDisposition,
            cacheRevision,
            cacheEntryId,
            evictedEntryId,
            work
        );
        return PolynomialTheoryUtilityCandidateResult.create(
            input,
            formationCase,
            PolynomialTheoryUtilityCandidateResult.TerminalStatus
                .VALIDATED_TRANSITION,
            "VALIDATED_TEST_TRANSITION",
            work,
            List.of(transition),
            "VERIFIED"
        );
    }

    private static PolynomialTheoryUtilityWorkBreakdown transitionWork(
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
            disposition == CacheDisposition.CACHE_DISABLED ? 0L : 1L,
            disposition == CacheDisposition.CACHE_MISS_INSERTED ? 1L : 0L,
            eviction ? 1L : 0L,
            disposition == CacheDisposition.CACHE_HIT_REPLAYED ? 1L : 0L,
            1L
        );
    }

    private static PolynomialTheoryUtilityExecutionProfile profile(
        String profileId
    ) {
        return PolynomialTheoryUtilityExecutionInputs.profile(profileId);
    }

    private static PolynomialTheoryUtilityExecutionInput input(
        String profileId
    ) {
        return PolynomialTheoryUtilityExecutionInputs.freeze().inputs()
            .stream()
            .filter(value -> profileId.equals(value.profileId()))
            .filter(value -> CASE_ID.equals(value.caseId()))
            .filter(value -> "CP06_FULL".equals(
                value.checkpointId()
            ))
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
