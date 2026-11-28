package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.NativeNodeCountObjective;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.transform.TransformationProvenance;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Paid task-level admission to a checked matcher. An unseen input shape uses the primitive scheduler. */
public final class CostAwareTacticRouter {
    public record Trial(int index, boolean primitiveSolved, long primitiveWork, boolean learnedSolved,
            long learnedWork, long learnedSteps) {}
    public enum Refinement { OUTSIDE_SUPPORT, PILOT_REUSED, MEASURED }
    public record SparseTrial(int index, List<String> pilotSchemas, int primitivePathLength, int fullPathLength,
            boolean compressedOrCheaper, Refinement status, boolean solved, long work, long learnedSteps,
            List<String> usedSchemas) {
        public SparseTrial { pilotSchemas = List.copyOf(pilotSchemas); usedSchemas = List.copyOf(usedSchemas); }
    }
    public record Fit(Admission admission, long work, List<Trial> trials, List<SparseTrial> sparseTrials) {
        public Fit(Admission admission, long work, List<Trial> trials) { this(admission,work,trials,List.of()); }
        public Fit { trials = List.copyOf(trials); sparseTrials = List.copyOf(sparseTrials); }
    }
    public record Result(long selectionWork, boolean admitted, TypedSourceOnlySearch.Result evidence, long originalBudget, boolean laneSolved) {
        public long totalWork() { return Math.addExact(selectionWork, evidence.totalWork()); }
        public boolean withinBudget() { return evidence.withinBudget() && totalWork() <= originalBudget; }
        public boolean solved() { return withinBudget() && laneSolved; }
    }

    public static final class Admission implements RetainedGraph.View {
        private final String model;
        private final Set<Key> accepted;
        private final String fingerprint;

        private final List<String> selectedSchemas;
        private final boolean sparse;

        private Admission(String model, Set<Key> accepted, Set<String> selected, CheckedSchemaSupport.Work work) {
            sparse = selected != null;
            selectedSchemas = sparse ? List.copyOf(new TreeSet<>(selected)) : List.of();
            this.model = model;
            this.accepted = Set.copyOf(accepted);
            var canonical = new TreeSet<String>();
            for (var key : accepted) canonical.add(key.toString());
            String serialized = "checked-tactic-admission/v1|" + model + "|" + String.join("|", canonical);
            if (sparse) {
                serialized = "checked-sparse-matcher/v1|" + serialized + "|" + String.join("|",selectedSchemas);
                work.add(2L * selectedSchemas.size() + 1);
            }
            work.add(serialized.length() + 2L * accepted.size() + 1);
            fingerprint = SchematicProofPlan.hash(serialized);
        }
        public String fingerprint() { return fingerprint; }
        public int acceptedShapes() { return accepted.size(); }
        /** Empty for a dense fit. Sparse fits publish the exact retained proof IDs installed in the matcher. */
        public List<String> selectedSchemas() { return selectedSchemas; }
        public boolean sparse() { return sparse; }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(model); visitor.reference(accepted); visitor.reference(fingerprint); visitor.reference(selectedSchemas);
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

    /** Materialize one flag per schema even in an ablation, so every configuration pays the same preparation. */
    private static final class KnockoutMask extends AbstractSet<String> implements RetainedGraph.View {
        private final Map<String,Boolean> flags;
        private final int disabledCount;
        KnockoutMask(CheckedLearnedSchemaModel model, Set<String> disabled) {
            var entries = new HashMap<String,Boolean>();
            int count = 0;
            for (var schema : model.schemas()) {
                boolean exclude = disabled.contains(schema.id());
                entries.put(schema.id(), exclude);
                if (exclude) count++;
            }
            if (count != disabled.size()) throw new IllegalArgumentException("unknown disabled schema");
            flags = Map.copyOf(entries);
            disabledCount = count;
        }
        @Override public int size() { return disabledCount; }
        @Override public boolean contains(Object id) { return Boolean.TRUE.equals(flags.get(id)); }
        @Override public Iterator<String> iterator() {
            return flags.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).iterator();
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(flags); }
    }

    private final Admission admission;
    private final CheckedSchemaTaskSolver primitive;
    private final CheckedSchemaTaskSolver learned;
    private final Set<String> disabled;
    private final long preparation;

    private CostAwareTacticRouter(TypedLearnedMoveInventory inventory, CheckedLearnedSchemaModel model,
            Admission admission, Set<String> disabled) {
        if (!model.identity().equals(admission.model)) throw new IllegalArgumentException("admission belongs to a different model");
        this.admission = admission;
        this.primitive = inventory.newTaskSolver().asHeuristicPrimitiveFallback();
        if (!admission.sparse) learned = inventory.newTaskSolverWithSharedContext(model);
        else if (admission.accepted.isEmpty()) learned = null;
        else learned = inventory.selectedSolver(model, true, Set.copyOf(admission.selectedSchemas));
        this.disabled = new KnockoutMask(model, disabled);
        preparation = Math.addExact(learned == null ? 0 : learned.preparationWork(), 3L * model.schemas().size() + inventory.primitiveProviders().size() + 6
            + (admission.sparse && learned != null ? admission.selectedSchemas.size() + 1L : 0));
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
        return fit(genome,model,tasks,false);
    }

    /**
     * Mine proposal IDs from actual verified full-matcher paths and measure the restricted
     * matcher with the same primitive counterfactual and task caps before admitting a shape.
     * Shape selection sees no constants, input IDs, family labels or required output.
     */
    public static Fit fitSparse(EvolutionGenome genome, CheckedLearnedSchemaModel model,
            List<CheckedSchemaTaskSolver.Task> tasks) {
        return fit(genome,model,tasks,true);
    }

    private record Pilot(Key key, String source, CheckedSchemaTaskSolver.Task charged, Trial trial,
            int primitiveLength, int fullLength, List<String> used, boolean eligible) {
        boolean qualifies() {
            return trial.learnedSolved() && !used.isEmpty() && (!trial.primitiveSolved()
                || trial.learnedWork() < trial.primitiveWork() || fullLength < primitiveLength);
        }
    }

    private static Fit fit(EvolutionGenome genome, CheckedLearnedSchemaModel model,
            List<CheckedSchemaTaskSolver.Task> tasks, boolean sparse) {
        requireInventory(genome, model);
        var inventory = TypedLearnedMoveInventory.primitives(genome);
        var primitive = inventory.newTaskSolver().asHeuristicPrimitiveFallback();
        var learned = inventory.newTaskSolverWithSharedContext(model);
        var work = new CheckedSchemaSupport.Work();
        work.add(learned.preparationWork() + inventory.primitiveProviders().size() + 6);
        var groups = new HashMap<Key, Benefits>();
        var trials = new ArrayList<Trial>();
        var pilots = new ArrayList<Pilot>();
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
            if (sparse) {
                var used = usedSchemas(candidate, work);
                pilots.add(new Pilot(key, source, charged, trial, baseline.evidence().witness().size(),
                    candidate.evidence().witness().size(), used, eligible));
                work.add(6);
            } else {
                observe(groups,key,source,trial);
            }
            work.add(Math.addExact(baseWork, candidateWork));
            work.add((sparse ? 1L : 3L) * source.length() + candidate.evidence().witness().size() + 15);
        }
        if (sparse) return refine(inventory,model,pilots,trials,work);
        return finish(model,groups,null,trials,List.of(),work);
    }

    private static void observe(Map<Key,Benefits> groups, Key key, String source, Trial trial) {
        groups.computeIfAbsent(key, ignored -> new Benefits()).observe(source, trial);
        groups.computeIfAbsent(key.anyDepth(), ignored -> new Benefits()).observe(source, trial);
    }

    private static List<String> usedSchemas(CheckedSchemaTaskSolver.Solution result, CheckedSchemaSupport.Work work) {
        var ids = new TreeSet<String>();
        for (var step : result.evidence().witness()) {
            work.add(1);
            if (step.move().sourceKind() == SearchMove.SourceKind.LEARNED) {
                var proof = (TransformationProvenance.ExactTheoryStep) step.move().provenance();
                String id = proof.evidence().binding().theoryStepId();
                ids.add(id);
                work.add(id.length() + 2L);
            }
        }
        return List.copyOf(ids);
    }

    private static Fit refine(TypedLearnedMoveInventory inventory, CheckedLearnedSchemaModel model, List<Pilot> pilots,
            List<Trial> trials, CheckedSchemaSupport.Work work) {
        var selected = new HashSet<String>();
        var support = new HashSet<Key>();
        for (var pilot : pilots) {
            work.add(4);
            if (pilot.qualifies()) {
                selected.addAll(pilot.used());
                support.add(pilot.key().anyDepth());
                work.add(pilot.used().size() + 2L);
            }
        }
        var receipts = new ArrayList<SparseTrial>();
        var groups = new HashMap<Key,Benefits>();
        boolean sameMatcher = selected.size() == model.schemas().size();
        var matcher = sameMatcher || selected.isEmpty() ? null : inventory.selectedSolver(model,true,selected);
        if (matcher != null) work.add(matcher.preparationWork());
        for (var pilot : pilots) {
            var base = pilot.trial();
            var status = Refinement.OUTSIDE_SUPPORT;
            boolean solved = false;
            long extra = 0, steps = 0;
            List<String> used = List.of();
            work.add(5);
            // Include every negative observation with that same shallow shape, at all depths.
            if (support.contains(pilot.key().anyDepth())) {
                Trial effective = base;
                if (sameMatcher) {
                    status = Refinement.PILOT_REUSED;
                    solved = base.learnedSolved();
                    steps = base.learnedSteps();
                    used = pilot.used();
                } else {
                    status = Refinement.MEASURED;
                    var result = matcher.solveWithAdmission(pilot.charged(),Set.of());
                    if (!result.evidence().accountingComplete()) throw new IllegalStateException("incomplete sparse calibration");
                    solved = result.solved() && pilot.eligible();
                    extra = result.evidence().totalWork();
                    steps = result.learnedApplications();
                    used = usedSchemas(result,work);
                    work.add(extra + result.evidence().witness().size());
                    effective = new Trial(base.index(),base.primitiveSolved(),base.primitiveWork(),solved,extra,steps);
                }
                observe(groups,pilot.key(),pilot.source(),effective);
                work.add(2L * pilot.source().length() + 5);
            }
            receipts.add(new SparseTrial(base.index(),pilot.used(),pilot.primitiveLength(),pilot.fullLength(),
                pilot.qualifies(),status,solved,extra,steps,used));
        }
        return finish(model,groups,selected,trials,receipts,work);
    }

    private static Fit finish(CheckedLearnedSchemaModel model, Map<Key,Benefits> groups, Set<String> selected,
            List<Trial> trials, List<SparseTrial> receipts, CheckedSchemaSupport.Work work) {
        var accepted = new HashSet<Key>();
        for (var entry : groups.entrySet()) {
            if (entry.getValue().worthTrying(entry.getKey().depth() >= 0)) accepted.add(entry.getKey());
        }
        work.add(5L * groups.size() + 1);
        var admission = new Admission(model.identity(), accepted, selected, work);
        return new Fit(admission, work.units, trials, receipts);
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
        return new Result(work.units, admit, answer.evidence(), task.budget().totalWork(), answer.solved());
    }

    static CheckedSchemaTaskSolver.Task remaining(CheckedSchemaTaskSolver.Task task, long spent) {
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
