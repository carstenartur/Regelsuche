package de.regelsuche.evolution;

import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveProvider;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.NativeNodeCountObjective;
import de.regelsuche.search.moves.SearchContinuationContract;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.StateValue;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Optional greedy proposal ordering learned from ordinary replayed primitive witnesses. */
public final class LearnedPrimitiveOrdering {
    public static final int INITIAL = -1;
    public static final int UNSEEN = -2;

    /** Rows contain inventory indices in descending successor frequency, ties in inventory order. */
    public record Model(String inventoryHash, Map<Integer,List<Integer>> orders) {
        public Model {
            var immutable = new TreeMap<Integer,List<Integer>>();
            orders.forEach((previous, order) -> immutable.put(previous, List.copyOf(order)));
            orders = Map.copyOf(immutable);
        }
    }

    public record Lesson(boolean solved, long work, List<String> rules) {
        public Lesson { rules = List.copyOf(rules); }
    }
    public record Fit(Model model, long acquisitionWork, long formationWork, List<Lesson> lessons) {
        public Fit { lessons = List.copyOf(lessons); }
    }

    private final CheckedSchemaTaskSolver solver;
    private final GreedyProvider provider;
    private final long loadWork;

    private LearnedPrimitiveOrdering(TypedLearnedMoveInventory inventory, GreedyProvider provider, long loadWork) {
        this.solver = inventory.newTaskSolver();
        this.provider = provider;
        this.loadWork = loadWork;
    }

    public static Fit fit(EvolutionGenome genome, List<CheckedSchemaTaskSolver.Task> tasks) {
        var inventory = TypedLearnedMoveInventory.primitives(genome);
        var solver = inventory.newTaskSolver();
        var ids = inventory.primitiveProviders().stream().map(p -> p.descriptor().id()).toList();
        var indices = new HashMap<String,Integer>();
        for (int i = 0; i < ids.size(); i++) indices.put(ids.get(i), i);
        var counts = new TreeMap<Integer,Map<Integer,Long>>();
        var lessons = new ArrayList<Lesson>();
        long acquisition = solver.preparationWork();
        long[] formation = {1L + 2L * ids.size()};
        for (var task : tasks) {
            var solution = solver.solve(task);
            long work = solution.evidence().totalWork();
            acquisition = Math.addExact(acquisition, work);
            var rules = new ArrayList<String>();
            formation[0]++;
            if (solution.solved()) {
                int previous = INITIAL;
                for (var step : solution.evidence().witness()) {
                    if (step.move().sourceKind() != SearchMove.SourceKind.PRIMITIVE)
                        throw new IllegalStateException("primitive lesson contains a nonprimitive edge");
                    String rule = step.move().ruleId();
                    int next = indices.get(rule);
                    counts.computeIfAbsent(previous, k -> new HashMap<>()).merge(next, 1L, Math::addExact);
                    counts.computeIfAbsent(UNSEEN, k -> new HashMap<>()).merge(next, 1L, Math::addExact);
                    rules.add(rule);
                    formation[0] += 7;
                    previous = next;
                }
            }
            lessons.add(new Lesson(solution.solved(), work, rules));
        }
        var orders = new TreeMap<Integer,List<Integer>>();
        for (var entry : counts.entrySet()) {
            var order = new ArrayList<Integer>(ids.size());
            for (int i = 0; i < ids.size(); i++) order.add(i);
            var frequency = entry.getValue();
            order.sort(Comparator.comparingLong((Integer i) -> {
                formation[0] += 2;
                return frequency.getOrDefault(i, 0L);
            }).reversed());
            formation[0] += 2L * ids.size() + 2;
            orders.put(entry.getKey(), order);
        }
        return new Fit(new Model(genome.contentHash(), orders), acquisition, formation[0], lessons);
    }

    public static LearnedPrimitiveOrdering load(EvolutionGenome genome, Model model) {
        if (!genome.contentHash().equals(model.inventoryHash()))
            throw new IllegalArgumentException("primitive inventory mismatch");
        var inventory = TypedLearnedMoveInventory.primitives(genome);
        var original = inventory.primitiveProviders();
        var rows = new HashMap<String,List<MoveProvider>>();
        List<MoveProvider> initial = original, unseen = original;
        long work = 1L + model.inventoryHash().length();
        for (var entry : model.orders().entrySet()) {
            int key = entry.getKey();
            if (key < UNSEEN || key >= original.size() || entry.getValue().size() != original.size())
                throw new IllegalArgumentException("invalid primitive order row");
            var seen = new boolean[original.size()];
            var sorted = new ArrayList<MoveProvider>(original.size());
            for (int index : entry.getValue()) {
                if (index < 0 || index >= seen.length || seen[index]) throw new IllegalArgumentException("invalid inventory permutation");
                seen[index] = true;
                sorted.add(original.get(index));
            }
            var order = List.copyOf(sorted);
            if (key == INITIAL) initial = order;
            else if (key == UNSEEN) unseen = order;
            else rows.put(original.get(key).descriptor().id(), order);
            work += 4L + 5L * order.size();
        }
        return new LearnedPrimitiveOrdering(inventory, new GreedyProvider(initial, unseen, Map.copyOf(rows), true), work);
    }

    /** The same single-successor heuristic, without paying for or consulting a model. */
    public static LearnedPrimitiveOrdering untrained(EvolutionGenome genome) {
        var inventory = TypedLearnedMoveInventory.primitives(genome);
        var order = inventory.primitiveProviders();
        return new LearnedPrimitiveOrdering(inventory, new GreedyProvider(order, order, Map.of(), false), 0);
    }

    public long loadWork() { return loadWork; }

    public TypedSourceOnlySearch.Result solve(CheckedSchemaTaskSolver.Task task) {
        if (task.goal().objective() != NativeNodeCountObjective.INSTANCE || RetainedOperation.isObserved())
            return solver.solve(task).evidence();
        return CompiledAstReplayCodec.withExpressionCache(256, 2_000_000, () -> {
            var original = solver.scopedTask(task).problem();
            var count = new SubtreeNodeCounts();
            var problem = new TypedMoveSearch.Problem(original.source(), original.context(), List.of(provider),
                TypedMoveSearch.Policy.INVENTORY_ORDER, original.verifier(), original.stateScore(), original.mode(),
                MoveSearch.Scheduling.STAGED, original.budget(), (state, context) -> {
                    var size = count.evaluate(state);
                    return new StateValue.Assessment(Math.toIntExact(size.value()), -size.value(), size.work(), 0, Map.of());
                });
            return new TypedSourceOnlySearch().searchUntil(problem, count, task.goal().maximumOutputScore(),
                SearchContinuationContract.PATH_SENSITIVE);
        });
    }

    private record GreedyProvider(List<MoveProvider> initial, List<MoveProvider> unseen,
            Map<String,List<MoveProvider>> following, boolean trained) implements TypedMoveSearch.TypedProvider {
        private static final Descriptor DESCRIPTOR = new Descriptor("prior-primitive-order/v1", "primitive order",
            SearchMove.SourceKind.PREPARATION, SearchMove.ProofStrength.REPLAYABLE, List.of(),
            SearchMove.ValueEvidence.UNKNOWN, "primitive-training-witnesses");

        @Override public Descriptor descriptor() { return DESCRIPTOR; }
        @Override public Batch candidates(MoveState state, MoveContext context) {
            var order = !trained || state.searchDepth() == 0 ? initial : following.getOrDefault(state.previousRule(), unseen);
            var work = TransformationWorkMetrics.ZERO.withDelegatedMechanicalWork(trained ? 2 : 1);
            for (var candidate : order) {
                var batch = candidate.candidates(state, context);
                work = work.plus(batch.work());
                work = work.withDelegatedMechanicalWork(Math.incrementExact(work.delegatedMechanicalWorkUnits()));
                if (!batch.moves().isEmpty()) return new Batch(List.of(batch.moves().getFirst()), work, false);
            }
            return new Batch(List.of(), work, false);
        }
    }
}
