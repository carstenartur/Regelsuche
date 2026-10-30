package de.regelsuche.evolution;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.NativeNodeCountObjective;
import java.util.Set;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Immutable knowledge generations over the existing task solver and schema generalizer.
 * Lessons use the old generation; validation can select a successor but is not held-out evidence.
 * Costs use the existing declared logical units, not a complete JVM work/heap contract.
 */
public final class CumulativeTaskKnowledge {
    public static final String REVISION = "regelsuche.cumulative-task-knowledge/v1";
    public enum Strategy { BASELINE, KNOWLEDGE_FIRST, AUTOMATIC }
    public enum Decision { INITIAL, PROMOTED, NO_NEW_SCHEMA, NO_VALIDATED_BENEFIT, ACQUISITION_LIMIT }
    public static final long DEFAULT_LEARNING_WORK = 1_000_000;
    private static final int MAX_BATCH = 32;
    private static final int MAX_ROUTES = 256;

    /** Historical acquisition receipts are descriptive, never mathematical proof authority. */
    public record Costs(long lessons, long formation, long preparation, long validation, long selection,
            long restoration, long splitChecking) {
        public Costs {
            for (long value : new long[] {lessons, formation, preparation, validation, selection, restoration, splitChecking})
                if (value < 0) throw new IllegalArgumentException("negative knowledge cost");
            sum(lessons, formation, preparation, validation, selection, restoration, splitChecking);
        }
        public long total() { return sum(lessons, formation, preparation, validation, selection, restoration, splitChecking); }
        Costs plus(Costs other) {
            return new Costs(sum(lessons, other.lessons), sum(formation, other.formation),
                sum(preparation, other.preparation), sum(validation, other.validation),
                sum(selection, other.selection), sum(restoration, other.restoration), sum(splitChecking, other.splitChecking));
        }
        static Costs zero() { return new Costs(0, 0, 0, 0, 0, 0, 0); }
    }

    /** Issued by an actual execution; no public constructor can fabricate a completed lesson. */
    public static final class Execution {
        private final String generationId;
        private final CheckedSchemaTaskSolver.Task task;
        private final Strategy selected;
        private final long selectionWork;
        private final CheckedSchemaTaskSolver.Solution solution;
        private Execution(String generationId, CheckedSchemaTaskSolver.Task task, Strategy selected,
                long selectionWork, CheckedSchemaTaskSolver.Solution solution) {
            this.generationId = generationId; this.task = task; this.selected = selected;
            this.selectionWork = selectionWork; this.solution = solution;
        }
        public String generationId() { return generationId; }
        public CheckedSchemaTaskSolver.Task task() { return task; }
        public Strategy selectedStrategy() { return selected; }
        public long selectionWork() { return selectionWork; }
        public Optional<CheckedSchemaTaskSolver.Solution> solution() { return Optional.ofNullable(solution); }
        public long totalWork() { return sum(selectionWork, solution == null ? 0 : solution.evidence().totalWork()); }
        public boolean solved() { return solution != null && solution.solved() && totalWork() <= task.budget().totalWork(); }
        long score() { return solution == null ? Long.MAX_VALUE : solution.evidence().outputScore(); }
    }

    public record Validation(CheckedSchemaTaskSolver.Task task, String profile,
            Execution baseline, Execution previous, Execution candidate, Optional<Execution> automatic) { }
    public record Update(CumulativeTaskKnowledge next, Decision reason, List<Execution> training,
            List<Validation> validation, Costs work, List<CheckedLearnedSchemaModel.Attempt> attempts) {
        public Update { training = List.copyOf(training); validation = List.copyOf(validation); attempts = List.copyOf(attempts); }
        public boolean promoted() { return reason == Decision.PROMOTED; }
    }

    /** A technical failure never hides completed lessons or authorizes a new knowledge state. */
    public static final class BatchFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final String parentId;
        private final Costs knownWork;
        private final List<Execution> completedLessons;
        private BatchFailure(String parentId, Costs knownWork, List<Execution> completedLessons, RuntimeException cause) {
            super("knowledge batch failed; unfinished work is unknown", cause);
            this.parentId = parentId; this.knownWork = knownWork; this.completedLessons = List.copyOf(completedLessons);
        }
        public String parentId() { return parentId; }
        public Costs knownWork() { return knownWork; }
        public List<Execution> completedLessons() { return completedLessons; }
        public boolean accountingComplete() { return false; }
    }
    private static final class Progress {
        long lessons, formation, preparation, validation, selection, split;
        final List<Execution> training = new ArrayList<>();
        final List<Validation> trials = new ArrayList<>();
        List<CheckedLearnedSchemaModel.Attempt> attempts = List.of();
        Costs costs() { return new Costs(lessons, formation, preparation, validation, selection, 0, split); }
    }

    private final EvolutionGenome inventory;
    private final CheckedLearnedSchemaModel model;
    private final CheckedSchemaTaskSolver baseline;
    private final CheckedSchemaTaskSolver learned;
    private final int generation;
    private final String parentId;
    private final Map<String, Strategy> routes;
    private final Set<Long> knowledgeGoals;
    private final Costs acquisition;
    private final Costs sessionSetup;
    private final Decision lastDecision;
    private final List<CheckedLearnedSchemaModel.Attempt> lastAttempts;
    private final String canonicalJson;
    private final String id;

    private CumulativeTaskKnowledge(EvolutionGenome inventory, CheckedLearnedSchemaModel model,
            CheckedSchemaTaskSolver baseline, CheckedSchemaTaskSolver learned, int generation,
            String parentId, Map<String, Strategy> routes, Costs acquisition, Costs sessionSetup,
            Decision lastDecision, List<CheckedLearnedSchemaModel.Attempt> lastAttempts) {
        this.inventory = Objects.requireNonNull(inventory); this.model = Objects.requireNonNull(model);
        if (!inventory.contentHash().equals(model.inventoryHash())) throw new IllegalArgumentException("foreign inventory");
        if (!model.requiredAssumptions().isEmpty()) throw new IllegalArgumentException("conditional knowledge unsupported");
        if (generation < 0 || generation > 100_000 || (generation == 0) != parentId.isEmpty()
                || generation > 0 && !parentId.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("invalid generation parent");
        if (routes.size() > MAX_ROUTES || routes.entrySet().stream().anyMatch(e -> e.getKey().length() > 512
                || !e.getKey().matches("nodes:-?[0-9]{1,19}:[A-Z(),]+") || e.getValue() == null || e.getValue() == Strategy.AUTOMATIC))
            throw new IllegalArgumentException("invalid routing policy");
        this.baseline = baseline; this.learned = learned; this.generation = generation; this.parentId = parentId;
        this.routes = java.util.Collections.unmodifiableMap(new TreeMap<>(routes));
        knowledgeGoals = knowledgeGoals(this.routes);
        this.acquisition = acquisition; this.sessionSetup = sessionSetup;
        this.lastDecision = Objects.requireNonNull(lastDecision); this.lastAttempts = List.copyOf(lastAttempts);
        if ((generation == 0) != (lastDecision == Decision.INITIAL)
                || generation == 0 && (!model.schemas().isEmpty() || !routes.isEmpty() || acquisition.total() != 0))
            throw new IllegalArgumentException("invalid initial generation");
        canonicalJson = TaskKnowledgeSnapshot.encode(generation, parentId, model, this.routes, acquisition,
            lastDecision, this.lastAttempts);
        id = SchematicProofPlan.hash(canonicalJson);
    }

    public static CumulativeTaskKnowledge empty(EvolutionGenome inventory) {
        Objects.requireNonNull(inventory, "inventory");
        var baseline = TypedLearnedMoveInventory.primitives(inventory).newTaskSolver();
        return new CumulativeTaskKnowledge(inventory, CheckedLearnedSchemaModel.empty(inventory.contentHash()),
            baseline, baseline, 0, "", Map.of(), Costs.zero(), Costs.zero(), Decision.INITIAL, List.of());
    }
    public String id() { return id; }
    public int generation() { return generation; }
    public String parentId() { return parentId; }
    public int schemaCount() { return model.schemas().size(); }
    public long acquisitionWork() { return acquisition.total(); }
    public Costs acquisitionCosts() { return acquisition; }
    public long sessionSetupWork() { return sessionSetup.total(); }
    public Map<String, Strategy> routingPolicy() { return routes; }
    public Decision lastDecision() { return lastDecision; }
    public List<CheckedLearnedSchemaModel.Attempt> lastAttempts() { return lastAttempts; }
    public String toCanonicalJson() { return canonicalJson; }

    /** Fresh query state, fixed library, paid routing, and no training side effect. */
    public Execution solve(CheckedSchemaTaskSolver.Task task, Strategy strategy) {
        Objects.requireNonNull(task, "task"); Objects.requireNonNull(strategy, "strategy");
        if (strategy != Strategy.AUTOMATIC) return run(id, task, strategy, 0, baseline, learned);
        return automatic(id, task, routes, knowledgeGoals, baseline, learned);
    }

    private static Set<Long> knowledgeGoals(Map<String, Strategy> routes) {
        var goals = new HashSet<Long>();
        routes.forEach((key, strategy) -> {
            if (strategy == Strategy.KNOWLEDGE_FIRST) goals.add(Long.parseLong(key.split(":", 3)[1]));
        });
        return Set.copyOf(goals);
    }
    private static Execution automatic(String id, CheckedSchemaTaskSolver.Task task, Map<String, Strategy> routes,
            Set<Long> goals, CheckedSchemaTaskSolver baseline, CheckedSchemaTaskSolver learned) {
        // A constant baseline policy does not inspect the source at all.
        if (goals.isEmpty()) return run(id, task, Strategy.BASELINE, 0, baseline, learned);
        if (task.goal().objective() != NativeNodeCountObjective.INSTANCE
                || !goals.contains(task.goal().maximumOutputScore()))
            return run(id, task, Strategy.BASELINE, 2, baseline, learned);
        var profile = TaskKnowledgeProfile.inspect(task);
        return run(id, task, routes.getOrDefault(profile.key(), Strategy.BASELINE), profile.work() + 2, baseline, learned);
    }

    private static Execution run(String generationId, CheckedSchemaTaskSolver.Task task, Strategy selected,
            long selectionWork, CheckedSchemaTaskSolver baseline, CheckedSchemaTaskSolver learned) {
        if (selectionWork >= task.budget().totalWork()) return new Execution(generationId, task, selected, selectionWork, null);
        var remaining = task.budget();
        if (selectionWork > 0) remaining = new MoveSearch.Budget(remaining.maxPrimitiveSteps(), remaining.maxSearchDepth(),
            remaining.maxTheoryWork(), remaining.maxStates(), remaining.totalWork() - selectionWork, remaining.maxComplexityDebt());
        var actual = new CheckedSchemaTaskSolver.Task(task.source(), task.assumptions(), task.goal(), remaining);
        var solution = (selected == Strategy.BASELINE ? baseline : learned).solve(actual);
        return new Execution(generationId, task, selected, selectionWork, solution);
    }

    /**
     * All lessons run against this immutable parent. Validation inputs never enter formation.
     * A rejected candidate still produces a cost-bearing snapshot with the old mathematics.
     * Each task owns its explicit budget; batch size and the existing formation bounds limit learning.
     */
    public Update learnBatch(List<CheckedSchemaTaskSolver.Task> lessons, List<CheckedSchemaTaskSolver.Task> validationTasks) {
        return learnBatch(lessons, validationTasks, DEFAULT_LEARNING_WORK);
    }

    /** Bounded phases may overrun atomically, but their full work remains paid and cannot promote knowledge. */
    public Update learnBatch(List<CheckedSchemaTaskSolver.Task> lessons, List<CheckedSchemaTaskSolver.Task> validationTasks,
            long maximumAcquisitionWork) {
        if (maximumAcquisitionWork < 1) throw new IllegalArgumentException("positive learning budget required");
        lessons = bounded(lessons, 2); validationTasks = bounded(validationTasks, 1);
        var progress = new Progress();
        // Invalid/non-polynomial/overlapping batches are rejected before running any lesson.
        progress.split = disjoint(lessons, validationTasks);
        try {
            if (progress.costs().total() > maximumAcquisitionWork) return limited(progress);
            if (!learnLessons(lessons, progress, maximumAcquisitionWork)) return limited(progress);
            var candidate = CheckedLearnedSchemaModel.extendFromSolutions(model,
                progress.training.stream().flatMap(e -> e.solution().stream()).toList());
            progress.formation = candidate.formationWork(); progress.attempts = candidate.attempts();
            if (progress.costs().total() > maximumAcquisitionWork) return limited(progress);
            boolean newSchemas = !candidate.schemas().stream().map(CheckedLearnedSchemaModel.Schema::id).collect(java.util.stream.Collectors.toSet())
                .equals(model.schemas().stream().map(CheckedLearnedSchemaModel.Schema::id).collect(java.util.stream.Collectors.toSet()));
            var prepared = newSchemas ? TypedLearnedMoveInventory.primitives(inventory).newTaskSolver(candidate) : learned;
            progress.preparation = newSchemas ? prepared.preparationWork() : 0;
            if (progress.costs().total() > maximumAcquisitionWork) return limited(progress);
            var proposedRoutes = new TreeMap<>(routes);
            if (newSchemas) {
                if (!validateCandidate(validationTasks, candidate, prepared, progress, maximumAcquisitionWork))
                    return limited(progress);
                fitRoutes(progress, proposedRoutes);
            }
            boolean improved = validatePolicy(candidate, prepared, proposedRoutes, progress, maximumAcquisitionWork);
            if (progress.costs().total() > maximumAcquisitionWork) return limited(progress);
            return selectSuccessor(progress, candidate, prepared, proposedRoutes, newSchemas, improved);
        } catch (RuntimeException failure) {
            throw new BatchFailure(id, progress.costs(), progress.training, failure);
        }
    }

    /** Every lesson uses this frozen parent; completed work survives an acquisition cutoff. */
    private boolean learnLessons(List<CheckedSchemaTaskSolver.Task> lessons, Progress progress, long maximumAcquisitionWork) {
        for (var task : lessons) {
            var execution = solve(task, Strategy.KNOWLEDGE_FIRST);
            progress.training.add(execution);
            progress.lessons = sum(progress.lessons, execution.totalWork());
            if (progress.costs().total() > maximumAcquisitionWork) return false;
        }
        return true;
    }

    /** Preserve the paid prefix between the baseline, previous-policy and candidate arms. */
    private boolean validateCandidate(List<CheckedSchemaTaskSolver.Task> validationTasks,
            CheckedLearnedSchemaModel candidate, CheckedSchemaTaskSolver prepared,
            Progress progress, long maximumAcquisitionWork) {
        String candidateId = "candidate:" + SchematicProofPlan.hash(candidate.toCanonicalJson());
        for (var task : validationTasks) {
            var profile = TaskKnowledgeProfile.inspect(task);
            progress.selection = sum(progress.selection, profile.work());
            var b = profiledRun(id, task, Strategy.BASELINE, baseline, learned);
            progress.validation = sum(progress.validation, b.totalWork());
            if (progress.costs().total() > maximumAcquisitionWork) return false;
            var p = solve(task, Strategy.AUTOMATIC);
            progress.validation = sum(progress.validation, p.totalWork());
            if (progress.costs().total() > maximumAcquisitionWork) return false;
            var c = profiledRun(candidateId, task, Strategy.KNOWLEDGE_FIRST, baseline, prepared);
            progress.validation = sum(progress.validation, c.totalWork());
            progress.trials.add(new Validation(task, profile.key(), b, p, c, Optional.empty()));
            if (progress.costs().total() > maximumAcquisitionWork) return false;
        }
        return true;
    }

    private static void fitRoutes(Progress progress, Map<String, Strategy> proposedRoutes) {
        var groups = new TreeMap<String, List<Validation>>();
        for (var trial : progress.trials) groups.computeIfAbsent(trial.profile(), key -> new ArrayList<>()).add(trial);
        for (var group : groups.entrySet()) {
            progress.selection = sum(progress.selection, group.getValue().size());
            if (group.getKey().equals("custom")) continue;
            boolean safe = group.getValue().stream().allMatch(row -> noWorse(row.candidate(), row.baseline()));
            long baseWork = group.getValue().stream().map(Validation::baseline).mapToLong(Execution::totalWork).reduce(0, Math::addExact);
            long candidateWork = group.getValue().stream().map(Validation::candidate).mapToLong(Execution::totalWork).reduce(0, Math::addExact);
            boolean moreSolved = group.getValue().stream().anyMatch(row -> row.candidate().solved() && !row.baseline().solved());
            proposedRoutes.put(group.getKey(), safe && (moreSolved || candidateWork < baseWork)
                ? Strategy.KNOWLEDGE_FIRST : Strategy.BASELINE);
        }
    }

    /** Re-execute the actual fitted policy; counterfactual receipts cannot justify activation. */
    private boolean validatePolicy(CheckedLearnedSchemaModel candidate, CheckedSchemaTaskSolver prepared,
            Map<String, Strategy> proposedRoutes, Progress progress, long maximumAcquisitionWork) {
        boolean safe = true, benefit = false;
        long previousWork = 0, nextWork = 0;
        var proposedGoals = knowledgeGoals(proposedRoutes);
        progress.selection = sum(progress.selection, proposedRoutes.size());
        for (int i = 0; i < progress.trials.size(); i++) {
            var row = progress.trials.get(i);
            // Execute the fitted policy itself, including its real cheap-baseline paths and budget.
            // Reusing a counterfactual arm's receipt would misstate routing work and tight-budget outcomes.
            var next = automatic("candidate:" + SchematicProofPlan.hash(candidate.toCanonicalJson()), row.task(),
                proposedRoutes, proposedGoals, baseline, prepared);
            progress.validation = sum(progress.validation, next.totalWork());
            progress.trials.set(i, new Validation(row.task(), row.profile(), row.baseline(), row.previous(), row.candidate(), Optional.of(next)));
            safe &= noWorse(next, row.previous());
            benefit |= next.solved() && !row.previous().solved();
            previousWork = sum(previousWork, row.previous().totalWork()); nextWork = sum(nextWork, next.totalWork());
            progress.selection = sum(progress.selection, 1);
            if (progress.costs().total() > maximumAcquisitionWork) return false;
        }
        return safe && (benefit || nextWork < previousWork);
    }

    private Update selectSuccessor(Progress progress, CheckedLearnedSchemaModel candidate,
            CheckedSchemaTaskSolver prepared, Map<String, Strategy> proposedRoutes,
            boolean newSchemas, boolean improved) {
        if (newSchemas && improved && proposedRoutes.size() <= MAX_ROUTES)
            return finish(progress, Decision.PROMOTED, candidate, prepared, proposedRoutes);
        return finish(progress, newSchemas ? Decision.NO_VALIDATED_BENEFIT : Decision.NO_NEW_SCHEMA,
            model, learned, routes);
    }

    private static Execution profiledRun(String id, CheckedSchemaTaskSolver.Task task, Strategy strategy,
            CheckedSchemaTaskSolver baseline, CheckedSchemaTaskSolver learned) {
        return run(id, task, strategy, TaskKnowledgeProfile.inspect(task).work() + 2, baseline, learned);
    }
    private Update limited(Progress progress) {
        return finish(progress, Decision.ACQUISITION_LIMIT, model, learned, routes);
    }
    private Update finish(Progress progress, Decision decision, CheckedLearnedSchemaModel chosen,
            CheckedSchemaTaskSolver solver, Map<String, Strategy> policy) {
        var work = progress.costs();
        var successor = new CumulativeTaskKnowledge(inventory, chosen, baseline, solver,
            Math.addExact(generation, 1), id, policy, acquisition.plus(sessionSetup).plus(work), Costs.zero(),
            decision, progress.attempts);
        return new Update(successor, decision, progress.training, progress.trials, work, progress.attempts);
    }

    private static boolean noWorse(Execution proposed, Execution previous) {
        if (proposed.solution().isPresent() && !proposed.solution().orElseThrow().evidence().accountingComplete()) return false;
        if (previous.solved()) return proposed.solved();
        return proposed.solved() || proposed.score() <= previous.score();
    }
    private static List<CheckedSchemaTaskSolver.Task> bounded(List<CheckedSchemaTaskSolver.Task> tasks, int minimum) {
        var copy = List.copyOf(tasks);
        if (copy.size() < minimum || copy.size() > MAX_BATCH) throw new IllegalArgumentException("batch size outside bounds");
        return copy;
    }
    private static long disjoint(List<CheckedSchemaTaskSolver.Task> lessons, List<CheckedSchemaTaskSolver.Task> validation) {
        long[] work = {0};
        var exact = new ExactPolynomialAnalysis(units -> work[0] = sum(work[0], units));
        var training = new HashSet<String>();
        for (var task : lessons) training.add(alphaIdentity(task, exact, work));
        for (var task : validation) if (training.contains(alphaIdentity(task, exact, work)))
            throw new IllegalArgumentException("lesson/validation polynomial alpha overlap");
        return work[0];
    }

    private static String alphaIdentity(CheckedSchemaTaskSolver.Task task, ExactPolynomialAnalysis exact, long[] work) {
        var bounded = new CheckedSchemaSupport.Work();
        CheckedSchemaSupport.domain(task.source(), CheckedLearnedSchemaModel.Bounds.defaults(), bounded);
        work[0] = sum(work[0], bounded.units);
        String text = ExpressionFormatter.format(task.source());
        work[0] = sum(work[0], text.length());
        return exact.alphaIdentity(text);
    }

    /** Write an immutable, bounded snapshot. No implicit latest pointer or destructive replacement. */
    public Path save(Path directory) throws IOException { return TaskKnowledgeSnapshot.save(directory, id, canonicalJson); }
    public static CumulativeTaskKnowledge load(EvolutionGenome inventory, Path path) throws IOException {
        return restore(inventory, TaskKnowledgeSnapshot.read(path));
    }
    public static CumulativeTaskKnowledge restore(EvolutionGenome inventory, String json) {
        Objects.requireNonNull(inventory, "inventory");
        var snapshot = TaskKnowledgeSnapshot.decode(json);
        // A recomputed digest cannot authorize false mathematics: load re-proves every schema.
        var model = CheckedLearnedSchemaModel.load(snapshot.model(), inventory.contentHash());
        var base = TypedLearnedMoveInventory.primitives(inventory);
        var baseline = base.newTaskSolver();
        var learned = model.schemas().isEmpty() ? baseline : base.newTaskSolver(model);
        var setup = new Costs(0, 0, learned.preparationWork(), 0, 0, model.loadWork(), 0);
        var restored = new CumulativeTaskKnowledge(inventory, model, baseline, learned,
            snapshot.generation(), snapshot.parentId(), snapshot.routes(), snapshot.costs(), setup, snapshot.decision(), snapshot.attempts());
        if (!restored.toCanonicalJson().equals(json)) throw new IllegalArgumentException("noncanonical knowledge snapshot");
        return restored;
    }
    private static long sum(long... values) {
        long total = 0;
        for (long value : values) total = Math.addExact(total, value);
        return total;
    }
}
