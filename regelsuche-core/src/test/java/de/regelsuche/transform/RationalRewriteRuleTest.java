package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Atomic rational rules that close the rational-simplification gap. */
class RationalRewriteRuleTest {
    private final AstRewriteTransformationEngine engine =
        new AstRewriteTransformationEngine();

    @Test
    void distributesDivisionOverSumAndDifference() {
        assertGenerated("(2 * x + 4) / 2", "2 * x / 2 + 4 / 2");
        assertGenerated("(a - b) / c", "a / c - b / c");
    }

    @Test
    void doesNotDistributeOverAnExplicitZeroDivisor() {
        assertFalse(rules("(a + b) / 0").contains(
            "ast_distribute_division_over_sum"));
    }

    @Test
    void cancelsACommonFactorOnEitherSideOfTheProduct() {
        assertGenerated("2 * x / 2", "x");
        assertGenerated("(x - 1) * (x + 1) / (x - 1)", "x + 1");
        assertGenerated("(x + 1) * (x - 1) / (x - 1)", "x + 1");
    }

    @Test
    void cancellingASymbolicFactorRetainsItsNonZeroAssumption() {
        Transformation cancellation = transformation(
            "(x - 1) * (x + 1) / (x - 1)", "ast_cancel_division_factor");

        assertEquals(List.of("x - 1 != 0"), cancellation.assumptions());
    }

    @Test
    void cancellingANumericFactorNeedsNoAssumption() {
        Transformation cancellation =
            transformation("2 * x / 2", "ast_cancel_division_factor");

        assertTrue(cancellation.assumptions().isEmpty());
    }

    @Test
    void foldsExactIntegerArithmeticOnly() {
        assertGenerated("4 / 2", "2");
        assertGenerated("3 + 4", "7");
        assertFalse(rules("1 / 3").contains("ast_fold_numeric_arithmetic"),
            "an inexact division must not become a rounded literal");
        assertFalse(rules("1 / 0").contains("ast_fold_numeric_arithmetic"));
    }

    @Test
    void splitsAPerfectSquareLiteralInsideADifferenceOnly() {
        assertGenerated("x ^ 2 - 1", "x ^ 2 - 1 ^ 2");
        assertGenerated("x ^ 2 - 9", "x ^ 2 - 3 ^ 2");
        assertFalse(rules("x ^ 2 - 5").contains("ast_square_literal_split"));
        assertFalse(rules("x ^ 2 + 4").contains("ast_square_literal_split"));
        assertFalse(rules("x ^ 3 - 4").contains("ast_square_literal_split"));
    }

    private List<String> rules(String input) {
        return engine.transform(input).stream()
            .map(Transformation::rule)
            .toList();
    }

    private Transformation transformation(String input, String ruleId) {
        return engine.transform(input).stream()
            .filter(candidate -> candidate.rule().equals(ruleId))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Missing " + ruleId + " for " + input));
    }

    private void assertGenerated(String input, String expected) {
        List<String> candidates = engine.transform(input).stream()
            .map(Transformation::transformedExpression)
            .toList();
        assertTrue(candidates.contains(expected), candidates.toString());
    }
}
