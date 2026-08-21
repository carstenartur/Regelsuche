package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PerfectSquareStructurePreparationTransformationEngineTest {
    private static final String SOURCE = "4 * x^4 * y^2 - 9 * z^2";
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    @Test
    void emitsTheCertifiedDifferenceOfSquaresMove() {
        Transformation candidate = prepared(engine().transform(SOURCE));
        assertEquals(
            PerfectSquareStructurePreparationSolver.PRINCIPAL_RULE_ID,
            candidate.rule());
        assertExpression(
            "(2 * x^2 * y - 3 * z) * (2 * x^2 * y + 3 * z)",
            candidate.transformedExpression());
        assertEquals(List.of(), candidate.assumptions());
        assertEquals(
            List.of(
                PerfectSquareStructurePreparationSolver.PREPARATION_RULE_ID,
                PerfectSquareStructurePreparationSolver.PRINCIPAL_RULE_ID),
            candidate.primitiveRuleIds());
        assertEquals(2, candidate.primitiveStepCount());
    }

    @Test
    void handlesNestedAstPositions() {
        assertContainsExpression(
            engine().transform("sin(" + SOURCE + ")"),
            "sin((2 * x^2 * y - 3 * z) * (2 * x^2 * y + 3 * z))");
        var execution = engine().transformWithEvidence("1 + sin(" + SOURCE + ")");
        assertTrue(execution.observations().stream().anyMatch(observation ->
            observation.path().equals("1.0")
                && observation.attempt().status()
                    == PerfectSquareStructurePreparationSolver.Status.PREPARED));
    }

    @Test
    void hiddenRuleAndMissingReplayFailClosed() {
        AstRewriteTransformationEngine direct = new AstRewriteTransformationEngine();
        Set<String> hidden = direct.rules().stream().map(RewriteRule::id)
            .filter(id -> !id.equals(
                PerfectSquareStructurePreparationSolver.PRINCIPAL_RULE_ID))
            .collect(java.util.stream.Collectors.toSet());
        var hiddenEngine = new PerfectSquareStructurePreparationTransformationEngine(
            direct, direct, hidden,
            new PerfectSquareStructurePreparationSolver(), 16);
        assertTrue(hiddenEngine.transform(SOURCE).stream()
            .noneMatch(this::isPrepared));

        TransformationEngine empty = expression -> List.of();
        var noReplay = new PerfectSquareStructurePreparationTransformationEngine(
            empty, empty,
            Set.of(PerfectSquareStructurePreparationSolver.PRINCIPAL_RULE_ID),
            new PerfectSquareStructurePreparationSolver(), 16);
        assertTrue(noReplay.transform(SOURCE).isEmpty());
    }

    @Test
    void retainsAllEarlierPreparationPaths() {
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
        AstRewriteTransformationEngine direct = new AstRewriteTransformationEngine();
        var disabled = new PerfectSquareStructurePreparationTransformationEngine(
            direct, direct,
            direct.rules().stream().map(RewriteRule::id)
                .collect(java.util.stream.Collectors.toSet()),
            new PerfectSquareStructurePreparationSolver(), 0);
        assertEquals(direct.transform(SOURCE), disabled.transform(SOURCE));
    }

    private PerfectSquareStructurePreparationTransformationEngine engine() {
        return new PerfectSquareStructurePreparationTransformationEngine();
    }

    private Transformation prepared(List<Transformation> transformations) {
        return transformations.stream().filter(this::isPrepared)
            .findFirst().orElseThrow();
    }

    private boolean isPrepared(Transformation transformation) {
        return transformation.primitiveRuleIds().contains(
            PerfectSquareStructurePreparationSolver.PREPARATION_RULE_ID);
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
