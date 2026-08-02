package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionParser;
import org.junit.jupiter.api.Test;

class UnivariatePolynomialTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void dividesExactlyAtTheMaximumAcceptedCoefficient() {
        UnivariatePolynomial dividend = UnivariatePolynomial.of(
            parser.parseTerm(
                "1000000000000 * x + 1000000000000"));
        UnivariatePolynomial divisor = UnivariatePolynomial.of(
            parser.parseTerm("x + 1"));
        assertNotNull(dividend);
        assertNotNull(divisor);

        UnivariatePolynomial quotient = dividend.divideExactly(divisor);

        assertNotNull(quotient);
        assertEquals(
            new NumberExpr(1_000_000_000_000d),
            quotient.toExpression());
    }

    @Test
    void rejectsANonExactDivisionAfterAnIntermediateProductBeyondDoublePrecision() {
        UnivariatePolynomial dividend = UnivariatePolynomial.of(
            parser.parseTerm(
                "1000000000000 * x ^ 2 + 1000000000000 * x + 1"));
        UnivariatePolynomial divisor = UnivariatePolynomial.of(
            parser.parseTerm(
                "10000 * x ^ 2 + 100000000 * x + 1000000000000"));
        assertNotNull(dividend);
        assertNotNull(divisor);

        // The first long-division step multiplies 100,000,000 by
        // 1,000,000,000,000. That 10^20 intermediate is not exactly
        // representable as a double, but the integer remainder must stay exact.
        assertNull(dividend.divideExactly(divisor));
    }
}
