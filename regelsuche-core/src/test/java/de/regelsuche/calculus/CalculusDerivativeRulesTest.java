package de.regelsuche.calculus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.RewriteRule;
import org.junit.jupiter.api.Test;

class CalculusDerivativeRulesTest {

    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void powerRuleFiresOnPowerExpression() {
        Expr diff = CalculusDerivativeRules.derivative(parser.parseTerm("x^3"), "x");
        RewriteRule rule = CalculusDerivativeRules.rules().stream()
            .filter(r -> "calculus_diff_power_rule".equals(r.id()))
            .findFirst()
            .orElseThrow();
        assertTrue(rule.matches(diff), "power rule must match diff(x^3, x)");
        assertEquals("3 * x ^ 2", ExpressionFormatter.format(rule.apply(diff)));
    }

    @Test
    void sumRuleFiresOnSum() {
        Expr diff = CalculusDerivativeRules.derivative(parser.parseTerm("x + 1"), "x");
        RewriteRule rule = CalculusDerivativeRules.rules().stream()
            .filter(r -> "calculus_diff_of_sum".equals(r.id()))
            .findFirst()
            .orElseThrow();
        assertTrue(rule.matches(diff));
        // Should produce diff(x, x) + diff(1, x)
        String result = ExpressionFormatter.format(rule.apply(diff));
        assertTrue(result.contains("diff(x, x)") && result.contains("diff(1, x)"),
            "sum rule should split into two diff terms, got: " + result);
    }

    @Test
    void baseTenLogRuleRetainsChangeOfBaseFactor() {
        Expr logDiff = CalculusDerivativeRules.derivative(
            parser.parseTerm("log(x)"), "x");
        Expr lnDiff = CalculusDerivativeRules.derivative(
            parser.parseTerm("ln(x)"), "x");
        RewriteRule logRule = CalculusDerivativeRules.rules().stream()
            .filter(r -> "calculus_diff_of_log".equals(r.id()))
            .findFirst()
            .orElseThrow();
        RewriteRule lnRule = CalculusDerivativeRules.rules().stream()
            .filter(r -> "calculus_diff_of_ln".equals(r.id()))
            .findFirst()
            .orElseThrow();

        assertTrue(logRule.matches(logDiff));
        assertTrue(lnRule.matches(lnDiff));
        assertEquals("1 / (x * ln(10))",
            ExpressionFormatter.format(logRule.apply(logDiff)));
        assertEquals("1 / x", ExpressionFormatter.format(lnRule.apply(lnDiff)));
        assertNotEquals(logRule.apply(logDiff), lnRule.apply(lnDiff));
    }
}
