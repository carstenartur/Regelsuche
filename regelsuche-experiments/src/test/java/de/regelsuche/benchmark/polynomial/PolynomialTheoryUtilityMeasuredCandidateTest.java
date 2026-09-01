package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityCandidateResult.TerminalStatus;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityTransitionOutcome.CacheDisposition;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityMeasuredCandidateTest {
    private static final String CASE_ID = "z02-difference-of-squares";
    private static final String ON_DEMAND =
        "ON_DEMAND_VERIFIED_FACTORIZATION";

    @Test
    void acceptsTheOnlyImplicitCaseAsAZeroObservationResult() {
        var result = PolynomialTheoryUtilityCandidateResult.noTransition(
            input("NO_FACTORIZATION", CASE_ID),
            formationCase(CASE_ID),
            "ZERO_OBSERVATION_RESULT"
        );

        var measured =
            PolynomialTheoryUtilityMeasuredCandidate.withoutObservations(
                result
            );

        assertEquals(result, measured.result());
        assertEquals(0, measured.measurements().generatedTransitionCount());
        assertEquals(0, measured.measurements().factorizationRequestCount());
        assertEquals(List.of(), measured.measurements().cacheEvents());
    }

    @Test
    void rejectsATransitionWhenItsEvidenceWouldBeDropped() {
        var result = transitionResult();

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityMeasuredCandidate
                .withoutObservations(result)
        );
    }

    @Test
    void rejectsNonzeroPreflightWorkWhenItsEvidenceWouldBeDropped() {
        var frozenInput = input(ON_DEMAND, CASE_ID);
        var result = PolynomialTheoryUtilityCandidateResult.create(
            frozenInput,
            formationCase(CASE_ID),
            TerminalStatus.BUDGET_INCONCLUSIVE,
            "PREFLIGHT_WORK_WITHOUT_OBSERVATIONS",
            new PolynomialTheoryUtilityWorkBreakdown(
                0L,
                1L,
                1L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L
            ),
            List.of(),
            "NOT_VERIFIED"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityMeasuredCandidate
                .withoutObservations(result)
        );
    }

    @Test
    void rejectsMeasurementRebindingToAnotherResult() {
        var first = PolynomialTheoryUtilityCandidateResult.noTransition(
            input("NO_FACTORIZATION", CASE_ID),
            formationCase(CASE_ID),
            "FIRST_RESULT"
        );
        var secondCase = "z03-cubic-unity";
        var second = PolynomialTheoryUtilityCandidateResult.noTransition(
            input("NO_FACTORIZATION", secondCase),
            formationCase(secondCase),
            "SECOND_RESULT"
        );
        var measurements =
            PolynomialTheoryUtilityCandidateMeasurements.create(
                first,
                List.of(),
                List.of(),
                List.of()
            );

        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityMeasuredCandidate(
                second,
                measurements
            )
        );
    }

    private static PolynomialTheoryUtilityCandidateResult transitionResult() {
        var input = input(ON_DEMAND, CASE_ID);
        var formation = formationCase(CASE_ID);
        var profile = PolynomialTheoryUtilityExecutionInputs.profile(ON_DEMAND);
        var work = new PolynomialTheoryUtilityWorkBreakdown(
            1L,
            1L,
            1L,
            1L,
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
        var transition = PolynomialTheoryUtilityTransitionOutcome.create(
            0,
            input.inputId(),
            List.of(),
            formation.sourceExpression(),
            "(x-1)*(x+1)",
            formation.sourceExpression(),
            "(x-1)*(x+1)",
            profile.transformationId(),
            profile.engineId(),
            hash("source-evidence"),
            hash("transition-evidence"),
            CacheDisposition.CACHE_DISABLED,
            "NONE",
            "NONE",
            "NONE",
            work
        );
        return PolynomialTheoryUtilityCandidateResult.create(
            input,
            formation,
            TerminalStatus.VALIDATED_TRANSITION,
            "VALIDATED_TRANSITION_WITHOUT_MEASUREMENT_FIXTURE",
            work,
            List.of(transition),
            "VERIFIED"
        );
    }

    private static PolynomialTheoryUtilityExecutionInput input(
        String profileId,
        String caseId
    ) {
        return PolynomialTheoryUtilityExecutionInputs.freeze().inputs()
            .stream()
            .filter(value -> profileId.equals(value.profileId()))
            .filter(value -> caseId.equals(value.caseId()))
            .filter(value -> "CP06_FULL".equals(value.checkpointId()))
            .findFirst()
            .orElseThrow();
    }

    private static PolynomialTheoryUtilityCaseCorpus.FormationCase
            formationCase(String caseId) {
        return PolynomialTheoryUtilityCaseCorpus.load().cases().stream()
            .filter(value -> caseId.equals(value.caseId()))
            .findFirst()
            .orElseThrow();
    }

    private static String hash(String value) {
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            value.getBytes(StandardCharsets.UTF_8)
        );
    }
}
