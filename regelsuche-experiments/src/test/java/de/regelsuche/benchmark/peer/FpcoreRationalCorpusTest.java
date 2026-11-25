package de.regelsuche.benchmark.peer;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FpcoreRationalCorpusTest {
    @Test
    void rationalAdmissionIsSeparateFromTheFrozenPolynomialFragment() {
        String text = """
            (FPCore (x) :name "division" (/ (- (* x x) 1) (- x 1)))
            (FPCore (x) :name "undefined" (/ x (- x x)))
            (FPCore (x) :name "root" (sqrt x))
            (FPCore (x) :name "polynomial" (+ x 1))
            """;
        var polynomial = FpcorePolynomialCorpus.read(text);
        var rational = FpcorePolynomialCorpus.readRational(text);
        assertEquals(4, rational.size());
        assertEquals(FpcorePolynomialCorpus.Status.UNSUPPORTED, polynomial.getFirst().status());
        assertEquals(FpcorePolynomialCorpus.Status.SUPPORTED, rational.getFirst().status());
        assertTrue(rational.getFirst().source().contains("/"));
        assertEquals(FpcorePolynomialCorpus.Status.UNSUPPORTED, rational.get(1).status());
        assertEquals(FpcorePolynomialCorpus.Status.UNSUPPORTED, rational.get(2).status());
        assertEquals(FpcorePolynomialCorpus.Status.SUPPORTED, rational.get(3).status());
        assertEquals(4, rational.get(3).ordinal());
    }
}
