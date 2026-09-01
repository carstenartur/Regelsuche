package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityCandidateResult.TerminalStatus;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityTransitionOutcome.CacheDisposition;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityTransitionTrace.PrimitiveStep;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityCandidateMeasurementsTest {
    private static final String CASE_ID = "z02-difference-of-squares";
    private static final String ASSUMPTION_CASE =
        "unsupported-rational-function";
    private static final String ON_DEMAND =
        "ON_DEMAND_VERIFIED_FACTORIZATION";
    private static final String BASELINE = "NO_FACTORIZATION";
    private static final String FACTORED = "(x-1)*(x+1)";

    @Test
    void retainsCompleteOnDemandMeasurementsAndDerivedCounts() {
        var result = transitionResult();
        var transition = result.transitions().getFirst();
        var trace = trace(transition, List.of());
        var attempt = attempt(result, 0, "request-0");

        var measurements = PolynomialTheoryUtilityCandidateMeasurements.create(
            result,
            List.of(trace),
            List.of(attempt),
            List.of()
        );

        assertEquals(
            "regelsuche.polynomial-theory-utility-"
                + "candidate-measurements/v1",
            measurements.schema()
        );
        assertEquals(1, measurements.generatedTransitionCount());
        assertEquals(List.of(2), measurements.pathDepths());
        assertEquals(2, measurements.totalPathDepth());
        assertEquals(List.of(2), measurements.primitiveExpansionLengths());
        assertEquals(2, measurements.totalPrimitiveExpansionLength());
        assertEquals(1, measurements.factorizationRequestCount());
        assertEquals(2, measurements.factorizationCandidateCount());
        assertEquals(0, measurements.cacheHitCount());
        assertEquals(0, measurements.cacheMissCount());
        assertEquals(0, measurements.cacheInsertionCount());
        assertEquals(0, measurements.cacheEvictionCount());
        assertEquals(0, measurements.cacheReplayCount());
        assertEquals(
            List.of("prepare-polynomial", "factor-polynomial"),
            measurements.primitiveRuleIds()
        );
        assertEquals(
            List.of(trace.transformedAstNodeCount()),
            measurements.transformedAstNodeCounts()
        );
        assertEquals(
            List.of(trace.astNodeGrowth()),
            measurements.astNodeGrowths()
        );
        measurements.validateAgainst(result);

        assertThrows(
            UnsupportedOperationException.class,
            () -> measurements.transitionTraces().clear()
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> measurements.factorizationAttempts().clear()
        );
    }

    @Test
    void retainsVisibleAssumptionWithoutInventingATransition() {
        var result = noTransitionResult(ON_DEMAND, ASSUMPTION_CASE);
        var measurements = PolynomialTheoryUtilityCandidateMeasurements.create(
            result,
            List.of(),
            List.of(),
            List.of()
        );

        assertEquals(
            "X_NONZERO",
            measurements.formationAssumptionSetId()
        );
        assertEquals(List.of(), measurements.normalizedAssumptions());
        assertEquals(0, measurements.generatedTransitionCount());
        assertEquals(List.of(), measurements.pathDepths());
        assertEquals(0, measurements.totalPrimitiveExpansionLength());
        assertTrue(measurements.sourceAstNodeCount() > 0);
    }

    @Test
    void retainsTheNoFactorizationControlAsACompleteZeroObservation() {
        var result = noTransitionResult(BASELINE, CASE_ID);
        var measurements = PolynomialTheoryUtilityCandidateMeasurements.create(
            result,
            List.of(),
            List.of(),
            List.of()
        );

        assertEquals(0, measurements.factorizationRequestCount());
        assertEquals(0, measurements.factorizationCandidateCount());
        assertEquals(0, measurements.generatedTransitionCount());
        assertEquals(0, measurements.totalPathDepth());
        assertEquals(0, measurements.totalPrimitiveExpansionLength());
        assertEquals(List.of(), measurements.transformedAstNodeCounts());
        assertEquals(List.of(), measurements.astNodeGrowths());
    }

    @Test
    void rejectsMissingTraceOrFactorizationProducer() {
        var result = transitionResult();
        var trace = trace(result.transitions().getFirst(), List.of());
        var attempt = attempt(result, 0, "request-0");

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateMeasurements.create(
                result,
                List.of(),
                List.of(attempt),
                List.of()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateMeasurements.create(
                result,
                List.of(trace),
                List.of(),
                List.of()
            )
        );

        var duplicateProducer = attempt(result, 1, "request-1");
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateMeasurements.create(
                result,
                List.of(trace),
                List.of(attempt, duplicateProducer),
                List.of()
            )
        );
    }

    @Test
    void rejectsFactorizationObservationWithoutRetainedWork() {
        var result = noTransitionResult(ON_DEMAND, CASE_ID);
        var profile = profile(ON_DEMAND);
        var attempt = PolynomialTheoryUtilityFactorizationAttempt.create(
            0,
            result.input().inputId(),
            profile.engineId(),
            hash("negative-request"),
            hash("negative-request-evidence"),
            List.of(),
            PolynomialTheoryUtilityFactorizationAttempt.NO_SELECTION,
            PolynomialTheoryUtilityFactorizationAttempt.NO_TRANSITION,
            "NO_CANDIDATE",
            hash("negative-report")
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateMeasurements.create(
                result,
                List.of(),
                List.of(attempt),
                List.of()
            )
        );
    }

    @Test
    void rejectsTraceAssumptionsAbsentFromTheResultMeasurement() {
        var result = transitionResult();
        var trace = trace(
            result.transitions().getFirst(),
            List.of("x != 0")
        );
        var attempt = attempt(result, 0, "request-0");

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateMeasurements.create(
                result,
                List.of(trace),
                List.of(attempt),
                List.of()
            )
        );
    }

    @Test
    void rejectsCounterfeitIdentityFormationMeasurementsAndRebinding() {
        var result = noTransitionResult(
            ON_DEMAND,
            ASSUMPTION_CASE
        );
        var valid = PolynomialTheoryUtilityCandidateMeasurements.create(
            result,
            List.of(),
            List.of(),
            List.of()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityCandidateMeasurements(
                hash("counterfeit-measurement"),
                valid.result(),
                valid.formationAssumptionSetId(),
                valid.normalizedAssumptions(),
                valid.sourceAstNodeCount(),
                valid.transitionTraces(),
                valid.factorizationAttempts(),
                valid.cacheEvents()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityCandidateMeasurements(
                valid.measurementId(),
                valid.result(),
                "NONE",
                valid.normalizedAssumptions(),
                valid.sourceAstNodeCount(),
                valid.transitionTraces(),
                valid.factorizationAttempts(),
                valid.cacheEvents()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityCandidateMeasurements(
                valid.measurementId(),
                valid.result(),
                valid.formationAssumptionSetId(),
                List.of("0 != x"),
                valid.sourceAstNodeCount(),
                valid.transitionTraces(),
                valid.factorizationAttempts(),
                valid.cacheEvents()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityCandidateMeasurements(
                valid.measurementId(),
                valid.result(),
                valid.formationAssumptionSetId(),
                valid.normalizedAssumptions(),
                valid.sourceAstNodeCount() + 1,
                valid.transitionTraces(),
                valid.factorizationAttempts(),
                valid.cacheEvents()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> valid.validateAgainst(
                noTransitionResult(ON_DEMAND, CASE_ID)
            )
        );

        var transitionResult = transitionResult();
        var transition = transitionResult.transitions().getFirst();
        var trace = trace(transition, List.of());
        var first = attempt(transitionResult, 0, "request-order");
        var reversed = PolynomialTheoryUtilityFactorizationAttempt.create(
            0,
            transitionResult.input().inputId(),
            profile(ON_DEMAND).engineId(),
            first.requestId(),
            first.requestEvidenceHash(),
            List.of(
                first.candidateIds().get(1),
                first.candidateIds().get(0)
            ),
            first.selectedCandidateId(),
            first.transitionId(),
            first.verifierOutcome(),
            first.reportEvidenceHash()
        );
        var originalMeasurements =
            PolynomialTheoryUtilityCandidateMeasurements.create(
                transitionResult,
                List.of(trace),
                List.of(first),
                List.of()
            );
        var changed = PolynomialTheoryUtilityCandidateMeasurements.create(
            transitionResult,
            List.of(trace),
            List.of(reversed),
            List.of()
        );
        assertNotEquals(
            originalMeasurements.measurementId(),
            changed.measurementId()
        );
    }

    private static PolynomialTheoryUtilityCandidateResult transitionResult() {
        var input = input(ON_DEMAND, CASE_ID);
        var studyCase = formationCase(CASE_ID);
        var profile = profile(ON_DEMAND);
        var work = onDemandWork();
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
            studyCase,
            TerminalStatus.VALIDATED_TRANSITION,
            "VALIDATED_FACTOR",
            work,
            List.of(transition),
            "VERIFIED"
        );
    }

    private static PolynomialTheoryUtilityTransitionTrace trace(
        PolynomialTheoryUtilityTransitionOutcome transition,
        List<String> assumptions
    ) {
        return PolynomialTheoryUtilityTransitionTrace.create(
            transition,
            2,
            List.of(
                PrimitiveStep.create(
                    transition,
                    0,
                    0,
                    "prepare-polynomial",
                    hash("primitive-0")
                ),
                PrimitiveStep.create(
                    transition,
                    1,
                    1,
                    "factor-polynomial",
                    hash("primitive-1")
                )
            ),
            assumptions
        );
    }

    private static PolynomialTheoryUtilityFactorizationAttempt attempt(
        PolynomialTheoryUtilityCandidateResult result,
        int index,
        String requestMaterial
    ) {
        var profile = profile(result.input().profileId());
        String first = hash(requestMaterial + ":candidate-0");
        String second = hash(requestMaterial + ":candidate-1");
        return PolynomialTheoryUtilityFactorizationAttempt.create(
            index,
            result.input().inputId(),
            profile.engineId(),
            hash(requestMaterial),
            hash(requestMaterial + ":evidence"),
            List.of(first, second),
            second,
            result.transitions().getFirst().transitionId(),
            "VERIFIED",
            hash(requestMaterial + ":report")
        );
    }

    private static PolynomialTheoryUtilityCandidateResult noTransitionResult(
        String profileId,
        String caseId
    ) {
        return PolynomialTheoryUtilityCandidateResult.noTransition(
            input(profileId, caseId),
            formationCase(caseId),
            "NO_TRANSITION_FOR_MEASUREMENT_TEST"
        );
    }

    private static PolynomialTheoryUtilityWorkBreakdown onDemandWork() {
        return new PolynomialTheoryUtilityWorkBreakdown(
            2L,
            1L,
            1L,
            2L,
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

    private static PolynomialTheoryUtilityExecutionProfile profile(
        String profileId
    ) {
        return PolynomialTheoryUtilityExecutionInputs.profile(profileId);
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
