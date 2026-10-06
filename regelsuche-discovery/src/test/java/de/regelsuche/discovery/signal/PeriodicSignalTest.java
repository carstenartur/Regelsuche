package de.regelsuche.discovery.signal;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.discovery.signal.PeriodicFourier.Plan;
import de.regelsuche.discovery.signal.PeriodicSignal.Term;
import de.regelsuche.scalar.ExactRational;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PeriodicSignalTest {
    @Test
    void returnsNormalizedDftAtSelectedFrequenciesWithRationalWeights() {
        var query = new FourierQuery(new PeriodicSignal(12, List.of(
            new Term(3, ExactRational.integer(2)),
            new Term(4, ExactRational.fromCanonicalText("-1/2")))),
            List.of(0, 1, 3, 4, 8, 9, 11));
        var expected = List.of("13/24", "0", "-1/8", "2/3", "2/3", "-1/8", "0")
            .stream().map(ExactRational::fromCanonicalText).toList();

        for (var plan : Plan.values()) {
            assertEquals(expected, PeriodicFourier.evaluate(query, plan).coefficients());
        }
    }

    @Test
    void handlesLengthOneConstantImpulseAndCancellation() {
        for (var plan : Plan.values()) {
            assertEquals(List.of(ExactRational.integer(7)), PeriodicFourier.evaluate(
                new FourierQuery(new PeriodicSignal(1, List.of(new Term(1, ExactRational.integer(7)))),
                    List.of(0)), plan).coefficients());
            var query = new FourierQuery(new PeriodicSignal(8, List.of(
                new Term(1, ExactRational.integer(3)),
                new Term(8, ExactRational.integer(4)),
                new Term(2, ExactRational.ONE), new Term(2, ExactRational.NEGATIVE_ONE))),
                IntStream.range(0, 8).boxed().toList());
            var result = PeriodicFourier.evaluate(query, plan);
            assertEquals(ExactRational.fromCanonicalText("7/2"), result.coefficients().getFirst());
            assertTrue(result.coefficients().subList(1, 8).stream()
                .allMatch(ExactRational.fromCanonicalText("1/2")::equals));
        }
    }

    @Test
    void mergingCanSaveEvaluationWorkButHasAConstructionCost() {
        var terms = IntStream.range(0, 12).mapToObj(i -> new Term(3, ExactRational.ONE)).toList();
        var query = new FourierQuery(new PeriodicSignal(12, terms),
            IntStream.range(0, 12).boxed().toList());
        var direct = PeriodicFourier.evaluate(query, Plan.DIRECT);
        var merged = PeriodicFourier.evaluate(query, Plan.MERGE_PERIODS);
        assertEquals(direct.coefficients(), merged.coefficients());
        assertTrue(merged.evaluation().units() < direct.evaluation().units());
        assertTrue(merged.construction().units() > 0);
        assertTrue(merged.total().units() < direct.total().units());
        assertTrue(merged.total().operandBits() > 0);
    }

    @Test
    void validatesAssumptionsAndBoundsBeforeComputing() {
        assertThrows(IllegalArgumentException.class, () -> new PeriodicSignal(0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PeriodicSignal(129, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PeriodicSignal(12,
            List.of(new Term(5, ExactRational.ONE))));
        assertThrows(IllegalArgumentException.class, () -> new Term(0, ExactRational.ONE));
        assertThrows(IllegalArgumentException.class, () -> new Term(1,
            ExactRational.integer(java.math.BigInteger.ONE.shiftLeft(64))));
        assertThrows(IllegalArgumentException.class, () -> new PeriodicSignal(12,
            java.util.Collections.nCopies(33, new Term(3, ExactRational.ONE))));
        var signal = new PeriodicSignal(4, List.of());
        assertThrows(IllegalArgumentException.class, () -> new FourierQuery(signal, List.of(-1)));
        assertThrows(IllegalArgumentException.class, () -> new FourierQuery(signal, List.of(4)));
        assertThrows(IllegalArgumentException.class, () -> new FourierQuery(signal, List.of(0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new FourierQuery(signal, List.of()));
    }
}
