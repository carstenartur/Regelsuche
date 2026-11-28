package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.NativeNodeCountObjective;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Paid task-level admission to a checked matcher. An unseen input shape uses the primitive scheduler. */
public final class CostAwareTacticRouter {
    public record Trial(int index, boolean primitiveSolved, long primitiveWork, boolean learnedSolved,
            long learnedWork, long learnedSteps) {}
    public record Fit(Admission admission, long work, List<Trial> trials) {
        public Fit { trials = List.copyOf(trials); }
    }
    public record Result(long selectionWork, boolean admitted, TypedSourceOnlySearch.Result evidence, long originalBudget) {
        public long totalWork() { return Math.addExact(selectionWork, evidence.totalWork()); }
        public boolean withinBudget() { return evidence.withinBudget() && totalWork() <= originalBudget; }
        public boolean solved() { return withinBudget() && evidence.search().outcome() == MoveSearch.Outcome.QUALITY_REACHED; }
    }

    public static final class Admission implements RetainedGraph.View {
        private final String model;
        private final Set<Key> accepted;
        private final String fingerprint;

        private Admission(String model, Set<Key> accepted, CheckedSchemaSupport.Work work) {
            this.model = model;
            this.accepted = Set.copyOf(accepted);
            var canonical = new TreeSet<String>();
            for (var key : accepted) canonical.add(key.toString());
            String serialized = "checked-tactic-admission/v1|" + model + "|" + String.join("|", canonical);
            work.add(serialized.length() + 2L * accepted.size() + 1);
            fingerprint = SchematicProofPlan.hash(serialized);
        }
        public String fingerprint() { return fingerprint; }
        public int acceptedShapes() { return accepted.size(); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(model); visitor.reference(accepted); visitor.reference(fingerprint);
        }
    }

    private record Key(String goal, int depth, int shape) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(goal); }
        Key anyDepth() { return new Key(goal, -1, shape); }
    }

    private static final class Benefits {
        String firstSource;
        boolean distinctSources;
        long netSaved;
        long additionalSolutions;
        boolean lostSolution;
        boolean usedProof;

        void observe(String source, Trial trial) {
            if (firstSource == null) firstSource = source;
            else distinctSources |= !firstSource.equals(source);
            usedProof |= trial.learnedSteps() > 0 && trial.learnedSolved();
            lostSolution |= trial.primitiveSolved() && !trial.learnedSolved();
            additionalSolutions += Boolean.compare(trial.learnedSolved(), trial.primitiveSolved());
            netSaved = Math.addExact(netSaved, trial.primitiveWork() - trial.learnedWork());
        }

        boolean worthTrying(boolean depthSpecific) {
            if (!distinctSources || !usedProof || lostSolution) return false;
            return additionalSolutions == 0 ? netSaved > 0 : depthSpecific;
        }
    }

    private final Admission admission;
    private final CheckedSchemaTaskSolver primitive;
    private final CheckedSchemaTaskSolver learned;
    private final Set<String> disabled;
    private final long preparation;

    private CostAwareTacticRouter(TypedLearnedMoveInventory inventory, CheckedLearnedSchemaModel model,
            Admission admission, Set<String> disabled) {
        if (!model.identity().equals(admission.model)) throw new IllegalArgumentException("admission belongs to a different model");
        var ids = new HashSet<String>();
        for (var schema : model.schemas()) ids.add(schema.id());
        if (!ids.containsAll(disabled)) throw new IllegalArgumentException("unknown disabled schema");
        this.admission = admission;
        this.primitive = inventory.newTaskSolver().asHeuristicPrimitiveFallback();
        this.learned = inventory.newTaskSolverWithSharedContext(model);
        this.disabled = Set.copyOf(disabled);
        // Reserve the full knockout mask for every configuration, including the enabled arm.
        preparation = Math.addExact(learned.preparationWork(), 3L * ids.size() + inventory.primitiveProviders().size() + 6);
    }

    public static CostAwareTacticRouter prepare(EvolutionGenome genome, CheckedLearnedSchemaModel model,
            Admission admission, Set<String> disabled) {
        requireInventory(genome, model);
        return new CostAwareTacticRouter(TypedLearnedMoveInventory.primitives(genome), model, admission, disabled);
    }

    public long preparationWork() { return preparation; }

    /** Charge both attempts, independent replays and the statistical fit for every calibration case. */
    public static Fit fit(EvolutionGenome genome, CheckedLearnedSchemaModel model,
            List<CheckedSchemaTaskSolver.Task> tasks) {
        requireInventory(genome, model);
        var inventory = TypedLearnedMoveInventory.primitives(genome);
        var primitive = inventory.newTaskSolver().asHeuristicPrimitiveFallback();
        var learned = inventory.newTaskSolverWithSharedContext(model);
        var work = new CheckedSchemaSupport.Work();
        work.add(learned.preparationWork() + inventory.primitiveProviders().size() + 6);
        var groups = new HashMap<Key, Benefits>();
        var trials = new ArrayList<Trial>();
        for (var task : tasks) {
            var routing = new CheckedSchemaSupport.Work();
            var key = key(task, routing);
            if (key == null) throw new IllegalArgumentException("unidentified calibration objective");
            routing.add(3);
            work.add(routing.units);
            var charged = remaining(task, routing.units);
            var baseline = primitive.solveWithAdmission(charged, Set.of());
            var candidate = learned.solveWithAdmission(charged, Set.of());
            if (!baseline.evidence().accountingComplete() || !candidate.evidence().accountingComplete())
                throw new IllegalStateException("incomplete tactic admission calibration");
            long baseWork = baseline.evidence().totalWork(), candidateWork = candidate.evidence().totalWork();
            long steps = candidate.learnedApplications();
            boolean eligible = task.budget().totalWork() > routing.units;
            var trial = new Trial(trials.size(), eligible && baseline.solved(), baseWork,
                eligible && candidate.solved(), candidateWork, steps);
            trials.add(trial);
            String source = ExpressionFormatter.formatStructure(task.source());
            // A solution gained at one depth cap only supports that depth cap. A measured
            // saving with equal coverage can support the same shape at other depths.
            groups.computeIfAbsent(key, ignored -> new Benefits()).observe(source, trial);
            groups.computeIfAbsent(key.anyDepth(), ignored -> new Benefits()).observe(source, trial);
            work.add(Math.addExact(baseWork, candidateWork));
            work.add(3L * source.length() + candidate.evidence().witness().size() + 15);
        }
        var accepted = new HashSet<Key>();
        for (var entry : groups.entrySet()) {
            if (entry.getValue().worthTrying(entry.getKey().depth() >= 0)) accepted.add(entry.getKey());
        }
        work.add(5L * groups.size() + 1);
        var admission = new Admission(model.identity(), accepted, work);
        return new Fit(admission, work.units, trials);
    }

    private static void requireInventory(EvolutionGenome genome, CheckedLearnedSchemaModel model) {
        if (!genome.contentHash().equals(model.inventoryHash())) throw new IllegalArgumentException("schema inventory mismatch");
    }

    public Result solve(CheckedSchemaTaskSolver.Task task) {
        var work = new CheckedSchemaSupport.Work();
        work.add(1);
        boolean admit = false;
        if (!admission.accepted.isEmpty()) {
            var input = key(task, work);
            work.add(2);
            admit = input != null && (admission.accepted.contains(input) || admission.accepted.contains(input.anyDepth()));
        }
        var answer = (admit ? learned : primitive).solveWithAdmission(remaining(task, work.units), disabled);
        return new Result(work.units, admit, answer.evidence(), task.budget().totalWork());
    }

    private static CheckedSchemaTaskSolver.Task remaining(CheckedSchemaTaskSolver.Task task, long spent) {
        var original = task.budget();
        var budget = new MoveSearch.Budget(original.maxPrimitiveSteps(), original.maxSearchDepth(), original.maxTheoryWork(),
            original.maxStates(), Math.max(1, original.totalWork() - spent), original.maxComplexityDebt());
        return new CheckedSchemaTaskSolver.Task(task.source(), task.assumptions(), task.goal(), budget);
    }

    private static Key key(CheckedSchemaTaskSolver.Task task, CheckedSchemaSupport.Work work) {
        String goal = TaskKnowledgeProfile.objectiveKey(task);
        work.add(2);
        if (goal == null) return null;
        Expr source = task.source();
        int root = symbol(source);
        if (source instanceof BinaryExpr binary) {
            root = (root * 256 + symbol(binary.left())) * 256 + symbol(binary.right());
            work.add(2);
        }
        return new Key(goal, task.budget().maxSearchDepth(), root);
    }

    /** Variables, function names and constant values are anonymous. */
    private static int symbol(Expr expression) {
        if (expression instanceof BinaryExpr binary) return 4 + binary.operator().ordinal();
        if (expression instanceof FunctionExpr) return 3;
        if (expression instanceof NumberExpr) return 2;
        return 1;
    }

    /** Share the native objective's result between scoring and the quality check during one solve. */
    static final class QueryNodeCount implements TypedSourceOnlySearch.Objective {
        private final Map<Expr, Long> sizes = new IdentityHashMap<>();
        @Override public TypedSourceOnlySearch.Score evaluate(TypedMoveSearch.State state) {
            var value = sizes.get(state.expression());
            if (value != null) return new TypedSourceOnlySearch.Score(value, 1);
            var result = NativeNodeCountObjective.INSTANCE.evaluate(state);
            long work = Math.addExact(result.work(), sizes.isEmpty() ? 4 : 2);
            if (sizes.size() == 64) {
                work = Math.addExact(work, sizes.size());
                sizes.clear();
            }
            sizes.put(state.expression(), result.value());
            return new TypedSourceOnlySearch.Score(result.value(), work);
        }
    }
}
