package de.regelsuche.calculus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionEvaluatorPortfolio;
import de.regelsuche.assumption.AssumptionTruthValue;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DerivativeDomainTest {
    private final ExpressionParser parser = new ExpressionParser();

    @ParameterizedTest
    @ValueSource(strings = {"ln", "log"})
    void logarithmRewriteRetainsItsPositiveRealDomain(String function) {
        Expr source = derivative(function + "(x)");
        RewriteRule rule = rule("calculus_diff_of_" + function);

        assertTrue(rule.matches(source));
        assertEquals(List.of(Assumption.positive("x")), rule.assumptions(source));
        assertTrue(rule.mayEmitAssumptions());
    }

    @Test
    void compositionalRewriteRetainsActualNestedLogArgumentsAndTheirDomains() {
        Expr source = derivative("ln(x^2 + 1) + log(1/y)");

        assertEquals(List.of(Assumption.positive("x ^ 2 + 1"),
            Assumption.nonZero("y"), Assumption.positive("1 / y")),
            rule("calculus_diff_of_sum").assumptions(source));
    }

    @Test
    void productRetainsTheOriginalQuotientDenominator() {
        Expr source = derivative("(0 / (x - 1)) * x");

        assertEquals(List.of(Assumption.nonZero("x - 1")),
            rule("calculus_diff_of_product").assumptions(source));
    }

    @Test
    void symbolicPowersRetainPositiveBasesThroughNestedFunctions() {
        Expr source = derivative("ln((1/x)^y) + 1");

        assertEquals(List.of(Assumption.nonZero("x"), Assumption.positive("1 / x"),
            Assumption.positive("(1 / x) ^ y")),
            rule("calculus_diff_of_sum").assumptions(source));
    }

    @Test
    void fractionalPowersDeclareTheirConservativePositiveDomain() {
        Expr source = derivative("x^0.5");

        assertEquals(List.of(Assumption.positive("x")),
            rule("calculus_diff_power_rule").assumptions(source));
    }

    @Test
    void zeroPowerRetainsItsExcludedZero() {
        assertEquals(List.of(Assumption.nonZero("x")),
            rule("calculus_diff_power_rule").assumptions(derivative("x^0")));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -2, -3})
    void parsedNegativeIntegerPowersReachTheRuleWithTheirOriginalDomain(int exponent) {
        RewriteRule rule = rule("calculus_diff_power_rule");
        Expr source = derivative("x^" + exponent);

        assertTrue(rule.matches(source));
        assertEquals(List.of(Assumption.nonZero("x")), rule.assumptions(source));
        assertEquals(new BinaryExpr(new NumberExpr(exponent), BinaryOperator.MUL,
            new BinaryExpr(parser.parseTerm("x"), BinaryOperator.POW, new NumberExpr(exponent - 1))),
            rule.apply(source));
    }

    @Test
    void parsedNegativeFractionalPowerKeepsItsStrongerPositiveDomain() {
        RewriteRule rule = rule("calculus_diff_power_rule");
        Expr source = derivative("x^-0.5");

        assertTrue(rule.matches(source));
        assertEquals(List.of(Assumption.positive("x")), rule.assumptions(source));
        assertEquals(new BinaryExpr(new NumberExpr(ExactRational.parse("-0.5")), BinaryOperator.MUL,
            new BinaryExpr(parser.parseTerm("x"), BinaryOperator.POW,
                new NumberExpr(ExactRational.parse("-1.5")))),
            rule.apply(source));
    }

    @ParameterizedTest
    @ValueSource(strings = {"x^(1 - 2)", "x^y", "y^-2"})
    void literalPowerDispatchDoesNotExpandIntoOtherExponentOrBaseCases(String body) {
        assertFalse(rule("calculus_diff_power_rule").matches(derivative(body)));
    }

    @Test
    void linearPowerDoesNotIntroduceAnExcludedZero() {
        RewriteRule rule = rule("calculus_diff_power_rule");
        Expr source = derivative("x^1");

        assertEquals(new NumberExpr(1), rule.apply(source));
        assertEquals(List.of(), rule.assumptions(source));
    }

    @Test
    void splittingNonsmoothCancellationRequiresDifferentiableOperands() {
        Expr source = derivative("abs(x) - abs(x)");
        RewriteRule rule = rule("calculus_diff_of_difference");

        assertTrue(rule.matches(source));
        assertEquals(List.of(Assumption.customPredicate("differentiable(abs(x), x)",
            List.of("abs(x)", "x"))), rule.assumptions(source));
    }

    @Test
    void continuityCannotDischargeTheDifferentiabilityGuard() {
        var required = rule("calculus_diff_of_difference")
            .assumptions(derivative("abs(x) - abs(x)")).getFirst();
        var continuous = Assumption.customPredicate("continuous(abs(x), x)", List.of("abs(x)", "x"));
        var evaluator = AssumptionEvaluatorPortfolio.localOnly();

        assertEquals(AssumptionTruthValue.UNKNOWN, evaluator.evaluate(required, List.of(continuous)).result());
        assertTrue(evaluator.evaluate(required, List.of(required)).isSatisfied());
    }

    @Test
    void arbitraryUnknownFunctionsDoNotBecomeUnconditionalProductRules() {
        assertEquals(List.of(Assumption.customPredicate("differentiable(mystery(x), x)",
            List.of("mystery(x)", "x"))),
            rule("calculus_diff_of_product").assumptions(derivative("mystery(x) * x")));
    }

    @Test
    void ordinaryTransformationProvenanceBindsTheMatchedSubtree() {
        var transformation = new AstRewriteTransformationEngine(CalculusDerivativeRules.rules())
            .transform("q + diff(log(y), y)").getFirst();

        assertEquals("q + 1 / (y * ln(10))", transformation.transformedExpression());
        assertEquals(List.of("y > 0"), transformation.assumptions());
        assertTrue(transformation.equivalencePreservingByConstruction());
    }

    private Expr derivative(String body) {
        return CalculusDerivativeRules.derivative(parser.parseTerm(body), "x");
    }

    private RewriteRule rule(String id) {
        return CalculusDerivativeRules.rules().stream().filter(rule -> id.equals(rule.id()))
            .findFirst().orElseThrow();
    }
}
