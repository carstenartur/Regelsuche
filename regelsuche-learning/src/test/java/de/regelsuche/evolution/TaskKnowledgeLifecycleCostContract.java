package de.regelsuche.evolution;

import java.util.List;

/** Synthetic timing contract: deliberately not performance evidence. */
final class TaskKnowledgeLifecycleCostContract {
    public static void main(String[] args) {
        actualAdoptionCheckpoint(null, 100, KnowledgeCostGate.Decision.NO_GAIN);
        actualAdoptionCheckpoint(TaskKnowledgeSelfImprover.CostScope.LEGACY_ATTEMPT, 100, KnowledgeCostGate.Decision.ADOPT);
        actualAdoptionCheckpoint(null, 100_000, KnowledgeCostGate.Decision.ADOPT);
        var previous = new KnowledgeCostGate.Sample(new KnowledgeCostGate.Cost(100, 100), true, 3, true);
        var candidate = new KnowledgeCostGate.Sample(new KnowledgeCostGate.Cost(10, 10), true, 3, true);
        var trials = List.of(new KnowledgeCostGate.Trial(previous, candidate));
        var attempt = new KnowledgeCostGate.Cost(200, 200);
        var entireRun = new KnowledgeCostGate.Cost(1_000_200, 1_000_200);
        if (TaskKnowledgeSelfImprover.adoptionDecision(TaskKnowledgeSelfImprover.CostScope.LEGACY_ATTEMPT,
                attempt, entireRun, trials, 100) != KnowledgeCostGate.Decision.ADOPT)
            throw new AssertionError("legacy per-attempt gain fixture");
        if (TaskKnowledgeSelfImprover.adoptionDecision(TaskKnowledgeSelfImprover.CostScope.IMPROVEMENT_RUN,
                attempt, entireRun, trials, 100) != KnowledgeCostGate.Decision.NO_GAIN)
            throw new AssertionError("earlier rejected costs must prevent unamortised later adoption");
        if (TaskKnowledgeSelfImprover.adoptionDecision(TaskKnowledgeSelfImprover.CostScope.IMPROVEMENT_RUN,
                attempt, entireRun, trials, 100_000) != KnowledgeCostGate.Decision.ADOPT)
            throw new AssertionError("sufficient horizon repays complete run");
        for (var scope : TaskKnowledgeSelfImprover.CostScope.values()) {
            long[] clock = {0};
            var improver = new TaskKnowledgeSelfImprover(() -> new KnowledgeCostGate.Cost(++clock[0], clock[0]));
            var batch = new TaskKnowledgeSelfImprover.Batch(
                List.of(MixedTaskKnowledgeWorkload.task("x+13", 1), MixedTaskKnowledgeWorkload.task("y+17", 1)),
                List.of(MixedTaskKnowledgeWorkload.task("z+19", 1)), List.of(MixedTaskKnowledgeWorkload.task("q+23", 1)));
            var result = improver.improve(CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory()),
                List.of(batch, batch), new TaskKnowledgeSelfImprover.Policy(5_000_000, Long.MAX_VALUE,
                    Long.MAX_VALUE, 2, 1, 128), scope);
            if (result.attempts().size() != 2) throw new AssertionError("two rejected attempts retained");
            var second = result.attempts().get(1);
            if (scope == TaskKnowledgeSelfImprover.CostScope.LEGACY_ATTEMPT) {
                if (!second.adoptionCost().equals(second.paid())) throw new AssertionError("legacy receipt cost");
            } else if (second.adoptionCost().wallNanos() < second.paid().wallNanos() + result.attempts().getFirst().paid().wallNanos())
                throw new AssertionError("actual second receipt omits earlier attempt");
        }
        System.out.println("PASS: rejected attempt costs prevent later unamortised adoption; explicit legacy replay, sufficient horizon, cumulative real receipts, actual candidate adoption checkpoint");
    }
    /** Null scope exercises the public default overload, not only its scope-selection helper. */
    private static void actualAdoptionCheckpoint(TaskKnowledgeSelfImprover.CostScope scope, long horizon,
            KnowledgeCostGate.Decision expected) {
        long[] marks = {0, 1, 2, 3, 1_000_000, 1_000_001, 1_000_002, 1_000_003,
            1_000_004, 1_000_005, 1_000_006, 1_000_007, 1_000_008, 1_000_108,
            1_000_109, 1_000_110, 1_000_111, 1_000_112};
        int[] cursor = {0};
        var improver = new TaskKnowledgeSelfImprover(() -> {
            int i = cursor[0]++;
            long value = i < marks.length ? marks[i] : marks[marks.length - 1] + i;
            return new KnowledgeCostGate.Cost(value, value);
        });
        var noop = new TaskKnowledgeSelfImprover.Batch(
            List.of(task("x+13", 1), task("y+17", 1)), List.of(task("z+19", 1)), List.of(task("q+23", 1)));
        // Existing useful(1) family: actual traces, schema proof, validation and fitted-policy execution.
        var useful = new TaskKnowledgeSelfImprover.Batch(
            List.of(task("((a+b)*(a-b)+b*b)+3", 5), task("5*((c+d)*(c-d)+d*d)", 5)),
            List.of(task("((p*q+v)*(p*q-v)+v*v)", 5), task("((z+w)*(z-w)+w*w)+7", 5)),
            List.of(task("((r+s+t)*(r+s-t)+t*t)", 5)));
        var initial = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
        var policy = new TaskKnowledgeSelfImprover.Policy(5_000_000, Long.MAX_VALUE, Long.MAX_VALUE, 2, 1, horizon);
        var batches = List.of(noop, useful);
        var result = scope == null ? improver.improve(initial, batches, policy)
            : improver.improve(initial, batches, policy, scope);
        require(result.accountingComplete() && !result.budgetExhausted(), "complete unexhausted integration run");
        require(result.attempts().size() == 2, "both actual attempts retained");
        var rejected = result.attempts().getFirst();
        var later = result.attempts().get(1);
        require(rejected.decision() == KnowledgeCostGate.Decision.NO_CANDIDATE && rejected.knownWork() > 0,
            "first real no-op remains paid and rejected");
        require(later.learnerDecision() == CumulativeTaskKnowledge.Decision.PROMOTED && !later.candidateId().isEmpty(),
            "second candidate genuinely promoted by mathematical learner");
        require(later.trials().size() == 1 && later.trials().getFirst().previous().solved()
            && later.trials().getFirst().candidate().solved(), "both actual promotion executions solve their task");
        require(KnowledgeCostGate.evaluate(later.paid(), later.trials(), 100) == KnowledgeCostGate.Decision.ADOPT,
            "short horizon repays later attempt alone");
        if (scope == null) {
            require(later.adoptionCost().wallNanos() >= rejected.paid().wallNanos() + later.paid().wallNanos(),
                "cumulative receipt contains rejected prefix");
            require(KnowledgeCostGate.evaluate(later.adoptionCost(), later.trials(), 100) == KnowledgeCostGate.Decision.NO_GAIN,
                "short horizon does not repay whole run");
        }
        require(later.decision() == expected, "actual final adoption checkpoint: expected " + expected + " got " + later.decision());
        require(result.active().id().equals(expected == KnowledgeCostGate.Decision.ADOPT ? later.candidateId() : initial.id()),
            "actual active generation follows final adoption decision");
        require(result.knownWork() == rejected.knownWork() + later.knownWork(), "no paid attempt work omitted");
    }
    private static CheckedSchemaTaskSolver.Task task(String source, int nodes) {
        return MixedTaskKnowledgeWorkload.task(source, nodes);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

}
