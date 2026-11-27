package de.regelsuche.math.algorithms.combinatorics;

import static de.regelsuche.scalar.ExactRational.ZERO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.LinearProgramVerifier.Status;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConflictMatchingAdversarialTest {
    @Test
    void verifierRejectsWeightsWhoseNumeratorCanFalsifyTheIntegerBound() {
        var numerator = new NonCanonicalOne();
        var counterfeit = new ExactRational(numerator, BigInteger.ONE);
        assertSame(numerator, counterfeit.numerator(), "The exact rational constructor can retain a BigInteger subtype");

        // Either edge selected alone is compatible, so a verified integer upper bound of zero is false.
        assertInvalidWeight(counterfeit);
    }

    @Test
    void verifierRejectsWeightsBeforeCallingArithmeticOnANonCanonicalDenominator() {
        var denominator = new GuardedDenominator();
        var counterfeit = new ExactRational(BigInteger.ONE, denominator);
        assertSame(denominator, counterfeit.denominator());
        denominator.rejectArithmetic = true;

        assertInvalidWeight(counterfeit);
    }

    private static void assertInvalidWeight(ExactRational weight) {
        var graph = new BipartiteMatching.Request(2, 2,
            List.of(new BipartiteMatching.Edge(0, 0), new BipartiteMatching.Edge(1, 1)));
        var source = new ConflictMatching.Request(graph, List.of(new ConflictMatching.Conflict(0, 1)));
        var certificate = new ConflictMatching.Certificate(source, List.of(ZERO, ZERO),
            List.of(ZERO, ZERO), List.of(weight));
        var replay = ConflictMatchingVerifier.verify(source, certificate,
            new IterationLimits(100_000, 1000, 10_000), () -> false);

        assertEquals(Status.INVALID, replay.status(), replay::toString);
        assertEquals("NON_CANONICAL_WEIGHT", replay.reason());
        assertTrue(replay.bound().isEmpty());
    }

    private static final class NonCanonicalOne extends BigInteger {
        private static final long serialVersionUID = 1L;
        private int divisions;

        NonCanonicalOne() {
            super("1");
        }

        @Override
        public BigInteger divide(BigInteger divisor) {
            // Retain the subtype through rational normalization, then falsify the verifier's floor.
            return divisions++ == 0 ? this : BigInteger.ZERO;
        }
    }

    private static final class GuardedDenominator extends BigInteger {
        private static final long serialVersionUID = 1L;
        private boolean rejectArithmetic;

        GuardedDenominator() {
            super("1");
        }

        @Override
        public BigInteger divide(BigInteger divisor) {
            if (rejectArithmetic) throw new AssertionError("Verifier divided a noncanonical denominator");
            return this;
        }

        @Override
        public int bitLength() {
            if (rejectArithmetic) throw new AssertionError("Verifier measured a noncanonical denominator");
            return super.bitLength();
        }
    }
}
