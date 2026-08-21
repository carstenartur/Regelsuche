package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MonomialCommonFactorPreparationTransformationEngineTest {
    private static final String SOURCE = "x^2 * y + x * z";
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    @Test
    void emitsTheCertifiedCompositeMoveThroughTheRealRule() {
        Transformation candidate = prepared(engine().transform(SOURCE));
        assertEquals(
            MonomialCommonFactorPreparationSolver.PRINCIPAL_RULE_ID,
            candidate.rule());
        assertExpression("x * (x * y + z)", candidate.transformedExpression());
        assertEquals(List.of(), candidate.assumptions());
        assertEquals(
            List.of(
                MonomialCommonFactorPreparationSolver.PREPARATION_RULE_ID,
                MonomialCommonFactorPreparationSolver.PRINCIPAL_RULE_ID),
            candidate.primitiveRuleIds());
        assertEquals(2, candidate.primitiveStepCount());
    }

    @Test
    void handlesNestedAndNumericVariableCases() {
        assertContainsExpression(
            engine().transform("sin(" + SOURCE + ")"),
            "sin(x * (x * y + z))");
        assertContainsExpression(
            engine().transform("6 * x^2 * y + 9 * x * z"),
            "(3 * x) * (2 * x * y + 3 * z)");
    }

    @Test
    void hiddenRuleAndMissingReplayFailClosed() {
        AstRewriteTransformationEngine direct = new AstRewriteTransformationEngine();
        Set<String> hidden = direct.rules().stream().map(RewriteRule::id)
            .filter(id -> !id.equals(
                MonomialCommonFactorPreparationSolver.PRINCIPAL_RULE_ID))
            .collect(java.util.stream.Collectors.toSet());
        var hiddenEngine = new MonomialCommonFactorPreparationTransformationEngine(
            direct, direct, hidden, new MonomialCommonFactorPreparationSolver(), 16);
        assertTrue(hiddenEngine.transform(SOURCE).stream()
            .noneMatch(this::isPrepared));

        TransformationEngine empty = expression -> List.of();
        var noReplay = new MonomialCommonFactorPreparationTransformationEngine(
            empty, empty,
            Set.of(MonomialCommonFactorPreparationSolver.PRINCIPAL_RULE_ID),
            new MonomialCommonFactorPreparationSolver(), 16);
        assertTrue(noReplay.transform(SOURCE).isEmpty());
    }

    @Test
    void retainsEarlierPreparationPaths() {
        assertTrue(containsPrimitive(
            engine().transform("(b * (a * c)) / a"),
            AcNormalizationPreparationSolver.PREPARATION_RULE_ID));
        assertTrue(containsPrimitive(
            engine().transform("(x^3 - 1) / (x - 1)"),
            RulePreparationPlanner.PREPARATION_RULE_ID));
    }

    @Test
    void disabledPreparationPreservesBaseResults() {
        AstRewriteTransformationEngine direct = new AstRewriteTransformationEngine();
        var disabled = new MonomialCommonFactorPreparationTransformationEngine(
            direct, direct,
            direct.rules().stream().map(RewriteRule::id)
                .collect(java.util.stream.Collectors.toSet()),
            new MonomialCommonFactorPreparationSolver(), 0);
        assertEquals(direct.transform(SOURCE), disabled.transform(SOURCE));
    }

    @Test
    void evidenceRetainsTheExactNestedAstPosition() {
        var execution = engine().transformWithEvidence("1 + sin(" + SOURCE + ")");
        assertTrue(execution.observations().stream().anyMatch(observation ->
            observation.path().equals("1.0")
                && observation.attempt().status()
                    == MonomialCommonFactorPreparationSolver.Status.PREPARED));
    }

    private MonomialCommonFactorPreparationTransformationEngine engine() {
        return new MonomialCommonFactorPreparationTransformationEngine();
    }

    private Transformation prepared(List<Transformation> transformations) {
        return transformations.stream().filter(this::isPrepared)
            .findFirst().orElseThrow();
    }

    private boolean isPrepared(Transformation transformation) {
        return transformation.primitiveRuleIds().contains(
            MonomialCommonFactorPreparationSolver.PREPARATION_RULE_ID);
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
        String expected
    ) {
        assertTrue(transformations.stream().filter(this::isPrepared)
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
