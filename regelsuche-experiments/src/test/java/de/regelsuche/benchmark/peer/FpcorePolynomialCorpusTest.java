package de.regelsuche.benchmark.peer;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FpcorePolynomialCorpusTest {
    @Test
    void importsExactDecimalsAndLexicalBindingsWithoutUsingPreconditions() {
        var values = FpcorePolynomialCorpus.read("""
            ; an original program can have metadata containing parentheses
            (FPCore (x) :name "quoted (name)" :precision binary64 :pre (> x 0)
              (let ([a 0.125] [b x]) (+ (* a b) (- x))))
            """);
        assertEquals(1, values.size());
        assertEquals("quoted (name)", values.getFirst().name());
        assertEquals(FpcorePolynomialCorpus.Status.SUPPORTED, values.getFirst().status());
        new ExactPolynomialAnalysis().requireEquivalent("x/8-x", values.getFirst().source());
    }

    @Test
    void letBindingsAreParallelAndLetStarBindingsAreSequential() {
        var entries = FpcorePolynomialCorpus.read("""
            (FPCore (x) :name "parallel" (let ([x 2] [y x]) (+ x y)))
            (FPCore (x) :name "sequential" (let* ([x 2] [y x]) (+ x y)))
            """);
        assertEquals(2, entries.size());
        new ExactPolynomialAnalysis().requireEquivalent("2+x", entries.get(0).source());
        new ExactPolynomialAnalysis().requireEquivalent("4", entries.get(1).source());
    }

    @Test
    void keepsUnsupportedProgramsAndDoesNotCancelAwayUndefinedDenominators() {
        var entries = FpcorePolynomialCorpus.read("""
            (FPCore (x) :name "variable denominator" (/ x x))
            (FPCore (x) :name "zero denominator" (/ x (- 1 1)))
            (FPCore (x) :name "branch" (if (> x 0) x (- x)))
            (FPCore (x) :name "large literal" (+ x 9223372036854775808))
            (FPCore (x) :name "valid" (/ x 6))
            """);
        assertEquals(5, entries.size());
        assertTrue(entries.subList(0, 4).stream().allMatch(e ->
            e.status() == FpcorePolynomialCorpus.Status.UNSUPPORTED && !e.detail().isBlank()));
        assertEquals(FpcorePolynomialCorpus.Status.SUPPORTED, entries.get(4).status());
        assertEquals(5, entries.get(4).ordinal());
    }

    @Test
    void rejectsMalformedDocumentsAndUnboundVariables() {
        assertThrows(IllegalArgumentException.class, () -> FpcorePolynomialCorpus.read("(FPCore (x) (+ x 1)"));
        var unknown = FpcorePolynomialCorpus.read("(FPCore (x) (+ x y))");
        assertEquals(1, unknown.size());
        assertEquals(FpcorePolynomialCorpus.Status.UNSUPPORTED, unknown.getFirst().status());
    }

    @Test
    void quotedStringsCannotBecomeNumericOrVariableExpressions() {
        var entries = FpcorePolynomialCorpus.read("""
            (FPCore (x) :name "quoted number" "1")
            (FPCore (x) :name "quoted variable" (+ "x" 1))
            """);
        assertTrue(entries.stream().allMatch(e -> e.status() == FpcorePolynomialCorpus.Status.UNSUPPORTED));
        assertThrows(IllegalArgumentException.class, () -> FpcorePolynomialCorpus.fromPrefix("\"1\""));
    }

    @Test
    void exactNativeIntegersDoNotHaveABinary64PrecisionRestriction() {
        var entries = FpcorePolynomialCorpus.read("(FPCore (x) (* 9007199254740993 x))");
        assertEquals(FpcorePolynomialCorpus.Status.SUPPORTED, entries.getFirst().status());
        new ExactPolynomialAnalysis().requireEquivalent("9007199254740993*x", entries.getFirst().source());
    }

    @Test
    void retainsTheCompletePinnedForeignFileInOrder() throws Exception {
        var root = Path.of(System.getProperty("regelsuche.repositoryRoot", "..")).toAbsolutePath();
        var file = root.resolve("research/benchmarks/enumo-public-v1/rosa.fpcore");
        var entries = FpcorePolynomialCorpus.read(Files.readString(file));
        assertEquals(37, entries.size());
        assertEquals("doppler1", entries.getFirst().name());
        assertEquals("Sine Newton", entries.getLast().name());
        assertTrue(entries.stream().anyMatch(e -> e.name().equals("rigidBody2")
            && e.status() == FpcorePolynomialCorpus.Status.SUPPORTED));
        assertTrue(entries.stream().anyMatch(e -> e.name().equals("Pendulum")
            && e.status() == FpcorePolynomialCorpus.Status.UNSUPPORTED));
    }
}
