package de.regelsuche.evolution;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Evolution;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Proposal;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Request;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantVerifier;
import de.regelsuche.representation.RepresentationBridge.Budget;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static de.regelsuche.evolution.CumulativeTaskKnowledge.Strategy.AUTOMATIC;
import static de.regelsuche.evolution.CumulativeTaskKnowledge.Strategy.BASELINE;
import static org.junit.jupiter.api.Assertions.*;

class RecurrenceProofSelectionTest {
    private static final Budget QUERY = new Budget(100_000);
    private static final Budget ACQUISITION = new Budget(500_000);

    @Test
    void learnsFromShiftedValidationAndSelectsTheProofAfterRestore() {
        var matrix = new ExactMatrix(List.of(List.of(ratio(5, 9), ratio(2, 3)),
            List.of(ratio(-2, 3), Rational.ONE)));
        var lesson = request(matrix, values(0, 0), values(2, 3), 2);
        var validation = request(matrix, List.of(ratio(8, 3), Rational.of(2)), values(4, -1), 2);
        var update = empty().learnRecurrences(List.of(lesson), List.of(validation), ACQUISITION);
        assertTrue(update.promoted(), () -> update.reason().name());

        var saved = update.next().toCanonicalJson();
        var loaded = CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(), saved);
        assertEquals(saved, loaded.toCanonicalJson());
        assertTrue(loaded.sessionSetupWork() > 0);
        var target = request(matrix, List.of(ratio(-8, 9), ratio(2, 3)), values(7, 11), 2);
        var result = loaded.solve(target, AUTOMATIC);
        assertTrue(result.reused());
        assertEquals(Rational.of(81), result.law().orElseThrow().initialValue());
        assertReplay(target, result);
    }

    @Test
    void selectsTheNegatedLinearSourceAndChangesTheLawSign() {
        var source = request(new ExactMatrix(List.of(values(2, 1), values(0, -1))), values(0, 0), values(1, 4), 1);
        var negated = new ExactMatrix(List.of(values(-2, -1), values(0, 1)));
        var validation = request(negated, values(0, 0), values(3, 2), 1);
        var update = empty().learnRecurrences(List.of(source), List.of(validation), ACQUISITION);
        assertTrue(update.promoted(), () -> update.reason().name());
        assertEquals(Evolution.ALTERNATING, update.training().getFirst().law().orElseThrow().proposal().evolution());
        var target = request(negated, values(0, 0), values(6, 5), 1);
        var result = update.next().solve(target, AUTOMATIC);
        assertTrue(result.reused());
        var law = result.law().orElseThrow();
        assertEquals(Evolution.FIXED, law.proposal().evolution());
        assertEquals(Rational.of(5), law.valueAt(BigInteger.ONE));
        assertReplay(target, result);
    }

    @Test
    void paysForAnInconsistentShiftAndTriesAnotherAnchorBeforeFallback() {
        var matrix = new ExactMatrix(List.of(values(2, 0, 0), values(0, 1, 0), values(0, 0, 1)));
        var first = request(matrix, values(0, 0, 1), values(1, 2, 3), 1);
        var second = request(matrix, values(0, 1, 0), values(1, 2, 3), 1);
        var old = empty();
        var update = old.learnRecurrences(List.of(first, second), List.of(
            request(matrix, values(1, 0, 1), values(2, 3, 4), 1),
            request(matrix, values(1, 1, 0), values(2, 3, 4), 1)), ACQUISITION);
        assertTrue(update.promoted(), () -> update.reason().name());
        assertEquals(2, update.next().recurrenceCount());

        var target = request(matrix, values(3, 1, 0), values(2, 5, 7), 1);
        var reused = update.next().solve(target, AUTOMATIC);
        assertTrue(reused.reused());
        assertEquals(Rational.of(7), reused.law().orElseThrow().initialValue());
        assertReplay(target, reused);

        var miss = request(matrix, values(3, 2, 3), values(2, 5, 7), 1);
        var fallback = update.next().solve(miss, AUTOMATIC);
        assertFalse(fallback.reused());
        var direct = old.solve(miss, BASELINE);
        assertTrue(fallback.totalWork() > direct.totalWork(), "failed matching work is charged");
        assertEquals(direct.law().orElseThrow().proposal(), fallback.law().orElseThrow().proposal());

        int remainingBeforeSuccess = Math.toIntExact(fallback.totalWork() - 1);
        var capped = new Request(miss.transition(), miss.initialState(), 1, new Budget(remainingBeforeSuccess));
        assertTrue(old.solve(capped, BASELINE).solved());
        var stopped = update.next().solve(capped, AUTOMATIC);
        assertEquals(RecurrenceKnowledge.Outcome.BUDGET_INCONCLUSIVE, stopped.outcome());
        assertEquals(remainingBeforeSuccess, stopped.totalWork());
        assertTrue(stopped.law().isEmpty());
    }

    @Test
    void aRetainedProofMustProveTheStoredPolynomialInItsDeclaredDegree() {
        var source = request(new ExactMatrix(List.of(values(1, 0), values(0, 1))),
            values(0, 0), values(3, 5), 2);
        var verifier = new RecurrenceInvariantVerifier();
        var signed = new Proposal("x1", Evolution.FIXED);
        var otherLaw = verifier.verify(source.transition(), signed, source.initialState(), QUERY).law().orElseThrow();
        var different = new RecurrenceKnowledge.Entry(source.transition(), 1, new Proposal("x0", Evolution.FIXED));
        assertThrows(IllegalArgumentException.class, () -> different.retain(otherLaw));
        var quadratic = new Proposal("x0^2", Evolution.FIXED);
        var quadraticLaw = verifier.verify(source.transition(), quadratic, source.initialState(), QUERY).law().orElseThrow();
        var tooShallow = new RecurrenceKnowledge.Entry(source.transition(), 1, quadratic);
        assertThrows(IllegalArgumentException.class, () -> tooShallow.retain(quadraticLaw));

        var original = new Proposal("x1 + 0", Evolution.FIXED);
        String snapshot = RecurrenceKnowledgeCodec.encode(List.of(new RecurrenceKnowledge.Entry(source.transition(), 1, original)));
        var restored = RecurrenceKnowledgeCodec.restore(snapshot).entries().getFirst();
        assertEquals(original, restored.proposal());
        assertEquals(signed, restored.anchorProof().orElseThrow().proposal());
    }

    private static void assertReplay(Request request, RecurrenceKnowledge.Execution result) {
        var law = result.law().orElseThrow();
        var checked = new RecurrenceInvariantVerifier().verify(request.transition(), law.proposal(),
            request.initialState(), QUERY);
        assertEquals(law.initialValue(), checked.law().orElseThrow().initialValue());
    }

    private static CumulativeTaskKnowledge empty() {
        return CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
    }

    private static Request request(ExactMatrix matrix, List<Rational> offset, List<Rational> initial, int degree) {
        return new Request(new Transition(matrix, new ExactVector(offset)), initial, degree, QUERY);
    }

    private static List<Rational> values(long... values) {
        return Arrays.stream(values).mapToObj(Rational::of).toList();
    }

    private static Rational ratio(long numerator, long denominator) {
        return new Rational(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }
}
