package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactParsedUnivariatePolynomialViewTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final ExactParsedUnivariatePolynomialView view =
        new ExactParsedUnivariatePolynomialView();

    @Test
    void extractsExactSourceCoefficientsIntoTheAuthoritativeRing() {
        var parsed = parser.parseExactTerm(
            "0.10*x^4 - (3/4)*x^2 + 2");
        var analysis = view.analyze(parsed);

        assertTrue(analysis.supported(), analysis.detailCode());
        SparsePolynomial<ExactRational> polynomial =
            analysis.polynomial().orElseThrow();
        assertEquals(
            ExactRationalField.DOMAIN_ID,
            polynomial.ring().coefficientDomain().id());
        assertEquals(
            List.of(new PolynomialVariable("x")),
            polynomial.ring().variables());
        assertEquals(4, polynomial.totalDegree());
        assertEquals(ExactRational.integer(2), polynomial.coefficient(0));
        assertEquals(ExactRational.ZERO, polynomial.coefficient(1));
        assertEquals(rational(-3, 4), polynomial.coefficient(2));
        assertEquals(ExactRational.ZERO, polynomial.coefficient(3));
        assertEquals(rational(1, 10), polynomial.coefficient(4));
        assertEquals(
            List.of("0.10", "4", "3", "4", "2", "2"),
            analysis.literals().stream()
                .map(ExactParsedUnivariatePolynomialView
                    .LiteralBinding::sourceLexeme)
                .toList());
        assertTrue(analysis.literals().stream().allMatch(binding ->
            binding.certificateHash().matches("sha256:[0-9a-f]{64}")));
        assertTrue(
            analysis.certificateHash().matches("sha256:[0-9a-f]{64}"));
        assertEquals(
            analysis.canonicalMaterial(),
            view.analyze(parsed).canonicalMaterial());
    }

    @Test
    void composesProductsPowersAndConstantDivisionExactly() {
        var analysis = view.analyze(
            parser.parseExactTerm("(x + 1/2)^2 / 2"));

        assertTrue(analysis.supported(), analysis.detailCode());
        SparsePolynomial<ExactRational> polynomial =
            analysis.polynomial().orElseThrow();
        assertEquals(rational(1, 8), polynomial.coefficient(0));
        assertEquals(rational(1, 2), polynomial.coefficient(1));
        assertEquals(rational(1, 2), polynomial.coefficient(2));
    }

    @Test
    void exponentiationBySquaringFitsATighterArithmeticBudget() {
        var boundedView = new ExactParsedUnivariatePolynomialView(
            new ExactParsedUnivariatePolynomialView.Budget(
                16,
                4_096,
                512,
                300));
        var analysis = boundedView.analyze(parser.parseExactTerm("x^16"));

        assertTrue(analysis.supported(), analysis.detailCode());
        assertEquals(
            16,
            analysis.polynomial().orElseThrow().totalDegree());
        assertEquals(272, analysis.work().arithmeticOperations());
    }

    @Test
    void preservesTheDeclaredVariableForAZeroPower() {
        var analysis = view.analyze(parser.parseExactTerm("x^0"));

        assertTrue(analysis.supported(), analysis.detailCode());
        SparsePolynomial<ExactRational> polynomial =
            analysis.polynomial().orElseThrow();
        assertEquals(
            List.of(new PolynomialVariable("x")),
            polynomial.ring().variables());
        assertEquals(0, polynomial.totalDegree());
        assertEquals(ExactRational.ONE, polynomial.coefficient(0));
    }

    @Test
    void treatsParserUnaryMinusAsExactNegation() {
        var analysis = view.analyze(parser.parseExactTerm("-x + 0.25"));

        assertTrue(analysis.supported(), analysis.detailCode());
        SparsePolynomial<ExactRational> polynomial =
            analysis.polynomial().orElseThrow();
        assertEquals(rational(1, 4), polynomial.coefficient(0));
        assertEquals(ExactRational.NEGATIVE_ONE, polynomial.coefficient(1));
        assertEquals(1, analysis.literals().size());
        assertEquals("0.25", analysis.literals().getFirst().sourceLexeme());
    }

    @Test
    void enforcesTheDegreeLimitForABareVariable() {
        var constantOnlyView = new ExactParsedUnivariatePolynomialView(
            new ExactParsedUnivariatePolynomialView.Budget(
                0,
                4_096,
                512,
                10_000));

        var variable = constantOnlyView.analyze(parser.parseExactTerm("x"));
        var constant = constantOnlyView.analyze(parser.parseExactTerm("2"));

        assertEquals(
            ExactParsedUnivariatePolynomialView.Status.BUDGET_INCONCLUSIVE,
            variable.status());
        assertEquals("MAX_DEGREE_EXCEEDED", variable.detailCode());
        assertTrue(variable.polynomial().isEmpty());
        assertTrue(constant.supported(), constant.detailCode());
        assertEquals(
            ExactRational.integer(2),
            constant.polynomial().orElseThrow()
                .coefficient(Monomial.one(0)));
    }

    @Test
    void rejectsFragmentsOutsideTheDeclaredUnivariateDomain() {
        assertFailure(
            "x + y",
            ExactParsedUnivariatePolynomialView.Status.UNSUPPORTED,
            "MULTIPLE_POLYNOMIAL_VARIABLES");
        assertFailure(
            "1 / x",
            ExactParsedUnivariatePolynomialView.Status.UNSUPPORTED,
            "DIVISOR_MUST_BE_EXACT_NONZERO_CONSTANT");
        assertFailure(
            "sin(x)",
            ExactParsedUnivariatePolynomialView.Status.UNSUPPORTED,
            "FUNCTION_EXPRESSION_OUTSIDE_UNIVARIATE_VIEW");
        assertFailure(
            "x^17",
            ExactParsedUnivariatePolynomialView.Status.BUDGET_INCONCLUSIVE,
            "MAX_DEGREE_EXCEEDED");
    }

    @Test
    void exposesDeterministicWorkAndRepresentationFailures() {
        var arithmeticView = new ExactParsedUnivariatePolynomialView(
            new ExactParsedUnivariatePolynomialView.Budget(
                16,
                4_096,
                512,
                1));
        var arithmetic = arithmeticView.analyze(
            parser.parseExactTerm("x + x"));
        var visitedView = new ExactParsedUnivariatePolynomialView(
            new ExactParsedUnivariatePolynomialView.Budget(
                16,
                4_096,
                1,
                10_000));
        var visited = visitedView.analyze(
            parser.parseExactTerm("x + 1"));
        var coefficientView = new ExactParsedUnivariatePolynomialView(
            new ExactParsedUnivariatePolynomialView.Budget(
                16,
                4,
                512,
                10_000));
        var coefficient = coefficientView.analyze(
            parser.parseExactTerm("16"));

        assertEquals(
            ExactParsedUnivariatePolynomialView.Status.BUDGET_INCONCLUSIVE,
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
        assertFalse(coefficient.supported());
    }

    private void assertFailure(
        String expression,
        ExactParsedUnivariatePolynomialView.Status status,
        String detailCode
    ) {
        var analysis = view.analyze(parser.parseExactTerm(expression));
        assertEquals(status, analysis.status());
        assertEquals(detailCode, analysis.detailCode());
        assertTrue(analysis.polynomial().isEmpty());
    }

    private static ExactRational rational(long numerator, long denominator) {
        return new ExactRational(
            BigInteger.valueOf(numerator),
            BigInteger.valueOf(denominator));
    }
}
