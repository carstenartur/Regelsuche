package de.regelsuche.evolution;

import de.regelsuche.ast.Expr;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.NativeNodeCountObjective;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Bounded, caller-owned positive path reuse for one immutable knowledge generation.
 * First success retains only a query observation; the second may retain its path.
 * Later hits execute fresh regular candidate verification and final selected-path replay.
 * Repeated-query savings are reuse of earlier search, not transfer to unseen mathematics.
 */
public final class TaskKnowledgeQuerySession implements AutoCloseable {
    /** Lookup work is already included in execution.selectionWork; retention is an additional paid charge. */
    public record Query(CumulativeTaskKnowledge.Execution execution, boolean reused, long lookupWork, long retentionWork) {
        public long totalWork() { return Math.addExact(execution.totalWork(), retentionWork); }
        public boolean solved() { return execution.solved() && totalWork() <= execution.task().budget().totalWork(); }
    }
    /** Character limits measure retained logical data, not JVM heap bytes. Failed work is a lower bound. */
    public record Statistics(long queries, long hits, long misses, long bypasses, long observations, long admissions, long failures,
            long evictions, int retainedQueries, int retainedObservations, long retainedCharacters, long knownWork, boolean accountingComplete) { }
    public static final class QueryFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final long knownWork;
        QueryFailure(long knownWork, RuntimeException cause) {
            super("query failed; unfinished work may be unknown", cause); this.knownWork = knownWork;
        }
        public long knownWork() { return knownWork; }
        public boolean accountingComplete() { return false; }
    }
    private record Key(Expr source, List<String> assumptions, TypedSourceOnlySearch.Objective objective,
            long maximumScore, MoveSearch.Budget budget, CumulativeTaskKnowledge.Strategy strategy) implements RetainedGraph.View {
        static Key of(CheckedSchemaTaskSolver.Task task, CumulativeTaskKnowledge.Strategy strategy) {
            return new Key(task.source(), AssumptionSignature.ofExpressions(task.assumptions()).normalizedAssumptions(),
                task.goal().objective(), task.goal().maximumOutputScore(), task.budget(), strategy);
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(source); visitor.reference(assumptions); visitor.reference(budget);
            // Only explicitly admitted stateless built-in objective and strategy enums enter keys.
        }
    }
    private record Entry(RetainedTaskPath path, long characters) { }
    private record Footprint(long characters, long work) { }
    private final CumulativeTaskKnowledge knowledge;
    private final int maximumQueries;
    private final long maximumCharacters;
    private final LinkedHashMap<Key, Entry> retained = new LinkedHashMap<>(16, .75f, true);
    private long queries, hits, misses, bypasses, observations, admissions, failures, evictions, characters, knownWork;
    private int retainedPaths;
    private boolean closed, complete = true;

    /** Zero capacity disables reuse and keeps the existing persistent solver's work contract. */
    public TaskKnowledgeQuerySession(CumulativeTaskKnowledge knowledge, int maximumQueries, long maximumCharacters) {
        this.knowledge = Objects.requireNonNull(knowledge);
        if (maximumQueries < 0 || maximumQueries > 256 || maximumCharacters < 0)
            throw new IllegalArgumentException("nonnegative bounded query capacity required");
        this.maximumQueries = maximumQueries; this.maximumCharacters = maximumCharacters;
    }

    public synchronized Query solve(CheckedSchemaTaskSolver.Task task, CumulativeTaskKnowledge.Strategy strategy) {
        if (closed) throw new IllegalStateException("query session is closed");
        Objects.requireNonNull(task); Objects.requireNonNull(strategy);
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("query interrupted before execution");
        queries = Math.addExact(queries, 1);
        long completedWork = 0;
        Key key = null;
        try {
            if (!cacheable(task)) {
                bypasses = Math.addExact(bypasses, 1);
                var execution = knowledge.solve(task, strategy);
                completedWork = execution.totalWork();
                complete &= accounted(execution);
                checkInterrupted();
                knownWork = Math.addExact(knownWork, completedWork);
                return new Query(execution, false, 0, 0);
            }
            completedWork = 1;
            key = Key.of(task, strategy);
            var previous = retained.get(key);
            boolean reused = previous != null && previous.path() != null;
            if (reused) hits = Math.addExact(hits, 1); else misses = Math.addExact(misses, 1);
            var execution = knowledge.solveInSession(task, strategy, 1, previous == null ? null : previous.path());
            completedWork = execution.totalWork();
            complete &= accounted(execution);
            checkInterrupted();
            long retentionWork = 0;
            if (!reused && execution.solved() && !execution.solution().orElseThrow().evidence().witness().isEmpty()) {
                retentionWork = previous == null ? observe(key, execution) : prepare(key, execution);
                completedWork = Math.addExact(completedWork, retentionWork);
                checkInterrupted();
            } else if (previous != null && !execution.solved()) {
                discard(retained.remove(key));
                retentionWork = 1; completedWork = Math.addExact(completedWork, 1);
            }
            knownWork = Math.addExact(knownWork, completedWork);
            return new Query(execution, reused, 1, retentionWork);
        } catch (RuntimeException failure) {
            if (key != null && retained.containsKey(key)) {
                discard(retained.remove(key)); completedWork = Math.addExact(completedWork, 1);
            }
            failures = Math.addExact(failures, 1);
            knownWork = Math.addExact(knownWork, completedWork);
            throw new QueryFailure(completedWork, failure);
        }
    }

    private static boolean accounted(CumulativeTaskKnowledge.Execution execution) {
        return execution.solution().map(solution -> solution.evidence().accountingComplete()).orElse(true);
    }

    /** The existing typed input encoding conservatively bounds the retained AST's scalar/text characters. */
    private long observe(Key key, CumulativeTaskKnowledge.Execution execution) {
        long work = 1, size = 0;
        for (var state : execution.solution().orElseThrow().evidence().search().encodedResult().stateAssessments().keySet()) {
            work = Math.addExact(work, 1);
            if (state.searchDepth() == 0) { size = state.expression().length(); break; }
        }
        if (size == 0) throw new IllegalStateException("completed query has no bound initial state");
        for (var assumption : key.assumptions()) {
            size = Math.addExact(size, assumption.length()); work = Math.addExact(work, 1);
        }
        return admit(key, null, new Footprint(size, work), execution);
    }

    private long prepare(Key key, CumulativeTaskKnowledge.Execution execution) {
        var path = new RetainedTaskPath(execution.solution().orElseThrow().evidence().witness());
        var measured = RetainedGraph.measure(List.of(key, path));
        return admit(key, path, new Footprint(measured.retained().characters(),
            Math.addExact(path.constructionWork(), measured.work())), execution);
    }

    private long admit(Key key, RetainedTaskPath path, Footprint footprint, CumulativeTaskKnowledge.Execution execution) {
        long paid = Math.addExact(execution.totalWork(), footprint.work());
        long budget = execution.task().budget().totalWork();
        checkInterrupted();
        if (footprint.characters() > maximumCharacters || paid >= budget) return footprint.work();
        // Reserve replacement and worst-case evictions before scanning or mutating retained entries.
        if (2L + 2L * retained.size() > budget - paid) return footprint.work();
        return Math.addExact(footprint.work(), remember(key, path, footprint.characters()));
    }

    private boolean cacheable(CheckedSchemaTaskSolver.Task task) {
        return maximumQueries > 0 && maximumCharacters > 0
            && (task.goal().objective() == NativeNodeCountObjective.INSTANCE || task.goal().objective() instanceof StructuralTaskObjective);
    }
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("query interrupted after paid execution");
    }
    private long remember(Key key, RetainedTaskPath path, long incoming) {
        long work = 1;
        var replaced = retained.remove(key);
        if (replaced != null) { discard(replaced); work = Math.addExact(work, 1); }
        while (retained.size() >= maximumQueries || characters > maximumCharacters - incoming) {
            var iterator = retained.entrySet().iterator();
            var oldest = iterator.next(); discard(oldest.getValue()); iterator.remove();
            evictions = Math.addExact(evictions, 1);
            work = Math.addExact(work, 2);
        }
        retained.put(key, new Entry(path, incoming)); characters = Math.addExact(characters, incoming);
        if (path == null) observations = Math.addExact(observations, 1);
        else { admissions = Math.addExact(admissions, 1); retainedPaths++; }
        return work;
    }
    private void discard(Entry entry) {
        characters -= entry.characters();
        if (entry.path() != null) retainedPaths--;
    }
    public synchronized Statistics statistics() {
        return new Statistics(queries, hits, misses, bypasses, observations, admissions, failures, evictions,
            retainedPaths, retained.size() - retainedPaths, characters, knownWork, complete && failures == 0);
    }
    @Override public synchronized void close() { retained.clear(); retainedPaths = 0; characters = 0; closed = true; }
}
