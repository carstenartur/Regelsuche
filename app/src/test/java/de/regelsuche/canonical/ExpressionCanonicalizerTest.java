package de.regelsuche.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionContext;
import de.regelsuche.validation.RandomExpressionGenerator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExpressionCanonicalizerTest {
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    @Test
    void canonicalizesCommutativeExpressions() {
        assertEquals(canonicalizer.stableHash("a + b"), canonicalizer.stableHash("b + a"));
        assertEquals("x", canonicalizer.canonicalize("x*1"));
        assertEquals("x + y + z", canonicalizer.canonicalize("(x+y)+z"));
        assertEquals("x ^ 2", canonicalizer.canonicalize("x*x"));
    }

    @Test
    void associativeAndCommutativeVariantsCollapseToSameHash() {
        // (a+b)+c == a+(b+c)
        assertEquals(canonicalizer.stableHash("(a+b)+c"), canonicalizer.stableHash("a+(b+c)"));
        // a*b == b*a
        assertEquals(canonicalizer.stableHash("a*b"), canonicalizer.stableHash("b*a"));
        // (a*b)*c == a*(b*c) == c*a*b
        assertEquals(canonicalizer.stableHash("(a*b)*c"), canonicalizer.stableHash("a*(b*c)"));
        assertEquals(canonicalizer.stableHash("(a*b)*c"), canonicalizer.stableHash("c*a*b"));
    }

    @Test
    void numericConstantsAreFolded() {
        assertEquals("5", canonicalizer.canonicalize("2 + 3"));
        assertEquals("12", canonicalizer.canonicalize("3 * 4"));
        // Mixed: 2 + x + 3 → x + 5
        assertEquals(canonicalizer.canonicalize("x + 5"), canonicalizer.canonicalize("2 + x + 3"));
    }

    @Test
    void polynomialSortedByDescendingDegree() {
        // monomials must appear high-degree first (mathematical normal form)
        assertEquals("x ^ 2 + 2 * x + 1", canonicalizer.canonicalize("1 + 2*x + x^2"));
        // higher-degree term sorts before lower one regardless of input order
        assertEquals("x ^ 10 + x ^ 2", canonicalizer.canonicalize("x^2 + x^10"));
        // tie on degree → lex ascending
        assertEquals("x + y", canonicalizer.canonicalize("y + x"));
    }

    @Test
    void defaultDivisionIsAssumptionFree() {
        // Without an AssumptionContext, x/x must NOT collapse to 1 (would be
        // mathematically wrong in general).
        assertNotEquals(canonicalizer.stableHash("x/x"), canonicalizer.stableHash("1"));
    }

    @Test
    void assumptionAwareDivisionCancellation() {
        AssumptionContext ctx = new AssumptionContext();
        // x/x → 1  under  x ≠ 0
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
        // (a*x)/x → a  under  x ≠ 0
        assertEquals("a", canonicalizer.canonicalizeWith("(a*x)/x", ctx));
        assertTrue(ctx.snapshot().stream().anyMatch(a -> a.kind() == Assumption.Kind.NON_ZERO
            && a.expression().equals("x != 0")));
    }

    @Test
    void assumptionFingerprintSeparatesHashes() {
        // Same input, but with vs. without assumption tracking → different hashes,
        // so transposition entries from the two modes don't accidentally merge.
        AssumptionContext ctx = new AssumptionContext();
        String safeHash = canonicalizer.stableHash("x/x");
        String assumingHash = canonicalizer.stableHashWith("x/x", ctx);
        assertNotEquals(safeHash, assumingHash,
            "hash with active assumption must differ from assumption-free hash");
    }

    @Test
    void assumptionFingerprintIsStable() {
        // Same expression canonicalized with two equivalent assumption contexts
        // must produce the same hash, regardless of the order in which the
        // assumptions were added (assumption set, not list).
        AssumptionContext ctxA = new AssumptionContext();
        AssumptionContext ctxB = new AssumptionContext();
        canonicalizer.canonicalizeWith("x/x", ctxA);
        canonicalizer.canonicalizeWith("y/y", ctxA);
        canonicalizer.canonicalizeWith("y/y", ctxB);
        canonicalizer.canonicalizeWith("x/x", ctxB);
        assertEquals(
            canonicalizer.stableHashWith("a", ctxA),
            canonicalizer.stableHashWith("a", ctxB)
        );
    }

    @Test
    void hashCollisionBaselineIsReducedByStrongCanonicalization() {
        // The strong canonicalizer must reduce — at the very least: not
        // increase — the number of distinct hashes produced for a set of
        // syntactically different but algebraically equivalent expressions.
        List<String> equivalents = List.of(
            "a + b + c",
            "(a + b) + c",
            "a + (b + c)",
            "c + b + a",
            "b + (a + c)",
            "(c + a) + b"
        );
        Set<String> hashes = new HashSet<>();
        for (String expression : equivalents) {
            hashes.add(canonicalizer.stableHash(expression));
        }
        assertEquals(1, hashes.size(),
            "all AC variants of a+b+c must canonicalize to the same hash but got " + hashes);
    }

    @Test
    void propertyTestRandomExpressionsAreStableUnderReparse() {
        // A canonical string must be a fix-point: re-parsing then
        // re-canonicalizing produces the same string. This protects against
        // round-trip drift in either canonicalizer or formatter.
        RandomExpressionGenerator generator = new RandomExpressionGenerator(42L);
        List<String> samples = generator.generate(40, 3);
        for (String sample : samples) {
            String once = canonicalizer.canonicalize(sample);
            String twice = canonicalizer.canonicalize(once);
            assertEquals(once, twice, "canonical form not stable for " + sample);
        }
    }
}
