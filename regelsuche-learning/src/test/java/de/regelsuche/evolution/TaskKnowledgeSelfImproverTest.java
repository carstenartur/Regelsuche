package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskKnowledgeSelfImproverTest {
    private static CheckedSchemaTaskSolver.Task task(String source, int goal) { return TaskKnowledgeSessionTest.task(source, goal); }
    private static String nested(String source, int depth, String prefix) { return CumulativeTaskKnowledgeContract.nested(source, depth, prefix); }
    private static TaskKnowledgeSelfImprover.Batch useful(int depth) {
        int goal = 2 * depth + 3;
        return new TaskKnowledgeSelfImprover.Batch(
            List.of(task(nested("a", depth, "b") + "+3", goal), task("5*(" + nested("c", depth, "d") + ")", goal)),
            List.of(task(nested("p*q", depth, "v"), goal), task(nested("z", depth, "w") + "+7", goal)),
            List.of(task(nested("r+s", depth, "t"), goal)));
    }
    private static TaskKnowledgeSelfImprover.Policy policy(long work, int batches) {
        return new TaskKnowledgeSelfImprover.Policy(work, Long.MAX_VALUE, Long.MAX_VALUE, batches, 1, 1_000_000);
    }
    private static CumulativeTaskKnowledge empty() { return CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory()); }

    @Test void noOpLearningIsPaidAndCannotChangeActiveKnowledge() {
        var initial = empty();
        var batch = new TaskKnowledgeSelfImprover.Batch(List.of(task("x+13", 1), task("y+17", 1)),
            List.of(task("z+19", 1)), List.of(task("q+23", 1)));
        var result = new TaskKnowledgeSelfImprover().improve(initial, List.of(batch), policy(1_000_000, 2));
        assertSame(initial, result.active()); assertEquals(1, result.attempts().size());
        assertEquals(KnowledgeCostGate.Decision.NO_CANDIDATE, result.attempts().getFirst().decision());
        assertTrue(result.knownWork() > 0); assertTrue(result.paid().wallNanos() > 0);
        assertEquals(initial.id(), result.attempts().getFirst().parentId());
    }

    @Test void promotionInputsMustBePolynomialDisjointBeforeLearning() {
        var batch = useful(1);
        var overlap = new TaskKnowledgeSelfImprover.Batch(batch.lessons(), batch.validation(),
            List.of(task(nested("h", 1, "i") + "+3", 5)));
        var initial = empty();
        var result = new TaskKnowledgeSelfImprover().improve(initial, List.of(overlap), policy(1_000_000, 2));
        assertSame(initial, result.active()); assertFalse(result.accountingComplete());
        assertEquals(KnowledgeCostGate.Decision.FAILED, result.attempts().getFirst().decision());
        assertNull(result.attempts().getFirst().learnerDecision());
        assertFalse(result.attempts().getFirst().failure().isEmpty());
        assertTrue(result.paid().wallNanos() > 0);
    }

    @Test void anInvalidLaterSplitRetainsEarlierAcceptedKnowledgeAndPaidReceipts() {
        var second = useful(2);
        var overlap = new TaskKnowledgeSelfImprover.Batch(second.lessons(), second.validation(),
            List.of(second.lessons().getFirst()));
        var result = scripted(0, 1, 2, 3, 4, 5, 105, 106, 107, 108, 109, 110, 111)
            .improve(empty(), List.of(useful(1), overlap), policy(5_000_000, 2));
        assertEquals(2, result.attempts().size());
        var accepted = result.attempts().getFirst();
        var failed = result.attempts().get(1);
        assertEquals(KnowledgeCostGate.Decision.ADOPT, accepted.decision());
        assertEquals(accepted.candidateId(), result.active().id());
        assertEquals(accepted.candidateId(), failed.parentId());
        assertEquals(KnowledgeCostGate.Decision.FAILED, failed.decision());
        assertNull(failed.learnerDecision());
        assertFalse(failed.failure().isEmpty()); assertFalse(result.accountingComplete());
        assertEquals(accepted.knownWork() + failed.knownWork(), result.knownWork());
        assertTrue(result.paid().wallNanos() >= accepted.paid().wallNanos() + failed.paid().wallNanos());
        assertTrue(accepted.knownWork() > 0);
    }

    private static TaskKnowledgeSelfImprover.Batch countingLessons(int[] evaluations) {
        var batch = useful(1);
        var first = batch.lessons().getFirst();
        var goal = new CheckedSchemaTaskSolver.Goal(state -> {
            evaluations[0]++; return new de.regelsuche.search.moves.TypedSourceOnlySearch.Score(0, 1);
        }, 0);
        var counted = new CheckedSchemaTaskSolver.Task(first.source(), first.assumptions(), goal, first.budget());
        return new TaskKnowledgeSelfImprover.Batch(List.of(counted, batch.lessons().get(1)), batch.validation(), batch.promotion());
    }

    @Test void anObservedWallOrCpuDeadlineAfterSplitStopsBeforeLearning() {
        for (boolean wall : List.of(true, false)) {
            int[] evaluations = {0};
            var bounded = new TaskKnowledgeSelfImprover.Policy(5_000_000,
                wall ? 50 : Long.MAX_VALUE, wall ? Long.MAX_VALUE : 50, 1, 1, 1_000_000);
            var initial = empty();
            var result = scripted(0, 1, 100, 101, 102, 103, 104).improve(initial, List.of(countingLessons(evaluations)), bounded);
            assertEquals(0, evaluations[0]);
            assertSame(initial, result.active()); assertTrue(result.budgetExhausted());
            var attempt = result.attempts().getFirst();
            assertEquals(KnowledgeCostGate.Decision.BUDGET_EXHAUSTED, attempt.decision());
            assertNull(attempt.learnerDecision()); assertTrue(attempt.knownWork() > 0);
            assertTrue(result.paid().wallNanos() >= 101);
        }
    }

    @Test void cpuBecomingUnavailableAfterSplitStopsBeforeLearningAndRetainsItsCosts() {
        int[] evaluations = {0}, calls = {0};
        var improver = new TaskKnowledgeSelfImprover(() -> {
            int mark = calls[0]++;
            return new KnowledgeCostGate.Cost(mark, mark >= 3 ? -1 : mark);
        });
        var initial = empty();
        var result = improver.improve(initial, List.of(countingLessons(evaluations)), policy(5_000_000, 1));
        assertEquals(0, evaluations[0]); assertSame(initial, result.active());
        assertFalse(result.accountingComplete()); assertFalse(result.budgetExhausted());
        assertEquals(-1, result.paid().cpuNanos()); assertTrue(result.paid().wallNanos() > 0);
        var attempt = result.attempts().getFirst();
        assertEquals(KnowledgeCostGate.Decision.INCOMPLETE, attempt.decision());
        assertNull(attempt.learnerDecision()); assertTrue(attempt.knownWork() > 0);
    }

    @Test void spentSplitAndLearningBudgetCannotBeRefundedIntoAnotherBatch() {
        var initial = empty();
        var result = new TaskKnowledgeSelfImprover().improve(initial, List.of(useful(1), useful(2)), policy(1, 2));
        assertSame(initial, result.active()); assertTrue(result.budgetExhausted());
        assertTrue(result.knownWork() > 1); assertEquals(1, result.attempts().size());
        assertEquals(KnowledgeCostGate.Decision.BUDGET_EXHAUSTED, result.attempts().getFirst().decision());
    }

    @Test void controlledMeasurementsExerciseRealAdoptionAndAcceptedParentChaining() {
        // Synthetic clock inputs test state transitions, never performance evidence.
        long[] marks = {0, 1, 2, 3, 4, 5, 105, 106, 107, 108, 109, 110};
        int[] cursor = {0};
        TaskKnowledgeSelfImprover.Meter meter = () -> {
            int index = cursor[0]++;
            long value = index < marks.length ? marks[index] : 110L + index;
            return new KnowledgeCostGate.Cost(value, value);
        };
        var initial = empty();
        var result = new TaskKnowledgeSelfImprover(meter).improve(initial, List.of(useful(1), useful(2)), policy(5_000_000, 2));
        assertEquals(KnowledgeCostGate.Decision.ADOPT, result.attempts().getFirst().decision());
        assertEquals(result.attempts().getFirst().candidateId(), result.attempts().get(1).parentId());
        assertTrue(result.active().schemaCount() > 0);
        for (var attempt : result.attempts())
            assertTrue(attempt.lessonGenerations().stream().allMatch(attempt.parentId()::equals));
        assertEquals(0, initial.schemaCount());
    }

    @Test void unavailableCpuRetainsCostsAndPreventsCostBasedAdoption() {
        long[] clock = {0};
        var improver = new TaskKnowledgeSelfImprover(() -> new KnowledgeCostGate.Cost(++clock[0], -1));
        var initial = empty();
        var result = improver.improve(initial, List.of(useful(1)), policy(5_000_000, 1));
        assertSame(initial, result.active()); assertFalse(result.accountingComplete());
        assertEquals(-1, result.paid().cpuNanos());
    }

    private static TaskKnowledgeSelfImprover scripted(long... marks) {
        int[] cursor = {0};
        return new TaskKnowledgeSelfImprover(() -> {
            int index = cursor[0]++;
            long value = index < marks.length ? marks[index] : marks[marks.length - 1] + index;
            return new KnowledgeCostGate.Cost(value, value);
        });
    }

    @Test void aDeadlineBetweenPairedExecutionsStopsBeforeTheSecondSolve() {
        int[] evaluations = {0};
        var objective = new CheckedSchemaTaskSolver.Goal(state -> {
            evaluations[0]++; return new de.regelsuche.search.moves.TypedSourceOnlySearch.Score(0, 1);
        }, 0);
        var promotion = new CheckedSchemaTaskSolver.Task(task("s+23", 1).source(), List.of(), objective, task("s+23", 1).budget());
        var batch = useful(1);
        batch = new TaskKnowledgeSelfImprover.Batch(batch.lessons(), batch.validation(), List.of(promotion));
        var bounded = new TaskKnowledgeSelfImprover.Policy(5_000_000, 80, Long.MAX_VALUE, 1, 1, 1_000_000);
        var initial = empty();
        var result = scripted(0, 1, 2, 3, 4, 5, 105, 106, 107, 108, 109).improve(initial, List.of(batch), bounded);
        assertEquals(1, evaluations[0]);
        assertSame(initial, result.active()); assertTrue(result.budgetExhausted());
        assertEquals(KnowledgeCostGate.Decision.BUDGET_EXHAUSTED, result.attempts().getFirst().decision());
    }

    @Test void observedDeadlineAtDecisionCompletionPreventsAdoption() {
        var bounded = new TaskKnowledgeSelfImprover.Policy(5_000_000, 500, Long.MAX_VALUE, 1, 1, 1_000_000);
        var initial = empty();
        var result = scripted(0, 1, 2, 3, 4, 5, 105, 106, 107, 108, 1000, 1001).improve(initial, List.of(useful(1)), bounded);
        assertSame(initial, result.active()); assertTrue(result.budgetExhausted());
        assertEquals(KnowledgeCostGate.Decision.BUDGET_EXHAUSTED, result.attempts().getFirst().decision());
    }

    @Test void aTechnicalFailureRetainsCompletedLessonsAndUnknownWorkStatus() {
        var first = useful(1).lessons().getFirst();
        var brokenGoal = new CheckedSchemaTaskSolver.Goal(state -> { throw new IllegalStateException("failed objective"); }, 1);
        var broken = new CheckedSchemaTaskSolver.Task(task("y+17", 1).source(), List.of(), brokenGoal, first.budget());
        var batch = new TaskKnowledgeSelfImprover.Batch(List.of(first, broken), List.of(task("z+19", 1)), List.of(task("q+23", 1)));
        var initial = empty();
        var result = new TaskKnowledgeSelfImprover().improve(initial, List.of(batch, useful(1)), policy(5_000_000, 2));
        assertSame(initial, result.active()); assertFalse(result.accountingComplete());
        assertEquals(1, result.attempts().size()); assertTrue(result.knownWork() > 0);
        var attempt = result.attempts().getFirst();
        assertEquals(KnowledgeCostGate.Decision.FAILED, attempt.decision());
        assertEquals(List.of(initial.id()), attempt.lessonGenerations());
        assertFalse(attempt.failure().isEmpty());
    }
}
