package de.regelsuche.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import org.junit.jupiter.api.Test;

class RationalRulesTest {

    @Test
    void cancelCommonFactorRemovesSharedTerm() {
        RationalRules.CancelCommonFactorRule rule = new RationalRules.CancelCommonFactorRule();
        VariableExpr x = new VariableExpr("x");
        VariableExpr y = new VariableExpr("y");
        VariableExpr z = new VariableExpr("z");
        Expr fraction = new BinaryExpr(
            new BinaryExpr(x, BinaryOperator.MUL, y),
            BinaryOperator.DIV,
            new BinaryExpr(x, BinaryOperator.MUL, z)
        );
        assertTrue(rule.matches(fraction));
        Expr cancelled = rule.apply(fraction);
        assertTrue(ExpressionFormatter.format(cancelled).contains("y / z"));
    }

    @Test
    void cancelRefusesZeroDenominatorResult() {
        RationalRules.CancelCommonFactorRule rule = new RationalRules.CancelCommonFactorRule();
        VariableExpr x = new VariableExpr("x");
        Expr fraction = new BinaryExpr(
            new BinaryExpr(x, BinaryOperator.MUL, new VariableExpr("y")),
            BinaryOperator.DIV,
            new BinaryExpr(x, BinaryOperator.MUL, new NumberExpr(0))
        );
        assertFalse(rule.matches(fraction));
    }

    @Test
    void multiplyFractionsBuildsProduct() {
        RationalRules.MultiplyFractionsRule rule = new RationalRules.MultiplyFractionsRule();
        VariableExpr a = new VariableExpr("a");
        VariableExpr b = new VariableExpr("b");
        VariableExpr c = new VariableExpr("c");
        VariableExpr d = new VariableExpr("d");
        Expr product = new BinaryExpr(
            new BinaryExpr(a, BinaryOperator.DIV, b),
            BinaryOperator.MUL,
            new BinaryExpr(c, BinaryOperator.DIV, d)
        );
        assertTrue(rule.matches(product));
        Expr combined = rule.apply(product);
        String formatted = ExpressionFormatter.format(combined);
        assertTrue(formatted.contains("a * c"));
        assertTrue(formatted.contains("b * d"));
    }

    @Test
    void divideByFractionFlipsFraction() {
        RationalRules.DivideByFractionRule rule = new RationalRules.DivideByFractionRule();
        VariableExpr a = new VariableExpr("a");
        VariableExpr b = new VariableExpr("b");
        VariableExpr c = new VariableExpr("c");
        Expr quotient = new BinaryExpr(
            a,
            BinaryOperator.DIV,
            new BinaryExpr(b, BinaryOperator.DIV, c)
        );
        assertTrue(rule.matches(quotient));
        String formatted = ExpressionFormatter.format(rule.apply(quotient));
        assertTrue(formatted.contains("a * c"));
        assertTrue(formatted.contains("/ b"));
    }
}
