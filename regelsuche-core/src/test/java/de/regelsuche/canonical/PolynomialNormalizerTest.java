package de.regelsuche.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import org.junit.jupiter.api.Test;

class PolynomialNormalizerTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final PolynomialNormalizer normalizer = new PolynomialNormalizer();

    @Test
    void collectsGlobalLikeTermsAfterExpansion() {
        assertEquals("x ^ 2 + 3 * x + 2", normalize("x*x + x*2 + x + 2"));
    }

    @Test
    void combinesMultivariateMonomials() {
        assertEquals("2 * x ^ 2 * y + y", normalize("x*y*x + y + y*x^2"));
    }

    @Test
    void normalizesSafeIntegerPowersOfMonomials() {
        assertEquals("8 * x ^ 3", normalize("(2*x)^3"));
    }

    @Test
    void rejectsUnsupportedOperatorsAndNonIntegerPowers() {
        assertTrue(normalizer.normalize(parse("x / y + x")).isEmpty());
        assertTrue(normalizer.normalize(parse("x ^ 0.5")).isEmpty());
        assertTrue(normalizer.normalize(parse("(x + 1)^2")).isEmpty());
        assertTrue(normalizer.normalize(parse("sin(x) + x")).isEmpty());
    }

    private String normalize(String expression) {
        return ExpressionFormatter.format(normalizer.normalize(parse(expression)).orElseThrow());
    }

    private Expr parse(String expression) {
        return parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
    }
}
