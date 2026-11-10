package de.regelsuche.evolution;

import static de.regelsuche.evolution.KnowledgeCostGate.Decision.*;

import de.regelsuche.evolution.KnowledgeCostGate.Cost;
import de.regelsuche.evolution.KnowledgeCostGate.Decision;
import de.regelsuche.evolution.KnowledgeCostGate.Sample;
import de.regelsuche.evolution.KnowledgeCostGate.Trial;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/** Actual-cost adoption around the existing learner; all rejected work remains in the returned ledger. */
public final class TaskKnowledgeSelfImprover {
    /** Legacy scope is solely for replaying pre-v2 caller-supplied studies. */
    public enum CostScope { IMPROVEMENT_RUN, LEGACY_ATTEMPT }
    public record Batch(List<CheckedSchemaTaskSolver.Task> lessons, List<CheckedSchemaTaskSolver.Task> validation,
            List<CheckedSchemaTaskSolver.Task> promotion) {
        public Batch {
            lessons = bounded(lessons, 2); validation = bounded(validation, 1); promotion = bounded(promotion, 1);
        }
    }
    /** Time/work cutoffs are checked between bounded atomic API operations, which remain fully paid. */
    public record Policy(long maximumLearningWork, long maximumWallNanos, long maximumCpuNanos,
            int maximumBatches, int measurementRepeats, long futureQueries) {
        public Policy {
            if (maximumLearningWork < 1 || maximumWallNanos < 1 || maximumCpuNanos < 1
                    || maximumBatches < 1 || maximumBatches > 32 || measurementRepeats < 1 || measurementRepeats > 9 || futureQueries < 1)
                throw new IllegalArgumentException("positive bounded improvement policy required");
        }
    }
    public record Attempt(String parentId, String candidateId, CumulativeTaskKnowledge.Decision learnerDecision,
            Decision decision, long knownWork, Cost paid, List<Trial> trials, List<String> lessonGenerations,
            boolean accountingComplete, String failure, Cost adoptionCost) {
        public Attempt {
            trials = List.copyOf(trials); lessonGenerations = List.copyOf(lessonGenerations);
            Objects.requireNonNull(adoptionCost);
        }
        /** Source compatibility for callers constructing old per-attempt receipts. */
        public Attempt(String parentId, String candidateId, CumulativeTaskKnowledge.Decision learnerDecision,
                Decision decision, long knownWork, Cost paid, List<Trial> trials, List<String> lessonGenerations,
                boolean accountingComplete, String failure) {
            this(parentId, candidateId, learnerDecision, decision, knownWork, paid, trials, lessonGenerations,
                accountingComplete, failure, paid);
        }
    }
    public record Result(CumulativeTaskKnowledge active, List<Attempt> attempts, long knownWork,
            Cost paid, boolean accountingComplete, boolean budgetExhausted) {
        public Result { attempts = List.copyOf(attempts); }
    }
    @FunctionalInterface interface Meter { Cost now(); }
    private final Meter meter;

    public TaskKnowledgeSelfImprover() { this(systemMeter()); }
    TaskKnowledgeSelfImprover(Meter meter) { this.meter = Objects.requireNonNull(meter); }

    /** Promotion predicts future savings; an independently measured stream is still required to establish them. */
    public Result improve(CumulativeTaskKnowledge initial, List<Batch> batches, Policy policy) {
        return improve(initial, batches, policy, CostScope.IMPROVEMENT_RUN);
    }

    /** New runs repay all attempts; explicit legacy mode retains historical selection semantics. */
    public Result improve(CumulativeTaskKnowledge initial, List<Batch> batches, Policy policy, CostScope costScope) {
        Objects.requireNonNull(costScope);
        Objects.requireNonNull(initial); Objects.requireNonNull(policy); batches = List.copyOf(batches);
        if (batches.size() > 32) throw new IllegalArgumentException("bounded improvement batches required");
        Cost start = meter.now();
        var attempts = new ArrayList<Attempt>();
        var examples = batches.stream().flatMap(b -> Stream.concat(b.lessons().stream(), b.validation().stream())).toList();
        var active = initial;
        long work = 0;
        boolean complete = start.cpuNanos() >= 0, exhausted = false;
        for (int i = 0; complete && i < Math.min(batches.size(), policy.maximumBatches()); i++) {
            Cost checkpoint = elapsed(start, meter.now());
            if (overBudget(policy, checkpoint, work)) { exhausted = true; break; }
            var step = new Step(active, batches.get(i), examples, policy, start, work, i, costScope);
            var attempt = step.execute();
            if (attempt.decision() == ADOPT) {
                // Observe all inner gate work before committing a generation.
                // The outer result retains the remaining controller overhead.
                Cost point = meter.now();
                Cost paid = elapsed(step.start, point);
                boolean accounted = attempt.accountingComplete() && paid.cpuNanos() >= 0;
                Decision decision = !accounted ? INCOMPLETE
                    : overBudget(policy, elapsed(start, point), Math.addExact(work, attempt.knownWork())) ? BUDGET_EXHAUSTED
                    : adoptionDecision(costScope, paid, elapsed(start, point), attempt.trials(), policy.futureQueries());
                attempt = new Attempt(attempt.parentId(), attempt.candidateId(), attempt.learnerDecision(), decision,
                    attempt.knownWork(), paid, attempt.trials(), attempt.lessonGenerations(), accounted, attempt.failure(),
                    adoptionCost(costScope, paid, elapsed(start, point)));
            }
            attempts.add(attempt); work = Math.addExact(work, attempt.knownWork());
            complete &= attempt.accountingComplete();
            if (attempt.decision() == ADOPT) active = step.proposed;
            if (attempt.decision() == BUDGET_EXHAUSTED) { exhausted = true; break; }
        }
        Cost paid = elapsed(start, meter.now());
        return new Result(active, attempts, work, paid, complete && paid.cpuNanos() >= 0,
            exhausted || overBudget(policy, paid, work));
    }

    private final class Step {
        private final CumulativeTaskKnowledge parent;
        private final Batch batch;
        private final List<CheckedSchemaTaskSolver.Task> examples;
        private final Policy policy;
        private final Cost overallStart, start;
        private final long previousWork;
        private final int sequence;
        private final CostScope costScope;
        private final List<Trial> trials = new ArrayList<>();
        private List<String> lessons = List.of();
        private long work;
        private boolean complete = true;
        private String failure = "";
        private CumulativeTaskKnowledge proposed;
        private CumulativeTaskKnowledge.Decision learnerDecision;
        private Decision decision = NO_CANDIDATE;
        private Cost lastSampleEnd;

        Step(CumulativeTaskKnowledge parent, Batch batch, List<CheckedSchemaTaskSolver.Task> examples,
                Policy policy, Cost overallStart, long previousWork, int sequence, CostScope costScope) {
            this.parent = parent; this.batch = batch; this.examples = examples; this.policy = policy;
            this.overallStart = overallStart; this.previousWork = previousWork; this.sequence = sequence;
            this.costScope = costScope;
            start = meter.now();
        }

        Attempt execute() {
            try {
                // Invalid later batches must retain earlier accepted state and all paid receipts.
                work = CumulativeTaskKnowledge.disjoint(examples, batch.promotion());
                Cost checkpoint = elapsed(overallStart, meter.now());
                if (checkpoint.cpuNanos() < 0) { complete = false; decision = INCOMPLETE; }
                else if (overBudget(policy, checkpoint, Math.addExact(previousWork, work))) decision = BUDGET_EXHAUSTED;
                else learn();
            } catch (CumulativeTaskKnowledge.BatchFailure error) {
                work = Math.addExact(work, error.knownWork().total());
                lessons = error.completedLessons().stream().map(CumulativeTaskKnowledge.Execution::generationId).toList();
                fail(error);
            } catch (RuntimeException error) { fail(error); }
            Cost point = meter.now();
            Cost paid = elapsed(start, point);
            if (complete && proposed != null && !trials.isEmpty() && decision != BUDGET_EXHAUSTED)
                decision = KnowledgeCostGate.evaluate(paid, trials, policy.futureQueries());
            if (complete && overBudget(policy, elapsed(overallStart, point), Math.addExact(previousWork, work))) decision = BUDGET_EXHAUSTED;
            Cost end = meter.now();
            paid = elapsed(start, end);
            // Include the decision's own measured work when testing its projected payback.
            if (decision == ADOPT) decision = KnowledgeCostGate.evaluate(paid, trials, policy.futureQueries());
            if (complete && overBudget(policy, elapsed(overallStart, end), Math.addExact(previousWork, work))) decision = BUDGET_EXHAUSTED;
            complete &= paid.cpuNanos() >= 0 && trials.stream().allMatch(t -> t.previous().complete() && t.candidate().complete());
            return new Attempt(parent.id(), proposed == null ? "" : proposed.id(), learnerDecision, decision,
                work, paid, trials, lessons, complete, failure, adoptionCost(costScope, paid, elapsed(overallStart, end)));
        }

        private void learn() {
            long remaining = policy.maximumLearningWork() - previousWork - work;
            var update = parent.learnBatch(batch.lessons(), batch.validation(), remaining);
            work = Math.addExact(work, update.work().total()); learnerDecision = update.reason();
            lessons = update.training().stream().map(CumulativeTaskKnowledge.Execution::generationId).toList();
            if (Math.addExact(previousWork, work) >= policy.maximumLearningWork()) { decision = BUDGET_EXHAUSTED; return; }
            if (!update.promoted()) return;
            proposed = update.next();
            for (int repetition = 0; repetition < policy.measurementRepeats(); repetition++) {
                for (int i = 0; i < batch.promotion().size(); i++) {
                    if (overBudget(policy, elapsed(overallStart, meter.now()), Math.addExact(previousWork, work))) {
                        decision = BUDGET_EXHAUSTED; return;
                    }
                    trial(batch.promotion().get(i), ((sequence + repetition + i) & 1) == 0);
                    if (!complete) { decision = INCOMPLETE; return; }
                    if (decision == BUDGET_EXHAUSTED) return;
                }
            }
        }

        private void trial(CheckedSchemaTaskSolver.Task task, boolean previousFirst) {
            Sample before, after;
            if (previousFirst) {
                before = observe(parent, task);
                if (stoppedAfterSample()) return;
                after = observe(proposed, task);
            } else {
                after = observe(proposed, task);
                if (stoppedAfterSample()) return;
                before = observe(parent, task);
            }
            trials.add(new Trial(before, after));
        }

        private boolean stoppedAfterSample() {
            if (!complete) { decision = INCOMPLETE; return true; }
            if (overBudget(policy, elapsed(overallStart, lastSampleEnd), Math.addExact(previousWork, work))) {
                decision = BUDGET_EXHAUSTED; return true;
            }
            return false;
        }

        private Sample observe(CumulativeTaskKnowledge knowledge, CheckedSchemaTaskSolver.Task task) {
            Cost begin = meter.now();
            var execution = knowledge.solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            lastSampleEnd = meter.now();
            Cost paid = elapsed(begin, lastSampleEnd);
            work = Math.addExact(work, execution.totalWork());
            long score = execution.solution().map(s -> s.evidence().outputScore()).orElse(Long.MAX_VALUE);
            boolean accounted = execution.solution().map(s -> s.evidence().accountingComplete()).orElse(true);
            complete &= accounted && paid.cpuNanos() >= 0;
            return new Sample(paid, execution.solved(), score, accounted);
        }

        private void fail(RuntimeException error) { complete = false; decision = FAILED; failure = error.toString(); }
    }

    static Decision adoptionDecision(CostScope scope, Cost attemptPaid, Cost runPaid, List<Trial> trials, long horizon) {
        return KnowledgeCostGate.evaluate(adoptionCost(scope, attemptPaid, runPaid), trials, horizon);
    }
    private static Cost adoptionCost(CostScope scope, Cost attemptPaid, Cost runPaid) {
        return scope == CostScope.LEGACY_ATTEMPT ? attemptPaid : runPaid;
    }

    private static boolean overBudget(Policy policy, Cost elapsed, long work) {
        return work >= policy.maximumLearningWork() || elapsed.wallNanos() >= policy.maximumWallNanos()
            || elapsed.cpuNanos() >= policy.maximumCpuNanos();
    }
    private static Cost elapsed(Cost start, Cost end) {
        long cpu = start.cpuNanos() < 0 || end.cpuNanos() < start.cpuNanos() ? -1 : end.cpuNanos() - start.cpuNanos();
        return new Cost(end.wallNanos() - start.wallNanos(), cpu);
    }
    private static List<CheckedSchemaTaskSolver.Task> bounded(List<CheckedSchemaTaskSolver.Task> tasks, int minimum) {
        var copy = List.copyOf(tasks);
        if (copy.size() < minimum || copy.size() > 32) throw new IllegalArgumentException("bounded task list required");
        return copy;
    }
    static Meter systemMeter() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        return systemMeter(System::nanoTime,
            os instanceof com.sun.management.OperatingSystemMXBean bean ? bean::getProcessCpuTime : () -> -1);
    }

    static Meter systemMeter(LongSupplier wallClock, LongSupplier cpuClock) {
        long origin = wallClock.getAsLong();
        // nanoTime has an arbitrary signed origin. Subtraction also preserves short durations
        // across signed wraparound; only the elapsed value belongs in a nonnegative Cost.
        return () -> new Cost(wallClock.getAsLong() - origin, cpuClock.getAsLong());
    }
}
