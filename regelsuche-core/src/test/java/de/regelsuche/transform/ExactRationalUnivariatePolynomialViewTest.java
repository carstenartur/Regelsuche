package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactRationalUnivariatePolynomialViewTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final ExactRationalUnivariatePolynomialView view =
        new ExactRationalUnivariatePolynomialView();

    @Test
    void extractsExactDecimalAndFractionCoefficientsFromProvenance() {
        var parsed = parser.parseExactTerm(
            "0.10*x^4 - (3/4)*x^2 + 2");
        var analysis = view.analyze(parsed);

        assertTrue(analysis.supported(), analysis.detailCode());
        assertEquals("x", analysis.variable());
        var polynomial = analysis.polynomial().orElseThrow();
        assertEquals(4, polynomial.degree());
        assertEquals(ExactRational.integer(2), polynomial.coefficient(0));
        assertEquals(ExactRational.ZERO, polynomial.coefficient(1));
        assertEquals(rational(-3, 4), polynomial.coefficient(2));
        assertEquals(ExactRational.ZERO, polynomial.coefficient(3));
        assertEquals(rational(1, 10), polynomial.coefficient(4));
        assertEquals(
            List.of("0.10", "4", "3", "4", "2", "2"),
            analysis.literals().stream()
                .map(ExactRationalUnivariatePolynomialView
                    .LiteralBinding::sourceLexeme)
                .toList());
        assertTrue(analysis.literals().stream().allMatch(binding ->
            binding.certificateHash().matches("sha256:[0-9a-f]{64}")));
        assertFalse(analysis.canonicalMaterial().isBlank());
        assertEquals(
            analysis.canonicalMaterial(),
            view.analyze(parsed).canonicalMaterial());
    }

    @Test
    void composesProductsPowersAndConstantDivisionExactly() {
        var analysis = view.analyze(
            parser.parseExactTerm("(x + 1/2)^2 / 2"));

        assertTrue(analysis.supported(), analysis.detailCode());
        var polynomial = analysis.polynomial().orElseThrow();
        assertEquals(rational(1, 8), polynomial.coefficient(0));
        assertEquals(rational(1, 2), polynomial.coefficient(1));
        assertEquals(rational(1, 2), polynomial.coefficient(2));
    }

    @Test
    void treatsParserUnaryMinusAsExactNegationNotAsAProvenanceHole() {
        var analysis = view.analyze(parser.parseExactTerm("-x + 0.25"));

        assertTrue(analysis.supported(), analysis.detailCode());
        var polynomial = analysis.polynomial().orElseThrow();
        assertEquals(rational(1, 4), polynomial.coefficient(0));
        assertEquals(ExactRational.NEGATIVE_ONE, polynomial.coefficient(1));
        assertEquals(1, analysis.literals().size());
        assertEquals("0.25", analysis.literals().getFirst().sourceLexeme());
    }

    @Test
    void rejectsFragmentsOutsideTheDeclaredCommutativeUnivariateDomain() {
        assertFailure(
            "x + y",
            ExactRationalUnivariatePolynomialView.Status.UNSUPPORTED,
            "MULTIPLE_POLYNOMIAL_VARIABLES");
        assertFailure(
            "1 / x",
            ExactRationalUnivariatePolynomialView.Status.UNSUPPORTED,
            "DIVISOR_MUST_BE_EXACT_NONZERO_CONSTANT");
        assertFailure(
            "sin(x)",
            ExactRationalUnivariatePolynomialView.Status.UNSUPPORTED,
            "FUNCTION_EXPRESSION_OUTSIDE_UNIVARIATE_VIEW");
        assertFailure(
            "x^17",
            ExactRationalUnivariatePolynomialView.Status.BUDGET_EXCEEDED,
            "MAX_DEGREE_EXCEEDED");
    }

    @Test
    void exposesDeterministicVisitedAndArithmeticBudgetFailures() {
        var arithmeticView = new ExactRationalUnivariatePolynomialView(
            new ExactRationalUnivariatePolynomialView.Budget(
                16,
                4_096,
                512,
                1));
        var arithmetic = arithmeticView.analyze(
            parser.parseExactTerm("x + x"));
        var visitedView = new ExactRationalUnivariatePolynomialView(
            new ExactRationalUnivariatePolynomialView.Budget(
                16,
                4_096,
                1,
                10_000));
        var visited = visitedView.analyze(
            parser.parseExactTerm("x + 1"));
        var coefficientView = new ExactRationalUnivariatePolynomialView(
            new ExactRationalUnivariatePolynomialView.Budget(
                16,
                4,
                512,
                10_000));
        var coefficient = coefficientView.analyze(
            parser.parseExactTerm("16"));

        assertEquals(
            ExactRationalUnivariatePolynomialView.Status.BUDGET_EXCEEDED,
            arithmetic.status());
        assertEquals(
            "MAX_ARITHMETIC_OPERATIONS_EXCEEDED",
            arithmetic.detailCode());
        assertEquals(1, arithmetic.work().arithmeticOperations());
        assertEquals(
            "MAX_VISITED_NODES_EXCEEDED",
            visited.detailCode());
        assertEquals(1, visited.work().visitedNodes());
        assertEquals(
            "COEFFICIENT_BIT_LIMIT_EXCEEDED",
            coefficient.detailCode());
    }

    private void assertFailure(
        String expression,
        ExactRationalUnivariatePolynomialView.Status status,
        String detailCode
    ) {
        var analysis = view.analyze(parser.parseExactTerm(expression));
        assertEquals(status, analysis.status());
        assertEquals(detailCode, analysis.detailCode());
        assertTrue(analysis.polynomial().isEmpty());
        assertTrue(analysis.variable().isEmpty());
    }

    private static ExactRational rational(long numerator, long denominator) {
        return new ExactRational(
            BigInteger.valueOf(numerator),
            BigInteger.valueOf(denominator));
    }
}
