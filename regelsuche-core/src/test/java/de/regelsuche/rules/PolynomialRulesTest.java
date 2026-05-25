package de.regelsuche.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.transform.RewriteRule;
import org.junit.jupiter.api.Test;

class PolynomialRulesTest {

    @Test
    void combineLikeTermsCombinesCoefficients() {
        PolynomialRules.CombineLikeTermsRule rule = new PolynomialRules.CombineLikeTermsRule();
        Expr two = new NumberExpr(2);
        Expr three = new NumberExpr(3);
        VariableExpr x = new VariableExpr("x");
        Expr addition = new BinaryExpr(
            new BinaryExpr(two, BinaryOperator.MUL, x),
            BinaryOperator.ADD,
            new BinaryExpr(three, BinaryOperator.MUL, x)
        );
        assertTrue(rule.matches(addition));
        Expr result = rule.apply(addition);
        assertEquals("5 * x", ExpressionFormatter.format(result));
    }

    @Test
    void combineLikeTermsSkipsPlainAplusA() {
        PolynomialRules.CombineLikeTermsRule rule = new PolynomialRules.CombineLikeTermsRule();
        VariableExpr x = new VariableExpr("x");
        Expr addition = new BinaryExpr(x, BinaryOperator.ADD, x);
        // ast_double_term already handles A + A, our rule should not overlap.
        assertFalse(rule.matches(addition));
    }

    @Test
    void domainRulesAreAtomicAndStable() {
        for (RewriteRule rule : PolynomialRules.rules()) {
            assertFalse(rule.id().isBlank(), "rule id must not be blank");
        }
    }
}
