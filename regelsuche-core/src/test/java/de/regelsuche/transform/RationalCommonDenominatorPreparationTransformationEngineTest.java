package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RationalCommonDenominatorPreparationTransformationEngineTest {
    private static final String SOURCE = "a / b + c / d";
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    @Test
    void emitsTheCertifiedPrincipalMove() {
        Transformation candidate = prepared(engine().transform(SOURCE));
        assertEquals(
            RationalCommonDenominatorPreparationSolver.PRINCIPAL_RULE_ID,
            candidate.rule());
        assertExpression(
            "(a * d + c * b) / (b * d)",
            candidate.transformedExpression());
        assertEquals(
            List.of("b * d != 0"),
            candidate.assumptions());
        assertEquals(
            List.of(
                RationalCommonDenominatorPreparationSolver.PREPARATION_RULE_ID,
                RationalCommonDenominatorPreparationSolver.PRINCIPAL_RULE_ID),
            candidate.primitiveRuleIds());
        assertEquals(2, candidate.primitiveStepCount());
    }

    @Test
    void retainsOneDirectSameDenominatorNormalizationAtTheRoot() {
        List<Transformation> transformations =
            engine().transform("a / b + c / b");
        List<Transformation> matches = transformations.stream()
            .filter(transformation -> canonicalizer.stableHash(
                    transformation.transformedExpression())
                .equals(canonicalizer.stableHash("(a + c) / b")))
            .toList();
        assertEquals(1, matches.size());
        assertEquals(1, matches.getFirst().primitiveStepCount());
        assertTrue(matches.getFirst().primitiveRuleIds().stream().noneMatch(
            RationalCommonDenominatorPreparationSolver.PREPARATION_RULE_ID
                ::equals));
    }

    @Test
    void replaysTheCheapDirectPathAtNestedAstPositions() {
        List<Transformation> transformations =
            engine().transform("sin(a / b + c / b)");
        Transformation direct = transformations.stream()
            .filter(transformation -> canonicalizer.stableHash(
                    transformation.transformedExpression())
                .equals(canonicalizer.stableHash(
                    "sin((a + c) / b)")))
            .findFirst()
            .orElseThrow();
        assertEquals(
            RationalCommonDenominatorPreparationSolver.PRINCIPAL_RULE_ID,
            direct.rule());
        assertEquals(List.of("b != 0"), direct.assumptions());
        assertEquals(1, direct.primitiveStepCount());
        assertTrue(direct.primitiveRuleIds().stream().noneMatch(
            RationalCommonDenominatorPreparationSolver.PREPARATION_RULE_ID
                ::equals));
    }

    @Test
    void handlesNestedPreparedAstPositions() {
        assertContainsExpression(
            engine().transform("sin(" + SOURCE + ")"),
            "sin((a * d + c * b) / (b * d))",
            true);
        var execution = engine().transformWithEvidence(
            "1 + sin(" + SOURCE + ")");
        assertTrue(execution.observations().stream().anyMatch(observation ->
            observation.path().equals("1.0")
                && observation.attempt().status()
                    == RationalCommonDenominatorPreparationSolver.Status.PREPARED));
    }

    @Test
    void hiddenOperatorAndMissingReplayFailClosed() {
        TransformationEngine empty = expression -> List.of();
        var hidden =
            new RationalCommonDenominatorPreparationTransformationEngine(
                empty,
                empty,
                Set.of(),
                new RationalCommonDenominatorPreparationSolver(),
                16);
        assertTrue(hidden.transform(SOURCE).isEmpty());

        var missingReplay =
            new RationalCommonDenominatorPreparationTransformationEngine(
                empty,
                empty,
                Set.of(
                    RationalCommonDenominatorPreparationSolver.PRINCIPAL_RULE_ID),
                new RationalCommonDenominatorPreparationSolver(),
                16);
        assertTrue(missingReplay.transform(SOURCE).isEmpty());
        assertTrue(missingReplay.transform("sin(a / b + c / b)").isEmpty());
    }

    @Test
    void retainsAllEarlierPreparationPaths() {
        assertTrue(containsPrimitive(
            engine().transform("4 * x^4 * y^2 - 9 * z^2"),
            PerfectSquareStructurePreparationSolver.PREPARATION_RULE_ID));
        assertTrue(containsPrimitive(
            engine().transform("x^2 * y + x * z"),
            MonomialCommonFactorPreparationSolver.PREPARATION_RULE_ID));
        assertTrue(containsPrimitive(
            engine().transform("(b * (a * c)) / a"),
            AcNormalizationPreparationSolver.PREPARATION_RULE_ID));
        assertTrue(containsPrimitive(
            engine().transform("(x^3 - 1) / (x - 1)"),
            RulePreparationPlanner.PREPARATION_RULE_ID));
    }

    @Test
    void disabledPreparationPreservesBaseResults() {
        TransformationEngine base =
            new HypothesisTransformationEngine(
                new PerfectSquareStructurePreparationTransformationEngine(),
                List.of(new RationalNormalizationHypothesisOperator()));
        TransformationEngine principal =
            new HypothesisTransformationEngine(
                expression -> List.of(),
                List.of(new RationalNormalizationHypothesisOperator()));
        var disabled =
            new RationalCommonDenominatorPreparationTransformationEngine(
                base,
                principal,
                Set.of(
                    RationalCommonDenominatorPreparationSolver.PRINCIPAL_RULE_ID),
                new RationalCommonDenominatorPreparationSolver(),
                0);
        assertEquals(
            base.transform(SOURCE),
            disabled.transform(SOURCE));
        assertEquals(
            base.transform("sin(a / b + c / b)"),
            disabled.transform("sin(a / b + c / b)"));
    }

    private RationalCommonDenominatorPreparationTransformationEngine engine() {
        return new RationalCommonDenominatorPreparationTransformationEngine();
    }

    private Transformation prepared(
        List<Transformation> transformations
    ) {
        return transformations.stream()
            .filter(this::isPrepared)
            .findFirst()
            .orElseThrow();
    }

    private boolean isPrepared(Transformation transformation) {
        return transformation.primitiveRuleIds().contains(
            RationalCommonDenominatorPreparationSolver.PREPARATION_RULE_ID);
    }

    private boolean containsPrimitive(
        List<Transformation> transformations,
        String ruleId
    ) {
        return transformations.stream().anyMatch(transformation ->
            transformation.primitiveRuleIds().contains(ruleId));
    }

    private void assertContainsExpression(
        List<Transformation> transformations,
        String expected,
        boolean preparedOnly
    ) {
        assertTrue(transformations.stream()
            .filter(transformation -> !preparedOnly || isPrepared(transformation))
            .anyMatch(transformation -> canonicalizer.stableHash(
                    transformation.transformedExpression())
                .equals(canonicalizer.stableHash(expected))));
    }

    private void assertExpression(String expected, String actual) {
        assertEquals(
            canonicalizer.stableHash(expected),
            canonicalizer.stableHash(actual));
    }
}
