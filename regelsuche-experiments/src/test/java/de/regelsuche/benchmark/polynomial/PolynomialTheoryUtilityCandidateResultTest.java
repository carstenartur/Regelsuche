package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityCandidateResult.TerminalStatus;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityTransitionOutcome.CacheDisposition;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityCandidateResultTest {
    private static final String CASE_ID = "two-identical-occurrences";
    private static final String PROFILE_ID =
        "ON_DEMAND_VERIFIED_FACTORIZATION";
    private static final String FACTORED = "(x-1)*(x+1)";

    @Test
    void retainsOrderedDistinctTransitionsAndAggregateWork() {
        var input = input();
        var studyCase = formationCase();
        var firstWork = work(3L);
        var secondWork = work(5L);
        var first = transition(input, 0, 0, firstWork);
        var second = transition(input, 1, 1, secondWork);
        var aggregate = firstWork.plus(secondWork).plus(overhead());

        var result = PolynomialTheoryUtilityCandidateResult.create(
            input,
            studyCase,
            TerminalStatus.VALIDATED_TRANSITION,
            "TWO_OCCURRENCES_FACTORED",
            aggregate,
            List.of(first, second),
            "VERIFIED"
        );

        assertEquals(
            "regelsuche.polynomial-theory-utility-candidate-result/v2",
            result.schema()
        );
        assertEquals(2, result.generatedTransitions());
        assertTrue(result.transitionEvidenceHash().startsWith("sha256:"));
        assertEquals(List.of(first, second), result.transitions());
        assertThrows(
            UnsupportedOperationException.class,
            () -> result.transitions().clear()
        );
        result.validateAgainst(input, studyCase);

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateResult.create(
                input,
                studyCase,
                TerminalStatus.VALIDATED_TRANSITION,
                "REORDERED_WITH_STALE_INDICES",
                aggregate,
                List.of(second, first),
                "VERIFIED"
            )
        );

        var reversedFirst = transition(input, 0, 1, secondWork);
        var reversedSecond = transition(input, 1, 0, firstWork);
        var reindexed = PolynomialTheoryUtilityCandidateResult.create(
            input,
            studyCase,
            TerminalStatus.VALIDATED_TRANSITION,
            "TWO_OCCURRENCES_FACTORED",
            aggregate,
            List.of(reversedFirst, reversedSecond),
            "VERIFIED"
        );
        assertNotEquals(result.resultId(), reindexed.resultId());
        assertNotEquals(
            result.transitionEvidenceHash(),
            reindexed.transitionEvidenceHash()
        );
    }

    @Test
    void rejectsDuplicateTransitionsAndUnderreportedWork() {
        var input = input();
        var studyCase = formationCase();
        var firstWork = work(3L);
        var secondWork = work(5L);
        var first = transition(input, 0, 0, firstWork);
        var second = transition(input, 1, 1, secondWork);
        var aggregate = firstWork.plus(secondWork);

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateResult.create(
                input,
                studyCase,
                TerminalStatus.VALIDATED_TRANSITION,
                "DUPLICATE_TRANSITION",
                firstWork.plus(firstWork),
                List.of(first, first),
                "VERIFIED"
            )
        );

        var underreported = new PolynomialTheoryUtilityWorkBreakdown(
            aggregate.primitiveWork(),
            aggregate.matchingWork() - 1L,
            aggregate.sourceValidationWork(),
            aggregate.factorizationWork(),
            aggregate.verificationWork(),
            aggregate.renderingWork(),
            aggregate.reparseWork(),
            aggregate.reconstructionWork(),
            aggregate.occurrenceReplacementWork(),
            aggregate.cacheLookupWork(),
            aggregate.cacheInsertionWork(),
            aggregate.cacheEvictionWork(),
            aggregate.cacheReplayWork(),
            aggregate.evidenceConstructionWork()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateResult.create(
                input,
                studyCase,
                TerminalStatus.VALIDATED_TRANSITION,
                "UNDERREPORTED_MATCHING",
                underreported,
                List.of(first, second),
                "VERIFIED"
            )
        );

        var excessive = new PolynomialTheoryUtilityWorkBreakdown(
            input.admittedPrimitiveWork() + 1L,
            0L, 0L, 0L, 0L, 0L, 0L,
            0L, 0L, 0L, 0L, 0L, 0L, 0L
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateResult.create(
                input,
                studyCase,
                TerminalStatus.BUDGET_INCONCLUSIVE,
                "EXCESSIVE_WORK",
                excessive,
                List.of(),
                "NOT_VERIFIED"
            )
        );
    }

    @Test
    void rejectsInvalidTerminalEvidenceIdentityAndRebinding() {
        var input = input();
        var studyCase = formationCase();
        var transition = transition(input, 0, 0, work(3L));

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateResult.create(
                input,
                studyCase,
                TerminalStatus.VALIDATED_TRANSITION,
                "MISSING_TRANSITION",
                PolynomialTheoryUtilityWorkBreakdown.zero(),
                List.of(),
                "VERIFIED"
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateResult.create(
                input,
                studyCase,
                TerminalStatus.NO_TRANSITION,
                "HIDDEN_TRANSITION",
                transition.work(),
                List.of(transition),
                "NOT_REQUESTED"
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateResult.create(
                input,
                PolynomialTheoryUtilityCaseCorpus.load().cases().get(0),
                TerminalStatus.NO_TRANSITION,
                "SUBSTITUTED_CASE",
                PolynomialTheoryUtilityWorkBreakdown.zero(),
                List.of(),
                "NOT_REQUESTED"
            )
        );

        var valid = PolynomialTheoryUtilityCandidateResult.noTransition(
            input,
            studyCase,
            "NO_CANDIDATE"
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityCandidateResult(
                hash("counterfeit-result"),
                valid.input(),
                valid.sourceRootExpression(),
                valid.terminalStatus(),
                valid.detailCode(),
                valid.work(),
                valid.transitions(),
                valid.verifierOutcome()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> valid.validateAgainst(foreignInput(), studyCase)
        );
    }

    @Test
    void retainsNoTransitionWithoutInventedEvidence() {
        var result = PolynomialTheoryUtilityCandidateResult.noTransition(
            input(),
            formationCase(),
            "NO_CANDIDATE"
        );

        assertEquals(TerminalStatus.NO_TRANSITION, result.terminalStatus());
        assertEquals(0, result.generatedTransitions());
        assertEquals("NONE", result.transitionEvidenceHash());
        assertEquals(
            PolynomialTheoryUtilityWorkBreakdown.zero(),
            result.work()
        );
        assertEquals("NOT_REQUESTED", result.verifierOutcome());
    }

    private static PolynomialTheoryUtilityTransitionOutcome transition(
        PolynomialTheoryUtilityExecutionInput input,
        int transitionIndex,
        int occurrenceIndex,
        PolynomialTheoryUtilityWorkBreakdown work
    ) {
        var profile = PolynomialTheoryUtilityExecutionInputs.profile(
            input.profileId()
        );
        String transformedRoot = occurrenceIndex == 0
            ? FACTORED + "+(x^2-1)"
            : "(x^2-1)+" + FACTORED;
        return PolynomialTheoryUtilityTransitionOutcome.create(
            transitionIndex,
            input.inputId(),
            List.of(occurrenceIndex),
            "x^2-1",
            FACTORED,
            formationCase().sourceExpression(),
            transformedRoot,
            profile.transformationId(),
            profile.engineId(),
            hash("source:" + transitionIndex + ":" + occurrenceIndex),
            hash("transition:" + transitionIndex + ":" + occurrenceIndex),
            CacheDisposition.CACHE_DISABLED,
            "NONE",
            "NONE",
            "NONE",
            work
        );
    }

    private static PolynomialTheoryUtilityWorkBreakdown work(
        long factorization
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
            0L,
            0L,
            0L,
            0L,
            1L
        );
    }

    private static PolynomialTheoryUtilityWorkBreakdown overhead() {
        return new PolynomialTheoryUtilityWorkBreakdown(
            0L, 0L, 0L, 0L, 0L, 0L, 0L,
            0L, 0L, 0L, 0L, 0L, 0L, 1L
        );
    }

    private static PolynomialTheoryUtilityExecutionInput input() {
        return PolynomialTheoryUtilityExecutionInputs.freeze().inputs()
            .stream()
            .filter(value -> PROFILE_ID.equals(value.profileId()))
            .filter(value -> CASE_ID.equals(value.caseId()))
            .filter(value -> "CP06_FULL".equals(value.checkpointId()))
            .findFirst()
            .orElseThrow();
    }

    private static PolynomialTheoryUtilityExecutionInput foreignInput() {
        return PolynomialTheoryUtilityExecutionInputs.freeze().inputs()
            .stream()
            .filter(value -> PROFILE_ID.equals(value.profileId()))
            .filter(value -> !CASE_ID.equals(value.caseId()))
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
