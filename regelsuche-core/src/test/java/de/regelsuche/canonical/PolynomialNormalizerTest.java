package de.regelsuche.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionContext;
import de.regelsuche.ast.Expr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class PolynomialNormalizerTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final PolynomialNormalizer normalizer = new PolynomialNormalizer();

    @Test
    void collectsGlobalLikeTermsAfterExpansion() {
        assertEquals("x ^ 2 + 3 * x + 2", normalize("x*x + x*2 + x + 2"));
        assertEquals("6 * x", normalize("3*x + x*3"));
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
    void expandsPolynomialProductsAndPowers() {
        assertEquals("x ^ 2 - 1", normalize("(x + 1) * (x - 1)"));
        assertEquals("x ^ 2 + 2 * x + 1", normalize("(x + 1)^2"));
    }

    @Test
    void combinesDecimalCoefficientsExactly() {
        assertEquals("0.3 * x", normalize("0.1*x + 0.2*x"));
        assertEquals("0", normalize("0.3*x - 0.1*x - 0.2*x"));
    }

    @Test
    void rejectsUnsupportedOperatorsAndNonIntegerPowers() {
        assertTrue(normalizer.normalize(parse("x / y + x")).isEmpty());
        assertTrue(normalizer.normalize(parse("x ^ 0.5")).isEmpty());
        assertTrue(normalizer.normalize(parse("sin(x) + x")).isEmpty());
    }

    @Test
    void zeroPowersRemainOutsideAssumptionFreePolynomialNormalization() {
        assertTrue(normalizer.normalize(parse("x^0")).isEmpty());
        assertTrue(normalizer.normalize(parse("0^0")).isEmpty());
        assertTrue(normalizer.normalize(parse("x^0 + y")).isEmpty());
    }

    @Test
    void zeroPowerCancellationRequiresNonZeroBase() {
        ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
        assertNotEquals(
            canonicalizer.stableHash("x^0 - x^0"),
            canonicalizer.stableHash("0"));

        AssumptionContext context = new AssumptionContext();
        assertEquals("0", canonicalizer.canonicalizeWith("x^0 - x^0", context));
        assertTrue(context.snapshot().stream().anyMatch(
            assumption -> assumption.kind() == Assumption.Kind.NON_ZERO
                && assumption.expression().equals("x != 0")));
    }

    @Test
    @Timeout(10)
    void exponentSumOverflowDoesNotCorruptCanonicalIdentity() {
        ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
        for (String base : List.of("x", "sin(x)", "(1/x)")) {
            String largePower = base + "^2147483647";
            for (String source : List.of(
                    largePower + " * " + base,
                    largePower + " * " + largePower + " * " + base + " * " + base,
                    largePower + " * " + largePower + " * " + base + "^3")) {
                Expr parsed = parse(source);
                Expr canonical = canonicalizer.canonicalize(parsed);
                assertEquals(parsed, canonical,
                    "an unsupported exponent sum must retain its factors: " + source);
                String rendered = ExpressionFormatter.format(canonical);
                assertEquals(rendered, canonicalizer.canonicalize(rendered));
                assertNotEquals(canonicalizer.stableHash(source), canonicalizer.stableHash("1"));
                assertNotEquals(canonicalizer.stableHash(source), canonicalizer.stableHash(base));
            }
        }
    }

    @Test
    @Timeout(10)
    void exponentSumAtTheIntegerLimitStillCombinesExactly() {
        ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
        for (String base : List.of("x", "sin(x)", "(1/x)")) {
            String expected = canonicalizer.canonicalize(base + "^2147483647");
            assertEquals(expected, canonicalizer.canonicalize(base + "^2147483646 * " + base));
            assertEquals(expected, canonicalizer.canonicalize(base + " * " + base + "^2147483646"));
        }
    }

    @Test
    @Timeout(10)
    void exponentOverflowFallbackPreservesExistingAssumptions() {
        ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
        Assumption existing = Assumption.positive("z");
        for (String factor : List.of("y/y", "y^0", "sin(2*(y/y))")) {
            String source = "(" + factor + ") * x^2147483647 * x";
            AssumptionContext context = new AssumptionContext();
            context.add(existing);
            Expr parsed = parse(source);
            assertEquals(parsed, canonicalizer.canonicalize(parsed, context));
            assertEquals(List.of(existing), context.snapshot(),
                "discarded factor reductions must not contribute assumptions: " + source);

            AssumptionContext hashContext = new AssumptionContext();
            assertEquals(canonicalizer.stableHash(source),
                canonicalizer.stableHashWith(source, hashContext));
            assertTrue(hashContext.isEmpty());
        }
    }

    @Test
    @Timeout(10)
    void exponentOverflowFallbackKeepsAssumptionsFromSuccessfulSiblings() {
        ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
        String product = "(y/y) * x^2147483647 * x";
        Expr expected = parse(product + " + 1");
        for (String source : List.of("z/z + " + product, product + " + z/z")) {
            AssumptionContext context = new AssumptionContext();
            assertEquals(expected, canonicalizer.canonicalize(parse(source), context));
            assertEquals(List.of(Assumption.nonZero("z")), context.snapshot());
        }
    }

    @Test
    @Timeout(10)
    void successfulMultiplicationCommitsAndDeduplicatesAssumptions() {
        ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
        Assumption existing = Assumption.positive("z");
        for (String factor : List.of("y/y", "y^0")) {
            String source = "(" + factor + ") * x^2147483646 * x";
            AssumptionContext context = new AssumptionContext();
            context.add(existing);
            for (int attempt = 0; attempt < 2; attempt++) {
                assertEquals("x ^ 2147483647", canonicalizer.canonicalizeWith(source, context));
                assertEquals(List.of(existing, Assumption.nonZero("y")), context.snapshot());
            }
        }
    }

    @Test
    @Timeout(10)
    void totalDegreeOrderingDoesNotOverflowAcrossVariables() {
        String highDegree = "x ^ 2147483647 * y ^ 2147483647";
        String expected = highDegree + " + x ^ 2147483647 * y + x";
        String source = "x + x^2147483647*y + " + highDegree;
        assertEquals(expected, normalize(source));

        ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
        assertEquals(expected, canonicalizer.canonicalize(source));
        // An opaque function forces the general addition fallback, which
        // must use the same non-overflowing total-degree ordering.
        assertEquals(expected + " + sin(z)", canonicalizer.canonicalize("sin(z) + " + source));
    }

    private String normalize(String expression) {
        return ExpressionFormatter.format(normalizer.normalize(parse(expression)).orElseThrow());
    }

    private Expr parse(String expression) {
        return parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
    }
}
