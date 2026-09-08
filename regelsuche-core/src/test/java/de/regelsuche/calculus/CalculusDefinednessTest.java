package de.regelsuche.calculus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class CalculusDefinednessTest {
    private final ExpressionParser parser = new ExpressionParser();

    @ParameterizedTest
    @ValueSource(strings = {"0 / 0", "0 / x", "0 * (1 / x)", "0 * log(x)", "0 * mystery(x)", "(1 / 0) * 0"})
    void simplifyingZeroDoesNotRemoveUndefinedInputs(String expression) {
        assertNotEquals(new NumberExpr(0), Differentiator.simplify(parser.parseTerm(expression)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0 ^ 0", "x ^ 0", "log(x) ^ 0", "1 ^ (1 / 0)", "1 ^ log(x)"})
    void simplifyingPowersDoesNotRemoveUndefinedInputs(String expression) {
        assertNotEquals(new NumberExpr(1), Differentiator.simplify(parser.parseTerm(expression)));
    }

    @Test
    void ordinaryTotalOperationsStillSimplify() {
        for (String expression : new String[] {"0 * x", "0 * sin(x)", "0 / 2"}) {
            assertEquals(new NumberExpr(0), Differentiator.simplify(parser.parseTerm(expression)));
        }
        for (String expression : new String[] {"2 ^ 0", "1 ^ x", "1 ^ sin(x)"}) {
            assertEquals(new NumberExpr(1), Differentiator.simplify(parser.parseTerm(expression)));
        }
    }

    @Test
    void derivativeOfLinearPowerDoesNotIntroduceAnExcludedZero() {
        var derivative = new Differentiator().differentiate(parser.parseTerm("x ^ 1"), "x");
        assertEquals(new NumberExpr(1), derivative);
    }

    @Test
    void integrationPreservesAnUndefinedDenominator() {
        var integral = new Integrator().integrate(parser.parseTerm("0 / 0"), "x").orElseThrow();
        assertNotEquals("0", ExpressionFormatter.format(integral));
    }
}
