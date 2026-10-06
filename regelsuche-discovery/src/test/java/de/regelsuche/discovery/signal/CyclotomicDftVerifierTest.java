package de.regelsuche.discovery.signal;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.discovery.signal.PeriodicFourier.Plan;
import de.regelsuche.discovery.signal.PeriodicSignal.Term;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CyclotomicDftVerifierTest {
    @Test
    void independentlyChecksEveryCombAndFrequencyThroughLengthTwentyFour() {
        for (int length = 1; length <= 24; length++) {
            for (int period = 1; period <= length; period++) {
                if (length % period != 0) continue;
                var query = new FourierQuery(new PeriodicSignal(length,
                    List.of(new Term(period, ExactRational.fromCanonicalText("-5/7")))),
                    IntStream.range(0, length).boxed().toList());
                for (var plan : Plan.values()) {
                    var result = PeriodicFourier.evaluate(query, plan);
                    var check = CyclotomicDftVerifier.verify(query, result.coefficients());
                    assertTrue(check.accepted(), "L=" + length + ", d=" + period + ": " + check);
                    assertEquals(length, check.checkedCoefficients());
                }
            }
        }
    }

    @Test
    void rejectsWrongNormalizationAndWrongSupportWithExactCounterexamples() {
        var query = new FourierQuery(new PeriodicSignal(12,
            List.of(new Term(3, ExactRational.ONE))), List.of(0, 3, 4));
        var wrong = List.of(ExactRational.integer(4), ExactRational.ZERO, ExactRational.ZERO);
        var check = CyclotomicDftVerifier.verify(query, wrong);
        assertFalse(check.accepted());
        assertEquals(0, check.counterexample().orElseThrow().frequency());
        assertTrue(check.counterexample().orElseThrow().remainder().stream().anyMatch(x -> !x.isZero()));

        var wrongSupport = new ArrayList<>(PeriodicFourier.evaluate(query, Plan.DIRECT).coefficients());
        wrongSupport.set(1, ExactRational.fromCanonicalText("1/3"));
        var second = CyclotomicDftVerifier.verify(query, wrongSupport);
        assertFalse(second.accepted());
        assertEquals(3, second.counterexample().orElseThrow().frequency());
    }

    @Test
    void independentlyChecksMixturesCancellationAndMaximumLength() {
        var query = new FourierQuery(new PeriodicSignal(128, List.of(
            new Term(1, ExactRational.ONE), new Term(4, ExactRational.fromCanonicalText("2/3")),
            new Term(4, ExactRational.fromCanonicalText("-2/3")),
            new Term(8, ExactRational.fromCanonicalText("-5/11")),
            new Term(128, ExactRational.integer(2)))), List.of(0, 1, 16, 32, 127));
        var check = CyclotomicDftVerifier.verify(query,
            PeriodicFourier.evaluate(query, Plan.MERGE_PERIODS).coefficients());
        assertTrue(check.accepted());
        assertTrue(check.work().units() > 0);
        assertTrue(check.work().maxOperandBits() >= 7);
        assertThrows(IllegalArgumentException.class,
            () -> CyclotomicDftVerifier.verify(query, List.of(ExactRational.ZERO)));
    }
}
