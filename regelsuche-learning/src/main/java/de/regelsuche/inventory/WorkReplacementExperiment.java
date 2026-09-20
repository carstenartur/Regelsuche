package de.regelsuche.inventory;

import static de.regelsuche.inventory.WorkReplacementManifest.*;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Orchestrates existing execution services. No search, learner or proof algorithm lives here. */
public final class WorkReplacementExperiment {
    public static final String REVISION = "regelsuche.work-replacement-experiment/v3";
    public static final String ACCOUNTING_REVISION = "regelsuche.work-replacement-experiment/v4-accounting";
    public enum Status { QUALITY_REACHED, QUALITY_UNREACHED, OVER_BUDGET, TIMEOUT, INVALID_PROOF, ERROR, NOT_RUN, ACCOUNTING_INCOMPLETE }
    public record Query(String id, String sourceIdentity, TypedMoveSearch.Problem problem, TypedSourceOnlySearch.Objective objective) {
        public Query {
            LifecycleWorkAccount.requireText(id); LifecycleWorkAccount.requireText(sourceIdentity);
            Objects.requireNonNull(problem); Objects.requireNonNull(objective);
        }
    }
    public record Evaluation(boolean qualityReached, boolean validProof, long inputScore, long outputScore,
            String outputIdentity, String rawReceipt, List<String> learnedWitnessIds) {
        public Evaluation {
            LifecycleWorkAccount.requireText(outputIdentity); Objects.requireNonNull(rawReceipt);
            learnedWitnessIds = List.copyOf(learnedWitnessIds);
        }
    }
    /** Callbacks append completed work before throwing. Unknown work explicitly invalidates the account. */
    public static final class Journal {
        private LifecycleWorkAccount account = LifecycleWorkAccount.empty();
        private boolean complete = true;
        public void append(LifecycleWorkAccount work) { account = account.plus(work); }
        public void incomplete() { complete = false; }
        public LifecycleWorkAccount account() { return account; }
        public boolean complete() { return complete; }
    }
    /** A process-backed query exceeded its deadline; killed work may be unobservable. */
    public static final class QueryTimeoutException extends RuntimeException {
        public QueryTimeoutException(String message, Throwable cause) { super(message, cause); }
    }
    @FunctionalInterface public interface Acquisition { void acquire(Journal journal); }
    @FunctionalInterface public interface SessionFactory { Session open(Journal journal, String receiptPrefix); }
    public interface Session extends AutoCloseable {
        Evaluation execute(Query query, long allocatedWork, Quality quality, Journal journal, String receiptPrefix);
        default Optional<Process> process() { return Optional.empty(); }
        @Override default void close() {}
    }
    public record Plan(Binding binding, Acquisition acquisition, LifecycleWorkAccount provisionedAcquisition, SessionFactory sessions) {
        public Plan { Objects.requireNonNull(binding); Objects.requireNonNull(acquisition); Objects.requireNonNull(sessions); }
    }
    public record Row(Arm arm, String queryId, Status status, long allocatedWork, long elapsedNanos,
            long processId, Evaluation evaluation, String detail) {}
    public record ArmResult(Arm arm, LifecycleWorkAccount account, boolean accountingComplete, long elapsedNanos, List<Row> rows) {
        public ArmResult { rows = List.copyOf(rows); }
        public boolean withinBudget(long budget) { return accountingComplete && account.totalWork() <= budget; }
    }
    public record Report(WorkReplacementManifest manifest, List<Query> queries, Map<Arm, ArmResult> arms) {
        public Report {
            queries = List.copyOf(queries); arms = Map.copyOf(arms);
            validateRows(queries, arms);
        }
        public String revision() {
            return arms.values().stream().flatMap(arm -> arm.rows().stream())
                .anyMatch(row -> row.status() == Status.ACCOUNTING_INCOMPLETE) ? ACCOUNTING_REVISION : REVISION;
        }
        public long successes(Arm arm) {
            if (arm == Arm.L_ORACLE) throw new IllegalArgumentException("oracle is diagnostic, never a learning success");
            var result = arms.get(arm);
            if (!result.withinBudget(manifest.resources().totalWork())) return 0;
            return result.rows().stream().filter(row -> row.status() == Status.QUALITY_REACHED).count();
        }
        public double runtimeRatio(Arm numerator, Arm denominator) {
            if (numerator == Arm.L_ORACLE || denominator == Arm.L_ORACLE) throw new IllegalArgumentException("oracle ratio excluded");
            if (manifest.profile() == Profile.PROVISIONED_MODEL || !arms.get(numerator).accountingComplete()
                    || !arms.get(denominator).accountingComplete())
                throw new IllegalStateException("unmeasured acquisition time or incomplete accounting prevents a lifecycle ratio");
            if (manifest.unsolvedPolicy() != UnsolvedPolicy.PENALIZE_AT_TIMEOUT)
                throw new IllegalStateException("runtime ratio needs declared unsolved-task treatment");
            return (double) penalized(arms.get(numerator)) / Math.max(1L, penalized(arms.get(denominator)));
        }
        private long penalized(ArmResult result) {
            long nanos = result.elapsedNanos();
            for (var row : result.rows()) if (row.status() != Status.QUALITY_REACHED)
                nanos = Math.addExact(nanos, Math.max(0, manifest.resources().queryTimeoutNanos() - row.elapsedNanos()));
            return nanos;
        }
    }
    private final Set<Session> usedSessions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Long> usedProcesses = new java.util.HashSet<>();

    public Report run(WorkReplacementManifest manifest, List<Query> queries, Map<Arm, Plan> plans) {
        validateInputs(manifest, queries, plans);
        var arms = new EnumMap<Arm, ArmResult>(Arm.class);
        for (var arm : Arm.values()) arms.put(arm, runArm(manifest, queries, arm, plans.get(arm)));
        return new Report(manifest, queries, arms);
    }
    private ArmResult runArm(WorkReplacementManifest manifest, List<Query> queries, Arm arm, Plan plan) {
        long start = System.nanoTime(); var journal = new Journal(); var rows = new ArrayList<Row>();
        try {
            if (manifest.profile() == Profile.PROVISIONED_MODEL) {
                if (plan.provisionedAcquisition() == null) throw new IllegalArgumentException("provisioned acquisition receipts required");
                journal.append(plan.provisionedAcquisition());
            } else plan.acquisition().acquire(journal);
            executeStream(manifest, queries, arm, plan, journal, rows);
        } catch (RuntimeException failure) {
            journal.incomplete();
            fillMissing(queries, arm, rows, failure.toString());
        }
        return new ArmResult(arm, journal.account(), journal.complete(), System.nanoTime() - start, rows);
    }
    private void executeStream(WorkReplacementManifest manifest, List<Query> queries, Arm arm, Plan plan,
            Journal journal, List<Row> rows) {
        Session session = null;
        try {
            for (int i = 0; i < queries.size(); i++) {
                if (stopIncomplete(queries, arm, journal, rows)) return;
                var query = queries.get(i); String prefix = arm + "/" + i;
                long remaining = Math.max(0, manifest.resources().totalWork() - journal.account().totalWork());
                if (remaining == 0) { rows.add(notRun(arm, query, "total lifecycle budget exhausted")); continue; }
                if (session == null) session = open(manifest.profile(), plan, journal, prefix);
                if (stopIncomplete(queries, arm, journal, rows)) return;
                remaining = Math.max(0, manifest.resources().totalWork() - journal.account().totalWork());
                long share = remaining / (queries.size() - i);
                rows.add(share == 0 ? notRun(arm, query, "no query share after restore")
                    : execute(manifest, arm, query, share, session, journal, prefix));
                if (stopIncomplete(queries, arm, journal, rows)) return;
                if (manifest.profile() == Profile.FRESH_PROCESS_PER_QUERY) { session.close(); session = null; }
            }
        } finally { if (session != null) session.close(); }
    }
    private static boolean stopIncomplete(List<Query> queries, Arm arm, Journal journal, List<Row> rows) {
        if (journal.complete()) return false;
        fillMissing(queries, arm, rows, "unobservable work; lifecycle stream stopped");
        return true;
    }
    private Session open(Profile profile, Plan plan, Journal journal, String prefix) {
        var session = Objects.requireNonNull(plan.sessions().open(journal, prefix), "session");
        try {
            if (!usedSessions.add(session)) throw new IllegalArgumentException("fresh execution session required");
            if (profile == Profile.FRESH_PROCESS_PER_QUERY) requireFreshProcess(session);
            return session;
        } catch (RuntimeException failure) { session.close(); throw failure; }
    }
    private void requireFreshProcess(Session session) {
        var process = session.process().orElseThrow(() -> new IllegalArgumentException("fresh profile requires a live child process"));
        if (!process.isAlive() || process.pid() == ProcessHandle.current().pid() || !usedProcesses.add(process.pid()))
            throw new IllegalArgumentException("fresh child process required per query");
    }
    private static Row execute(WorkReplacementManifest manifest, Arm arm, Query query, long share, Session session,
            Journal journal, String prefix) {
        long start = System.nanoTime(), before = journal.account().totalWork();
        try {
            var result = session.execute(query, share, manifest.quality(), journal, prefix);
            long elapsed = System.nanoTime() - start;
            long spent = Math.subtractExact(journal.account().totalWork(), before);
            var status = journal.complete() ? status(result, spent > share, elapsed > manifest.resources().queryTimeoutNanos())
                : Status.ACCOUNTING_INCOMPLETE;
            return new Row(arm, query.id(), status, share, elapsed, session.process().map(Process::pid).orElse(ProcessHandle.current().pid()), result, "");
        } catch (QueryTimeoutException failure) {
            journal.incomplete();
            return new Row(arm, query.id(), Status.TIMEOUT, share, System.nanoTime() - start,
                session.process().map(Process::pid).orElse(-1L), null, failure.toString());
        } catch (RuntimeException failure) {
            journal.incomplete();
            return new Row(arm, query.id(), Status.ERROR, share, System.nanoTime() - start, -1, null, failure.toString());
        }
    }
    private static Status status(Evaluation result, boolean overBudget, boolean timeout) {
        if (!result.validProof()) return Status.INVALID_PROOF;
        if (timeout) return Status.TIMEOUT;
        if (overBudget) return Status.OVER_BUDGET;
        return result.qualityReached() ? Status.QUALITY_REACHED : Status.QUALITY_UNREACHED;
    }
    private static void validateInputs(WorkReplacementManifest manifest, List<Query> queries, Map<Arm, Plan> plans) {
        if (queries.isEmpty() || queries.stream().map(Query::id).distinct().count() != queries.size())
            throw new IllegalArgumentException("distinct nonempty query stream required");
        for (var arm : Arm.values()) manifest.validateBinding(arm, Objects.requireNonNull(plans.get(arm), "missing arm").binding());
        for (var query : queries) {
            if (query.problem().context().phase() != de.regelsuche.search.moves.MoveContext.Phase.FROZEN_EVALUATION)
                throw new IllegalArgumentException("evaluation requires FROZEN_EVALUATION context");
            var partition = manifest.partitions().stream().filter(value -> value.id().equals(query.id())).findFirst().orElseThrow();
            if (partition.split() == Split.TRAIN || !partition.sourceIdentity().equals(query.sourceIdentity())
                    || !query.sourceIdentity().equals(new de.regelsuche.search.program.CompiledAstReplayCodec().encodeExpression(query.problem().source())))
                throw new IllegalArgumentException("query partition mismatch");
            if (query.problem().budget().maxStates() > manifest.resources().maxStates()
                    || query.problem().budget().maxSearchDepth() > manifest.resources().maxDepth())
                throw new IllegalArgumentException("query resource mismatch");
        }
    }
    private static void validateRows(List<Query> queries, Map<Arm, ArmResult> arms) {
        for (var arm : Arm.values()) {
            var result = Objects.requireNonNull(arms.get(arm), "missing arm rows");
            if (result.arm() != arm || result.rows().size() != queries.size()) throw new IllegalArgumentException("incomplete result matrix");
            for (int i = 0; i < queries.size(); i++) if (result.rows().get(i).arm() != arm
                    || !result.rows().get(i).queryId().equals(queries.get(i).id())) throw new IllegalArgumentException("missing or reordered row");
        }
    }
    private static Row notRun(Arm arm, Query query, String reason) { return new Row(arm, query.id(), Status.NOT_RUN, 0, 0, -1, null, reason); }
    private static void fillMissing(List<Query> queries, Arm arm, List<Row> rows, String reason) {
        for (int i = rows.size(); i < queries.size(); i++) rows.add(notRun(arm, queries.get(i), reason));
    }
}
