package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MonomialCommonFactorPreparationTransformationEngineTest {
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    @Test
    void exposesTheSynthesizedCommonMonomialAsACompositeMove() {
        Transformation candidate =
            new MonomialCommonFactorPreparationTransformationEngine()
                .transform("x^2 * y + x * z")
                .stream()
                .filter(transformation -> transformation.primitiveRuleIds()
                    .contains(
                        MonomialCommonFactorPreparationSolver
                            .PREPARATION_RULE_ID))
                .findFirst()
                .orElseThrow();

        assertEquals(
            MonomialCommonFactorPreparationSolver.PRINCIPAL_RULE_ID,
            candidate.rule());
        assertEquals(
            canonicalizer.stableHash("x * (x * y + z)"),
            canonicalizer.stableHash(candidate.transformedExpression()));
        assertEquals(List.of(), candidate.assumptions());
        assertEquals(
            List.of(
                MonomialCommonFactorPreparationSolver.PREPARATION_RULE_ID,
                MonomialCommonFactorPreparationSolver.PRINCIPAL_RULE_ID),
            candidate.primitiveRuleIds());
        assertEquals(2, candidate.primitiveStepCount());
    }

    @Test
    void preparesANestedFunctionArgument() {
        boolean reached =
            new MonomialCommonFactorPreparationTransformationEngine()
                .transform("sin(x^2 * y + x * z)")
                .stream()
                .filter(transformation -> transformation.primitiveRuleIds()
                    .contains(
                        MonomialCommonFactorPreparationSolver
                            .PREPARATION_RULE_ID))
                .anyMatch(transformation -> canonicalizer.stableHash(
                        transformation.transformedExpression())
                    .equals(canonicalizer.stableHash(
                        "sin(x * (x * y + z))")));

        assertTrue(reached);
    }

    @Test
    void extractsNumericAndVariableFactorsThroughTheRealPrincipalRule() {
        boolean reached =
            new MonomialCommonFactorPreparationTransformationEngine()
                .transform("6 * x^2 * y + 9 * x * z")
                .stream()
                .anyMatch(transformation -> transformation.primitiveRuleIds()
                    .contains(
                        MonomialCommonFactorPreparationSolver
                            .PREPARATION_RULE_ID)
                    && canonicalizer.stableHash(
                        transformation.transformedExpression()).equals(
                            canonicalizer.stableHash(
                                "(3 * x) * (2 * x * y + 3 * z)")));

        assertTrue(reached);
    }

    @Test
    void hiddenPrincipalRulePreventsPreparedApplications() {
        AstRewriteTransformationEngine direct =
            new AstRewriteTransformationEngine();
        Set<String> visible = direct.rules().stream()
            .map(RewriteRule::id)
            .filter(id -> !id.equals(
                MonomialCommonFactorPreparationSolver.PRINCIPAL_RULE_ID))
            .collect(java.util.stream.Collectors.toSet());
        MonomialCommonFactorPreparationTransformationEngine engine =
            new MonomialCommonFactorPreparationTransformationEngine(
                direct,
                direct,
                visible,
                new MonomialCommonFactorPreparationSolver(),
                16);

        assertTrue(engine.transform("x^2 * y + x * z").stream()
            .noneMatch(transformation -> transformation.primitiveRuleIds()
                .contains(
                    MonomialCommonFactorPreparationSolver
                        .PREPARATION_RULE_ID)));
    }

    @Test
    void principalReplayIsMandatory() {
        TransformationEngine empty = expression -> List.of();
        MonomialCommonFactorPreparationTransformationEngine engine =
            new MonomialCommonFactorPreparationTransformationEngine(
                empty,
                empty,
                Set.of(
                    MonomialCommonFactorPreparationSolver.PRINCIPAL_RULE_ID),
                new MonomialCommonFactorPreparationSolver(),
                16);

        assertTrue(engine.transform("x^2 * y + x * z").isEmpty());
    }

    @Test
    void retainsTheExistingAcCancellationPreparationPath() {
        assertTrue(
            new MonomialCommonFactorPreparationTransformationEngine()
                .transform("(b * (a * c)) / a")
                .stream()
                .anyMatch(transformation -> transformation.primitiveRuleIds()
                    .contains(
                        AcNormalizationPreparationSolver.PREPARATION_RULE_ID)));
    }

    @Test
    void retainsTheExistingExactPolynomialPreparationPath() {
        assertTrue(
            new MonomialCommonFactorPreparationTransformationEngine()
                .transform("(x^3 - 1) / (x - 1)")
                .stream()
                .anyMatch(transformation -> transformation.primitiveRuleIds()
                    .contains(RulePreparationPlanner.PREPARATION_RULE_ID)));
    }

    @Test
    void disabledPreparationPreservesTheBaseEngineResults() {
        AstRewriteTransformationEngine direct =
            new AstRewriteTransformationEngine();
        MonomialCommonFactorPreparationTransformationEngine disabled =
            new MonomialCommonFactorPreparationTransformationEngine(
                direct,
                direct,
                direct.rules().stream()
                    .map(RewriteRule::id)
                    .collect(java.util.stream.Collectors.toSet()),
                new MonomialCommonFactorPreparationSolver(),
                0);
        String expression = "x^2 * y + x * z";

        assertEquals(
            direct.transform(expression),
            disabled.transform(expression));
    }

    @Test
    void observationsRetainThePreparationStatusAndAstPosition() {
        MonomialCommonFactorPreparationTransformationEngine.Execution execution =
            new MonomialCommonFactorPreparationTransformationEngine()
                .transformWithEvidence("1 + sin(x^2 * y + x * z)");

        assertTrue(execution.observations().stream().anyMatch(observation ->
            observation.path().equals("1.0")
                && observation.attempt().status()
                    == MonomialCommonFactorPreparationSolver.Status.PREPARED));
    }
}
