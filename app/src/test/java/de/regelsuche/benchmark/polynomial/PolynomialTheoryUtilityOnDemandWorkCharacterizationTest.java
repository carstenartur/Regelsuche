package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.polynomial
    .NativeUnivariateFactorizationEngine;
import de.regelsuche.math.algorithms.polynomial
    .NativeUnivariateFactorizationPolicy;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial
    .ExactNestedFactorizationTransformationPipeline;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityOnDemandWorkCharacterizationTest {
    @Test
    void fullDifferenceOfSquaresFitsItsFrozenCanonicalAuthority() {
        String caseId = "z02-difference-of-squares";
        String checkpointId = "CP06_FULL";
        var formationCase = PolynomialTheoryUtilityCaseCorpus.load().cases()
            .stream()
            .filter(value -> caseId.equals(value.caseId()))
            .findFirst()
            .orElseThrow();
        var input = PolynomialTheoryUtilityExecutionInputs.freeze().inputs()
            .stream()
            .filter(value ->
                PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter
                    .PROFILE_ID.equals(value.profileId())
            )
            .filter(value -> checkpointId.equals(value.checkpointId()))
            .filter(value -> caseId.equals(value.caseId()))
            .findFirst()
            .orElseThrow();

        var source = new ExpressionParser().parseExactTerm(
            formationCase.sourceExpression()
        );
        var position = new TreePosition(
            List.of(),
            ExpressionFormatter.format(source.expression())
        );
        var engine = NativeUnivariateFactorizationEngine.rationals(
            NativeUnivariateFactorizationPolicy.boundedDefaults()
                .withMaxEngineWorkUnits(input.factorizationWork())
        );
        var nested = new ExactNestedFactorizationTransformationPipeline()
            .transform(source, position, engine, 0);
        assertTrue(
            nested.transformed(),
            () -> "native root characterization did not transform: "
                + nested.status() + ':' + nested.detailCode()
        );

        int primitiveSteps = 11;
        Map<String, Long> stages = new LinkedHashMap<>(
            nested.totalWork().stages()
        );
        stages.merge(
            "study.evidence.factorization-attempt-records",
            1L,
            Math::addExact
        );
        stages.merge(
            "study.evidence.transition-outcome-records",
            1L,
            Math::addExact
        );
        stages.merge(
            "study.evidence.transition-trace-records",
            1L,
            Math::addExact
        );
        stages.merge(
            "study.evidence.primitive-step-records",
            (long) primitiveSteps,
            Math::addExact
        );
        var raw = new PolynomialWorkLedger(stages);
        int diagnosticAuthority = 1_000_000;
        var pricingInput = new PolynomialTheoryUtilityExecutionInput(
            PolynomialTheoryUtilityExecutionIdentity.sha256(
                "on-demand-root-work-characterization/v1"
                    .getBytes(StandardCharsets.UTF_8)
            ),
            input.rowId(),
            input.runId(),
            input.caseId(),
            input.profileId(),
            input.checkpointId(),
            input.adapterId(),
            diagnosticAuthority,
            diagnosticAuthority,
            diagnosticAuthority,
            input.inputStatus()
        );
        var work = PolynomialTheoryUtilityRawWorkPartitioner.project(
            pricingInput,
            primitiveSteps,
            raw
        ).work();

        assertTrue(
            work.primitiveWork() <= input.admittedPrimitiveWork()
                && work.mechanicalWork() <= input.totalMechanicalWork()
                && work.factorizationWork() <= input.factorizationWork(),
            () -> "canonical=" + work
                + "; authority={primitive=" + input.admittedPrimitiveWork()
                + ", mechanical=" + input.totalMechanicalWork()
                + ", factorization=" + input.factorizationWork() + "}"
                + "; raw=" + raw.stages()
        );
    }
}
