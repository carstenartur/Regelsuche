package de.regelsuche.evolution;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
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
    private record Key(Expr source, boolean renamed, List<String> assumptions, TypedSourceOnlySearch.Objective objective,
            long maximumScore, MoveSearch.Budget budget, CumulativeTaskKnowledge.Strategy strategy) implements RetainedGraph.View {
        static Key of(CheckedSchemaTaskSolver.Task task, CumulativeTaskKnowledge.Strategy strategy, RenamedTaskPath.Shape shape) {
            return new Key(shape == null ? task.source() : shape.source(), shape != null,
                AssumptionSignature.ofExpressions(task.assumptions()).normalizedAssumptions(),
                task.goal().objective(), task.goal().maximumOutputScore(), task.budget(), strategy);
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(source); visitor.reference(assumptions); visitor.reference(budget);
            // Only explicitly admitted stateless built-in objective and strategy enums enter keys.
        }
    }
    private record RenamingEvidence(long ordinaryWork, long keyCharacters, boolean declined) { }
    private record Entry(RetainedGraph.View path, long characters, RenamingEvidence renaming) { }
    /** Coarse profitability only. Mathematical reuse still requires the exact canonical key. */
    private record RenamingProfile(BinaryOperator root, BinaryOperator left, BinaryOperator right, long goal,
            MoveSearch.Budget budget, CumulativeTaskKnowledge.Strategy strategy) {
        static RenamingProfile of(BinaryExpr source, CheckedSchemaTaskSolver.Task task, CumulativeTaskKnowledge.Strategy strategy) {
            return new RenamingProfile(source.operator(), operator(source.left()), operator(source.right()),
                task.goal().maximumOutputScore(), task.budget(), strategy);
        }

        private static BinaryOperator operator(Expr expression) {
            return expression instanceof BinaryExpr binary ? binary.operator() : null;
        }
    }
    private final CumulativeTaskKnowledge knowledge;
    private final int maximumQueries;
    private final long maximumCharacters;
    private final boolean variableRenaming;
    private final LinkedHashMap<Key, Entry> retained = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<RenamingProfile, Boolean> renamingProfiles = new LinkedHashMap<>(16, .75f, true);
    private long queries, hits, misses, bypasses, observations, admissions, failures, evictions, characters, knownWork;
    private int retainedPaths;
    private boolean closed, complete = true;

    /** Zero capacity disables reuse and keeps the existing persistent solver's work contract. */
    public TaskKnowledgeQuerySession(CumulativeTaskKnowledge knowledge, int maximumQueries, long maximumCharacters) {
        this(knowledge, maximumQueries, maximumCharacters, false);
    }

    /** Opt into replay across bijective renamings of free variables in bounded, unconditional primitive paths. */
    public static TaskKnowledgeQuerySession withVariableRenaming(CumulativeTaskKnowledge knowledge,
            int maximumQueries, long maximumCharacters) {
        return new TaskKnowledgeQuerySession(knowledge, maximumQueries, maximumCharacters, true);
    }

    private TaskKnowledgeQuerySession(CumulativeTaskKnowledge knowledge, int maximumQueries, long maximumCharacters,
            boolean variableRenaming) {
        this.knowledge = Objects.requireNonNull(knowledge);
        if (maximumQueries < 0 || maximumQueries > 256 || maximumCharacters < 0)
            throw new IllegalArgumentException("nonnegative bounded query capacity required");
        this.maximumQueries = maximumQueries; this.maximumCharacters = maximumCharacters;
        this.variableRenaming = variableRenaming;
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
            key = Key.of(task, strategy, null);
            var previous = retained.get(key);
            RenamedTaskPath.Shape shape = null;
            RenamingProfile requestProfile = null;
            Boolean profileDecision = null;
            if (previous == null && variableRenaming && task.assumptions().isEmpty()
                    && task.goal().objective() == NativeNodeCountObjective.INSTANCE && task.source() instanceof BinaryExpr binary) {
                requestProfile = RenamingProfile.of(binary, task, strategy);
                profileDecision = renamingProfiles.get(requestProfile);
                completedWork = Math.addExact(completedWork, 4); // Three root inspections and one profile lookup.
                if (Boolean.TRUE.equals(profileDecision)) {
                    var work = new CheckedSchemaSupport.Work();
                    shape = RenamedTaskPath.Shape.of(task.source(), work);
                    completedWork = Math.addExact(completedWork, work.units);
                    if (shape != null) {
                        key = Key.of(task, strategy, shape);
                        previous = retained.get(key);
                        completedWork = Math.addExact(completedWork, 1);
                    }
                }
            }
            var path = previous == null ? null : previous.path();
            boolean reused;
            CumulativeTaskKnowledge.Execution execution;
            if (path instanceof RenamedTaskPath renamed) {
                var work = new CheckedSchemaSupport.Work();
                var bound = renamed.bind(shape, work);
                completedWork = Math.addExact(completedWork, work.units);
                var searched = knowledge.solveRenamedInSession(task, strategy, completedWork, bound);
                execution = searched.execution();
                reused = searched.reused();
            } else if (path instanceof CompactTaskPath compact) {
                var searched = knowledge.solveRenamedInSession(task, strategy, completedWork, compact);
                execution = searched.execution();
                reused = searched.reused();
            } else {
                reused = path != null;
                execution = knowledge.solveInSession(task, strategy, completedWork, (RetainedTaskPath) path);
            }
            if (reused) hits = Math.addExact(hits, 1); else misses = Math.addExact(misses, 1);
            long lookupWork = completedWork;
            completedWork = execution.totalWork();
            complete &= accounted(execution);
            checkInterrupted();
            long retentionWork = 0;
            if (requestProfile != null && profileDecision == null) {
                boolean promising = execution.solution().filter(CheckedSchemaTaskSolver.Solution::solved)
                    .map(solution -> promisingRenaming(solution.evidence())).orElse(false);
                if (promising) {
                    var work = new CheckedSchemaSupport.Work();
                    shape = RenamedTaskPath.Shape.of(task.source(), work);
                    retentionWork = work.units;
                    if (shape != null) key = Key.of(task, strategy, shape);
                }
                retentionWork = Math.addExact(retentionWork, rememberProfile(requestProfile, shape != null));
                completedWork = Math.addExact(completedWork, retentionWork);
            }
            if (path instanceof RenamedTaskPath && (!reused || completedWork >= previous.renaming().ordinaryWork())) {
                long work = Math.addExact(declineRenaming(key, previous), rememberProfile(requestProfile, false));
                retentionWork = Math.addExact(retentionWork, work);
                completedWork = Math.addExact(completedWork, work);
            } else if (!reused && execution.solved() && !execution.solution().orElseThrow().evidence().witness().isEmpty()) {
                long work = previous == null ? observe(key, execution, retentionWork) : prepare(key, shape, previous, lookupWork, execution);
                retentionWork = Math.addExact(retentionWork, work);
                completedWork = Math.addExact(completedWork, work);
                checkInterrupted();
            } else if (previous != null && !execution.solved() && (previous.renaming() == null || !previous.renaming().declined())) {
                discard(retained.remove(key));
                retentionWork = 1; completedWork = Math.addExact(completedWork, 1);
            }
            knownWork = Math.addExact(knownWork, completedWork);
            return new Query(execution, reused, lookupWork, retentionWork);
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

    /** Shape inspection is paid for every transfer. Prefer paths with enough steps to offset that cost. */
    private static boolean promisingRenaming(TypedSourceOnlySearch.Result result) {
        return !result.witness().isEmpty() && result.inputScore() <= 3L * result.witness().size();
    }

    private long rememberProfile(RenamingProfile profile, boolean promising) {
        renamingProfiles.put(profile, promising);
        if (renamingProfiles.size() <= maximumQueries) return 2; // Decision and map update.
        renamingProfiles.pollFirstEntry();
        return 3;
    }

    /** The existing typed input encoding conservatively bounds the retained AST's scalar/text characters. */
    private long observe(Key key, CumulativeTaskKnowledge.Execution execution, long alreadyPaid) {
        long work = 1, size = 0;
        for (var state : execution.solution().orElseThrow().evidence().search().encodedResult().stateAssessments().keySet()) {
            work = Math.addExact(work, 1);
            if (state.searchDepth() == 0) { size = state.expression().length(); break; }
        }
        if (size == 0) throw new IllegalStateException("completed query has no bound initial state");
        for (var assumption : key.assumptions()) {
            size = Math.addExact(size, assumption.length()); work = Math.addExact(work, 1);
        }
        // The producer's tagged AST includes more framing for each variable than its numbered slot needs.
        var profile = key.renamed() ? new RenamingEvidence(0, size, false) : null;
        return admit(key, new Entry(null, size, profile), work, alreadyPaid, execution);
    }

    private long prepare(Key key, RenamedTaskPath.Shape shape, Entry previous, long lookupWork,
            CumulativeTaskKnowledge.Execution execution) {
        if (previous.renaming() != null && previous.renaming().declined()) return 0;
        var witness = execution.solution().orElseThrow().evidence().witness();
        if (shape != null) {
            var work = new CheckedSchemaSupport.Work();
            work.add(1);
            if (!promisingRenaming(execution.solution().orElseThrow().evidence()))
                return Math.addExact(work.units, declineRenaming(key, previous));
            var path = RenamedTaskPath.retain(shape, witness, work);
            if (path == null) return Math.addExact(work.units, declineRenaming(key, previous));
            var measured = RetainedGraph.measure(path);
            var evidence = new RenamingEvidence(execution.totalWork() - lookupWork, previous.renaming().keyCharacters(), false);
            long characters = Math.addExact(measured.retained().characters(), evidence.keyCharacters());
            return admit(key, new Entry(path, characters, evidence),
                Math.addExact(work.units, measured.work()), 0, execution);
        }
        long compactWork = 0;
        if (variableRenaming && key.assumptions().isEmpty() && key.objective() == NativeNodeCountObjective.INSTANCE) {
            var work = new CheckedSchemaSupport.Work();
            var compact = CompactTaskPath.retain(execution.solution().orElseThrow().evidence().search().encodedResult().witness(), work);
            if (compact != null) {
                return admit(key, new Entry(compact, compact.characters(), null), work.units, 0, execution);
            }
            compactWork = work.units;
        }
        var path = new RetainedTaskPath(witness);
        var measured = RetainedGraph.measure(List.of(key, path));
        return admit(key, new Entry(path, measured.retained().characters(), null),
            Math.addExact(compactWork, Math.addExact(path.constructionWork(), measured.work())), 0, execution);
    }

    private long declineRenaming(Key key, Entry previous) {
        long size = previous.renaming().keyCharacters();
        var rejected = new RenamingEvidence(previous.renaming().ordinaryWork(), size, true);
        discard(retained.put(key, new Entry(null, size, rejected)));
        characters = Math.addExact(characters, size);
        return 1;
    }

    private long admit(Key key, Entry entry, long footprintWork, long alreadyPaid, CumulativeTaskKnowledge.Execution execution) {
        long paid = Math.addExact(execution.totalWork(), Math.addExact(alreadyPaid, footprintWork));
        long budget = execution.task().budget().totalWork();
        checkInterrupted();
        if (entry.characters() > maximumCharacters || paid >= budget) return footprintWork;
        // Reserve replacement and worst-case evictions before scanning or mutating retained entries.
        if (2L + 2L * retained.size() > budget - paid) return footprintWork;
        return Math.addExact(footprintWork, remember(key, entry));
    }

    private boolean cacheable(CheckedSchemaTaskSolver.Task task) {
        return maximumQueries > 0 && maximumCharacters > 0
            && (task.goal().objective() == NativeNodeCountObjective.INSTANCE || task.goal().objective() instanceof StructuralTaskObjective);
    }
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("query interrupted after paid execution");
    }
    private long remember(Key key, Entry entry) {
        long work = 1;
        var replaced = retained.remove(key);
        if (replaced != null) { discard(replaced); work = Math.addExact(work, 1); }
        while (retained.size() >= maximumQueries || characters > maximumCharacters - entry.characters()) {
            var iterator = retained.entrySet().iterator();
            var oldest = iterator.next(); discard(oldest.getValue()); iterator.remove();
            evictions = Math.addExact(evictions, 1);
            work = Math.addExact(work, 2);
        }
        retained.put(key, entry); characters = Math.addExact(characters, entry.characters());
        if (entry.path() == null) observations = Math.addExact(observations, 1);
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
    @Override public synchronized void close() { retained.clear(); renamingProfiles.clear(); retainedPaths = 0; characters = 0; closed = true; }
}
