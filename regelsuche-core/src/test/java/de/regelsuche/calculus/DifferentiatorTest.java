package de.regelsuche.calculus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DifferentiatorTest {

    private final ExpressionParser parser = new ExpressionParser();
    private final Differentiator differentiator = new Differentiator();
    private final Integrator integrator = new Integrator();

    private Expr parse(String text) {
        return parser.parse(new InputRequest(InputType.TERM, text)).terms().get(0);
    }

    @Test
    void derivativePowerRuleWorks() {
        Expr derivative = differentiator.differentiate(parse("x^3"), "x");
        assertEquals("3 * x ^ 2", ExpressionFormatter.format(derivative));
    }

    @Test
    void sumProductAndStandardFunctionsAreSupported() {
        Expr derivative = differentiator.differentiate(parse("x^2 + sin(x)"), "x");
        assertEquals("2 * x + cos(x)", ExpressionFormatter.format(derivative));

        Expr exp = differentiator.differentiate(parse("exp(x)"), "x");
        assertEquals("exp(x)", ExpressionFormatter.format(exp));

        Expr ln = differentiator.differentiate(parse("ln(x)"), "x");
        assertEquals(parse("1 / x"), ln);

        Expr product = differentiator.differentiate(parse("x * sin(x)"), "x");
        assertEquals("sin(x) + x * cos(x)", ExpressionFormatter.format(product));
    }

    @Test
    void baseTenLogDerivativeRetainsItsChangeOfBaseFactor() {
        Expr derivative = differentiator.differentiate(parse("log(x)"), "x");

        assertEquals(parse("1 / (x * ln(10))"), derivative);
        assertNotEquals(
            differentiator.differentiate(parse("ln(x)"), "x"),
            derivative);
    }

    @Test
    void baseTenLogChainRuleRetainsItsChangeOfBaseFactor() {
        Expr derivative = differentiator.differentiate(
            parse("log(x^2 + 1)"), "x");

        assertEquals(
            parse("(1 / ((x^2 + 1) * ln(10))) * (2 * x)"),
            derivative);
    }

    @Test
    void integratorRecoversPowerRule() {
        Optional<Expr> integral = integrator.integrate(parse("x^2"), "x");
        assertTrue(integral.isPresent());
        assertEquals("x ^ 3 / 3", ExpressionFormatter.format(integral.get()));

        Optional<Expr> sin = integrator.integrate(parse("sin(x)"), "x");
        assertTrue(sin.isPresent());
        assertEquals("0 - cos(x)", ExpressionFormatter.format(sin.get()));
    }

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
