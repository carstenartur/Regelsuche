package de.regelsuche.benchmark.peer;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import org.junit.jupiter.api.Test;

class EnumoPublicComparisonTest {
    @Test
    void startupCannotConsumeAnotherFullBudgetAfterItsDeadline(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) {
        var command = java.util.List.of(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), SlowReady.class.getName());
        assertThrows(java.util.concurrent.TimeoutException.class, () -> {
            try (var ignored = new EnumoPublicComparison.Session(command, temp.resolve("stderr"),
                    System.nanoTime() - 1)) { }
        });
    }

    public static final class SlowReady {
        private SlowReady() { }
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(100);
            System.out.println("{\"status\":\"READY\"}");
        }
    }

    @Test
    void acceptsOnlyExactPolynomialEquivalence() {
        assertEquals("VERIFIED", EnumoPublicComparison.judge("x*x+x", "x*(x+1)"));
        assertEquals("INVALID", EnumoPublicComparison.judge("x*x+x", "x*(x+2)"));
        assertEquals("INVALID", EnumoPublicComparison.judge("x/3", "0.3333333333333333*x"));
    }

    @Test
    void rejectsAResultThatIntroducesAnUndefinedDenominator() {
        assertEquals("OUTSIDE_SHARED_GRAMMAR", EnumoPublicComparison.judge("1", "x/x"));
        assertEquals("OUTSIDE_SHARED_GRAMMAR", EnumoPublicComparison.judge("x", "x/0"));
    }

    @Test
    void nativeCandidatesMustRespectTheSameFrozenGrammarAsOriginalCandidates() {
        assertEquals("OUTSIDE_SHARED_GRAMMAR", EnumoPublicComparison.judge("x*x*x*x", "x^4"));
        assertEquals("OUTSIDE_SHARED_GRAMMAR", EnumoPublicComparison.judge("x", "x/(y-y+1)"));
    }

    @Test
    void peerSerializationPreservesExactArithmeticAndRejectsForeignOperators() {
        for (String source : java.util.List.of("(x*x-x)/6", "(-3*x+5)/8", "0.125*x-2")) {
            String wire = FpcorePolynomialCorpus.toPrefix(source);
            String received = FpcorePolynomialCorpus.fromPrefix(wire);
            new ExactPolynomialAnalysis().requireEquivalent(source, received);
        }
        assertThrows(IllegalArgumentException.class, () -> FpcorePolynomialCorpus.fromPrefix("(if x 1 0)"));
        assertThrows(IllegalArgumentException.class, () -> FpcorePolynomialCorpus.fromPrefix("(+ x 1) (+ x 2)"));
    }
}
