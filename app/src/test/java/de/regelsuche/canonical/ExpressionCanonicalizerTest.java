package de.regelsuche.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionContext;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.plugin.AstVisitorContext;
import de.regelsuche.validation.RandomExpressionGenerator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExpressionCanonicalizerTest {
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void canonicalizesCommutativeExpressions() {
        assertEquals(canonicalizer.stableHash("a + b"), canonicalizer.stableHash("b + a"));
        assertEquals("x", canonicalizer.canonicalize("x*1"));
        assertEquals("x + y + z", canonicalizer.canonicalize("(x+y)+z"));
        assertEquals("x ^ 2", canonicalizer.canonicalize("x*x"));
    }

    @Test
    void associativeAndCommutativeVariantsCollapseToSameHash() {
        assertEquals(canonicalizer.stableHash("(a+b)+c"), canonicalizer.stableHash("a+(b+c)"));
        assertEquals(canonicalizer.stableHash("a*b"), canonicalizer.stableHash("b*a"));
        assertEquals(canonicalizer.stableHash("(a*b)*c"), canonicalizer.stableHash("a*(b*c)"));
        assertEquals(canonicalizer.stableHash("(a*b)*c"), canonicalizer.stableHash("c*a*b"));
    }

    @Test
    void currentExprIdentityIsStructuralAndParserDoesNotInternValues() {
        Expr first = parser.parseTerm("a");
        Expr second = parser.parseTerm("a");
        assertEquals(first, second, "VariableExpr equality is structural");
        assertNotSame(first, second, "the syntax parser does not intern Expr values");

        Expr leftGrouped = parser.parseTerm("(a + b) + c");
        Expr rightGrouped = parser.parseTerm("a + (b + c)");
        assertNotEquals(leftGrouped, rightGrouped,
            "binary grouping participates in current Expr equality");
        assertEquals(canonicalizer.stableHash("(a + b) + c"),
            canonicalizer.stableHash("a + (b + c)"),
            "canonical value identity removes associative grouping");

        Expr forward = parser.parseTerm("a + b");
        Expr reversed = parser.parseTerm("b + a");
        assertNotEquals(forward, reversed,
            "operand order participates in current Expr equality");
        assertEquals(canonicalizer.stableHash("a + b"), canonicalizer.stableHash("b + a"));
    }

    @Test
    void repeatedSyntaxOccurrencesKeepIndependentReferenceIdentity() {
        BinaryExpr sum = (BinaryExpr) parser.parseTerm("a + a");
        assertEquals(sum.left(), sum.right());
        assertNotSame(sum.left(), sum.right());

        AstVisitorContext context = new AstVisitorContext();
        context.putMetadata(sum.left(), "side", "left");
        context.putMetadata(sum.right(), "side", "right");

        assertEquals("left", context.metadata(sum.left()).get("side"));
        assertEquals("right", context.metadata(sum.right()).get("side"));
    }

    @Test
    void normalSetPreservesDistinctOccurrencesThatReferenceOneValue() {
        Expr value = new VariableExpr("a");
        Set<Use> uses = new LinkedHashSet<>();
        uses.add(new Use(1, value));
        uses.add(new Use(2, value));

        assertEquals(2, uses.size());
        List<Use> ordered = uses.stream().toList();
        assertSame(ordered.get(0).value(), ordered.get(1).value(),
            "occurrence identity and mathematical value identity are independent");
    }

    @Test
    void binaryExprMayShareAChildButStructuralEqualityDoesNotExposeSharing() {
        Expr shared = new VariableExpr("a");
        BinaryExpr dag = new BinaryExpr(shared, BinaryOperator.ADD, shared);

        assertSame(dag.left(), dag.right());
        assertEquals(parser.parseTerm("a + a"), dag,
            "record equality cannot distinguish one shared value from two equal occurrences");
    }

    @Test
    void numericConstantsAreFolded() {
        assertEquals("5", canonicalizer.canonicalize("2 + 3"));
        assertEquals("12", canonicalizer.canonicalize("3 * 4"));
        assertEquals(canonicalizer.canonicalize("x + 5"), canonicalizer.canonicalize("2 + x + 3"));
    }

    @Test
    void nonPolynomialDecimalCoefficientsNeverNarrowToIntegers() {
        assertEquals("0.5 * sin(x)", canonicalizer.canonicalize("0.5*sin(x)"));
        assertEquals("sin(x)", canonicalizer.canonicalize("0.5*sin(x) + 0.5*sin(x)"));
        assertEquals("2 * sin(x)", canonicalizer.canonicalize("1.5*sin(x) + 0.5*sin(x)"));

        assertNotEquals(canonicalizer.stableHash("0.5*sin(x)"), canonicalizer.stableHash("0"));
        assertNotEquals(canonicalizer.stableHash("1.5*sin(x)"), canonicalizer.stableHash("sin(x)"));
        assertNotEquals(
            canonicalizer.stableHash("2147483648*sin(x)"),
            canonicalizer.stableHash("2147483647*sin(x)"));
    }

    @Test
    void assumptionFreeZeroProductsDoNotEraseUndefinedFactors() {
        String undefined = "0*(1/0)";
        String nested = "2 + 0*(1/0)";

        assertNotEquals(canonicalizer.stableHash(undefined), canonicalizer.stableHash("0"));
        assertNotEquals(canonicalizer.stableHash(nested), canonicalizer.stableHash("2"));
        assertEquals(
            canonicalizer.canonicalize(undefined),
            canonicalizer.canonicalize(canonicalizer.canonicalize(undefined)));

        AssumptionContext context = new AssumptionContext();
        assertEquals("0", canonicalizer.canonicalizeWith("0*(x/x)", context));
        assertTrue(context.snapshot().stream().anyMatch(
            assumption -> assumption.kind() == Assumption.Kind.NON_ZERO));
    }

    @Test
    void unrepresentableExactCoefficientSumFallsBackWithoutRounding() {
        String source = "sin(x) + 0.00000000000000001*sin(x)";
        String canonical = canonicalizer.canonicalize(source);

        assertNotEquals(canonicalizer.stableHash(source), canonicalizer.stableHash("sin(x)"));
        assertEquals(canonical, canonicalizer.canonicalize(canonical));
    }

    @Test
    void fractionalPowersAreNeverNarrowedWhenProductsAreCollected() {
        for (String exponent : List.of("0.5", "1.5")) {
            String source = "sin(x)^" + exponent + " * sin(x)^" + exponent;
            String canonical = canonicalizer.canonicalize(source);
            assertEquals(canonical, canonicalizer.canonicalize(canonical));
            assertNotEquals(canonicalizer.stableHash(source), canonicalizer.stableHash("1"));
            assertNotEquals(canonicalizer.stableHash(source), canonicalizer.stableHash("sin(x)^2"));
        }
    }

    @Test
    void exactPolynomialCoefficientDoesNotRoundBackIntoLegacyAst() {
        String source = "0.123456789012345 * 0.123456789012345 * x";
        double rounded = 0.123456789012345d * 0.123456789012345d;
        String roundedExpression = Double.toString(rounded) + " * x";

        assertTrue(new PolynomialNormalizer()
            .normalize(parser.parseTerm(source)).isEmpty(),
            "unrepresentable exact coefficient must make normalization decline");
        assertNotEquals(canonicalizer.stableHash(source), canonicalizer.stableHash(roundedExpression));
        String canonical = canonicalizer.canonicalize(source);
        assertEquals(canonical, canonicalizer.canonicalize(canonical));

        assertEquals("0.02 * x", canonicalizer.canonicalize("0.1 * 0.2 * x"));
    }

    @Test
    void polynomialSortedByDescendingDegree() {
        assertEquals("x ^ 2 + 2 * x + 1", canonicalizer.canonicalize("1 + 2*x + x^2"));
        assertEquals("x ^ 10 + x ^ 2", canonicalizer.canonicalize("x^2 + x^10"));
        assertEquals("x + y", canonicalizer.canonicalize("y + x"));
    }

    @Test
    void canonicalizesGlobalPolynomialLikeTerms() {
        assertEquals("x ^ 2 + 3 * x + 2", canonicalizer.canonicalize("x*x + x*2 + x + 2"));
    }

    @Test
    void canonicalizerKeepsCompositePolynomialExpansionAsExplicitSearchStep() {
        assertEquals("(x + 1) ^ 2", canonicalizer.canonicalize("(x + 1)^2"));
    }

    @Test
    void defaultDivisionIsAssumptionFree() {
        assertNotEquals(canonicalizer.stableHash("x/x"), canonicalizer.stableHash("1"));
    }

    @Test
    void assumptionAwareDivisionCancellation() {
        AssumptionContext ctx = new AssumptionContext();
        assertEquals("1", canonicalizer.canonicalizeWith("x/x", ctx));
        assertTrue(ctx.snapshot().stream().anyMatch(a -> a.kind() == Assumption.Kind.NON_ZERO
            && a.expression().equals("x != 0")));
    }

    @Test
    void assumptionAwareZeroNumeratorCancellation() {
        AssumptionContext ctx = new AssumptionContext();
        assertEquals("0", canonicalizer.canonicalizeWith("0/(x + 1)", ctx));
        assertTrue(ctx.snapshot().stream().anyMatch(a -> a.kind() == Assumption.Kind.NON_ZERO));
    }

    @Test
    void assumptionAwareFactorCancellation() {
        AssumptionContext ctx = new AssumptionContext();
        assertEquals("a", canonicalizer.canonicalizeWith("(a*x)/x", ctx));
        assertTrue(ctx.snapshot().stream().anyMatch(a -> a.kind() == Assumption.Kind.NON_ZERO
            && a.expression().equals("x != 0")));
    }

    @Test
    void assumptionFingerprintSeparatesHashes() {
        AssumptionContext ctx = new AssumptionContext();
        String safeHash = canonicalizer.stableHash("x/x");
        String assumingHash = canonicalizer.stableHashWith("x/x", ctx);
        assertNotEquals(safeHash, assumingHash,
            "hash with active assumption must differ from assumption-free hash");
    }

    @Test
    void assumptionFingerprintIsStable() {
        AssumptionContext ctxA = new AssumptionContext();
        AssumptionContext ctxB = new AssumptionContext();
        canonicalizer.canonicalizeWith("x/x", ctxA);
        canonicalizer.canonicalizeWith("y/y", ctxA);
        canonicalizer.canonicalizeWith("y/y", ctxB);
        canonicalizer.canonicalizeWith("x/x", ctxB);
        assertEquals(
            canonicalizer.stableHashWith("a", ctxA),
            canonicalizer.stableHashWith("a", ctxB));
    }

    @Test
    void hashCollisionBaselineIsReducedByStrongCanonicalization() {
        List<String> equivalents = List.of(
            "a + b + c",
            "(a + b) + c",
            "a + (b + c)",
            "c + b + a",
            "b + (a + c)",
            "(c + a) + b");
        Set<String> hashes = new HashSet<>();
        for (String expression : equivalents) {
            hashes.add(canonicalizer.stableHash(expression));
        }
        assertEquals(1, hashes.size(),
            "all AC variants of a+b+c must canonicalize to the same hash but got " + hashes);
    }

    @Test
    void propertyTestRandomExpressionsAreStableUnderReparse() {
        RandomExpressionGenerator generator = new RandomExpressionGenerator(42L);
        List<String> samples = generator.generate(40, 3);
        for (String sample : samples) {
            String once = canonicalizer.canonicalize(sample);
            String twice = canonicalizer.canonicalize(once);
            assertEquals(once, twice, "canonical form not stable for " + sample);
        }
    }

    private record Use(long id, Expr value) {
    }
}
