package de.regelsuche.scoring.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.scoring.ExpressionScorer;
import org.junit.jupiter.api.Test;

/**
 * For every {@link TransformationGoal} this class pins one
 * "result A is definitely better than result B" example.
 */
class TransformationGoalTest {
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();

    @Test
    void simplifyPrefersShorterOverLongerForm() {
        // x has 0 operators; x + 0 has one — SIMPLIFY prefers the shorter form
        assertBetterThan(TransformationGoal.SIMPLIFY, "x", "x + 0");
        assertBetterThan(TransformationGoal.SIMPLIFY, "x", "x + 0 + 0");
    }

    @Test
    void factorizeStrictlyPrefersFactoredOverExpanded() {
        // (x + 1) * (x + 2) is factored, x^2 + 3*x + 2 is expanded.
        assertBetterThan(TransformationGoal.FACTORIZE, "(x + 1) * (x + 2)", "x^2 + 3*x + 2");
        // A trinomial square preference too.
        assertBetterThan(TransformationGoal.FACTORIZE, "(x + 3)^2", "x^2 + 6*x + 9");
    }

    @Test
    void numericallyStablePrefersHornerOverExpanded() {
        // x*(x*(x+3)+3)+1 has no subtraction and shallow division-free
        // structure; the expanded form has more SUB-comparable risk surface.
        assertBetterThan(TransformationGoal.NUMERICALLY_STABLE,
            "x * (x * (x + 3) + 3) + 1",
            "(x + 1)^5 - (x + 1)^4 - (x + 1)^4 - (x + 1)^4 - (x + 1)^4");
        // Catastrophic cancellation x - x should be penalised.
        assertBetterThan(TransformationGoal.NUMERICALLY_STABLE, "0", "x - x");
    }

    @Test
    void proofFriendlyPrefersSymmetricForm() {
        // Symmetric (palindromic) operand sequence beats the same-length
        // asymmetric one.
        assertBetterThan(TransformationGoal.PROOF_FRIENDLY, "a + b + a", "a + b + c");
    }

    @Test
    void teachingFriendlyPrefersIntegerCoefficientsOverFractions() {
        // (x + 1)^2 is the school-book form; expanded is denser but still
        // teachable; an explicit division is the worst.
        assertBetterThan(TransformationGoal.TEACHING_FRIENDLY,
            "(x + 1)^2",
            "(x^2 + 2*x + 1) / 1");
        // Avoid very large coefficients
        assertBetterThan(TransformationGoal.TEACHING_FRIENDLY,
            "(x + 3) * (x + 5)",
            "x^2 + 8*x + 15000");
    }

    @Test
    void everyGoalProvidesACostModel() {
        for (TransformationGoal goal : TransformationGoal.values()) {
            CostModel model = goal.defaultCostModel();
            assertNotNull(model, () -> goal + " must have a default cost model");
            assertNotNull(model.id(), () -> model.getClass() + " must expose a stable id");
        }
    }

    private void assertBetterThan(TransformationGoal goal, String better, String worse) {
        CostModel model = goal.defaultCostModel();
        ExpressionScore betterScore = scorer.score(better);
        ExpressionScore worseScore = scorer.score(worse);
        int betterCost = model.cost(better, canonicalizer, betterScore);
        int worseCost = model.cost(worse, canonicalizer, worseScore);
        assertTrue(betterCost < worseCost, () -> String.format(
            "%s: expected '%s' (cost=%d) to be strictly better than '%s' (cost=%d)",
            goal, better, betterCost, worse, worseCost));
    }

    @Test
    void costModelIdsAreStableAndDistinct() {
        // The UI dropdown and JSON exports key off model.id(); these strings
        // must therefore be stable across versions.
        assertEquals("operator-count", new OperatorCountCost().id());
        assertEquals("depth", new DepthCost().id());
        assertEquals("factored-form", new FactoredFormCost().id());
        assertEquals("numeric-stability", new NumericStabilityCost().id());
        assertEquals("teaching-friendly", new TeachingFriendlinessCost().id());
        assertEquals("symmetry", new SymmetryCost().id());
    }
}
