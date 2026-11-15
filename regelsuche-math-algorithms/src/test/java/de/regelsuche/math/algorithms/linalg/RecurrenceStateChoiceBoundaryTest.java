package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Request;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Status;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.representation.RepresentationBridge.Budget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecurrenceStateChoiceBoundaryTest {
    private final RecurrenceInvariantSynthesis solver = new RecurrenceInvariantSynthesis();

    @Test
    void findsTheSameFirstLawByActuallySolvingOnASmallerSupport() {
        var transition = new Transition(new ExactMatrix(List.of(row(0, 1), row(1, 1))), new ExactVector(row(0, 0)));
        var task = new Request(transition, row(0, 1), 2, new Budget(10_000));
        var direct = solver.findFirst(task);
        var guided = solver.findFirst(task, List.of(new Monomial(Map.of("x0", 2)),
            new Monomial(Map.of("x0", 1, "x1", 1)), new Monomial(Map.of("x1", 2))));
        assertTrue(guided.usedSupport());
        assertEquals(direct.law().orElseThrow().proposal(), guided.law().orElseThrow().proposal());
        assertEquals(solver.solve(task).laws().getFirst().proposal(), direct.law().orElseThrow().proposal());
        assertTrue(guided.work().consumedWorkUnits() < direct.work().consumedWorkUnits());
    }

    @Test
    void chargesAnInsufficientSupportAndFallsBackToTheFullBasis() {
        var flip = new Transition(new ExactMatrix(List.of(row(-1))), new ExactVector(row(1)));
        var task = new Request(flip, row(2), 2, new Budget(10_000));
        var direct = solver.findFirst(task);
        var guided = solver.findFirst(task, List.of(Monomial.variable("x0")));
        assertFalse(guided.usedSupport());
        assertEquals(direct.law().orElseThrow().proposal(), guided.law().orElseThrow().proposal());
        assertTrue(guided.work().consumedWorkUnits() > direct.work().consumedWorkUnits());
    }

    @Test
    void paidFailureDoesNotResetTheBudget() {
        var flip = new Transition(new ExactMatrix(List.of(row(-1))), new ExactVector(row(1)));
        var reference = solver.findFirst(new Request(flip, row(2), 2, new Budget(10_000)));
        var allowance = reference.work().consumedWorkUnits();
        var task = new Request(flip, row(2), 2, new Budget(allowance));
        var guided = solver.findFirst(task, List.of(Monomial.variable("x0")));
        assertEquals(Status.BUDGET_INCONCLUSIVE, guided.status());
        assertEquals(allowance, guided.work().consumedWorkUnits());
        assertTrue(guided.law().isEmpty());
    }

    @Test
    void usesTheSameCandidateSupportFromSourceHomogeneityAlone() {
        var transition = new Transition(new ExactMatrix(List.of(row(0, 1), row(1, 1))), new ExactVector(row(0, 0)));
        var task = new Request(transition, row(0, 1), 2, new Budget(10_000));
        var ordinary = solver.findFirst(task);
        var direct = solver.findFirstWithHomogeneousBlocks(task, List.of());
        var guided = solver.findFirstWithHomogeneousBlocks(task, List.of(new Monomial(Map.of("x0", 2)),
            new Monomial(Map.of("x0", 1, "x1", 1)), new Monomial(Map.of("x1", 2))));
        assertFalse(direct.usedSupport());
        assertEquals(ordinary.law().orElseThrow().proposal(), direct.law().orElseThrow().proposal());
        assertEquals(direct.law().orElseThrow().proposal(), guided.law().orElseThrow().proposal());
        assertTrue(direct.work().consumedWorkUnits() < ordinary.work().consumedWorkUnits());
        assertTrue(guided.work().consumedWorkUnits() >= direct.work().consumedWorkUnits());
    }

    @Test
    void retainsTheWholeBasisForAnAffineTransition() {
        var shift = new Transition(new ExactMatrix(List.of(row(1))), new ExactVector(row(1)));
        var task = new Request(shift, row(2), 2, new Budget(10_000));
        var direct = solver.findFirstWithHomogeneousBlocks(task, List.of());
        var guided = solver.findFirstWithHomogeneousBlocks(task, List.of(Monomial.variable("x0")));
        assertEquals(Status.COMPLETE, direct.status());
        assertEquals(Status.COMPLETE, guided.status());
        assertTrue(direct.law().isEmpty());
        assertTrue(guided.law().isEmpty());
        assertTrue(guided.work().consumedWorkUnits() > direct.work().consumedWorkUnits());
    }

    private static List<Rational> row(long... entries) {
        return java.util.Arrays.stream(entries).mapToObj(Rational::of).toList();
    }
}
