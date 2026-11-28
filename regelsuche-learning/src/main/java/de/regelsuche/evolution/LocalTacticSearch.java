package de.regelsuche.evolution;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveProvider;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.NativeNodeCountObjective;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.ExprMatcher;
import de.regelsuche.transform.PatternExpr;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static de.regelsuche.evolution.CheckedSchemaSupport.Work;

/** Bounded local admission of verified schemata under arbitrary binary contexts. */
public final class LocalTacticSearch {
    private static final int MAX_SITES = 24;
    private static final int MAX_DEPTH = 12;
    private static final long PRIMITIVE_PILOT_FLOOR = 1_024;
    private static final int ANY = 0;
    private static final int NUMBER = 1;
    private static final int OTHER = 2;
    private static final int BINARY = 3;
    private static final int SYMBOLS = BINARY + BinaryOperator.values().length;
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();

    private record Entry(CheckedLearnedSchemaModel.Schema schema, ExprMatcher matcher) {}

    /** Only the matching traversal can bind a schema, path and substitutions to this immutable root. */
    static final class Site {
        private final CheckedLearnedSchemaModel.Schema schema;
        private final Expr source;
        private final List<Integer> path;
        private final Map<String, Expr> bindings;

        private Site(CheckedLearnedSchemaModel.Schema schema, Expr source, List<Integer> path, Map<String, Expr> bindings) {
            this.schema = schema;
            this.source = source;
            this.path = List.copyOf(path);
            this.bindings = bindings;
        }

        CheckedLearnedSchemaModel.Schema schema() { return schema; }
        Expr source() { return source; }
        List<Integer> path() { return path; }
        Map<String, Expr> bindings() { return bindings; }
    }

    private final Map<Integer, List<Entry>> buckets;
    private final CheckedLearnedSchemaModel model;
    private final CheckedSchemaTaskSolver fallback;
    private final List<MoveProvider> primitives;
    private final TypedMoveSearch.Verifier verifier;
    private final ExprMatcher.MatchOptions matchLimits;
    private final long preparation;

    public static LocalTacticSearch prepare(EvolutionGenome genome, CheckedLearnedSchemaModel model) {
        return new LocalTacticSearch(genome, model);
    }

    private LocalTacticSearch(EvolutionGenome genome, CheckedLearnedSchemaModel model) {
        if (!genome.contentHash().equals(model.inventoryHash())) throw new IllegalArgumentException("different primitive inventory");
        this.model = model;
        var inventory = TypedLearnedMoveInventory.primitives(genome);
        primitives = inventory.primitiveProviders();
        fallback = inventory.newTaskSolver().asHeuristicPrimitiveFallback();
        var primitiveCheck = inventory.verifier();
        var learnedCheck = model.verifierWithSharedContext();
        verifier = (state, move, context) -> move.sourceKind() == SearchMove.SourceKind.PRIMITIVE
            ? primitiveCheck.verify(state, move, context) : learnedCheck.verify(state, move, context);

        int limit = model.bounds().maximumExpressionNodes() * 4;
        matchLimits = new ExprMatcher.MatchOptions(null, 1, limit, limit);

        var work = new Work();
        work.add(primitives.size() + 8L);
        var table = new HashMap<Integer, List<Entry>>();
        for (var schema : model.schemas()) {
            work.add(1);
            if (!(schema.source() instanceof PatternExpr.Operation root)) continue;
            var entry = new Entry(schema, ExprMatcher.pattern(schema.source()));
            int left = symbol(root.left());
            int right = symbol(root.right());
            work.add(4);
            for (int l = firstKind(left); l <= lastKind(left); l++) {
                for (int r = firstKind(right); r <= lastKind(right); r++) {
                    table.computeIfAbsent(key(root.operator(), l, r), ignored -> new ArrayList<>()).add(entry);
                    work.add(3);
                }
            }
        }
        table.replaceAll((key, entries) -> {
            entries.sort((a, b) -> {
                work.add(1);
                return Integer.compare(b.schema().structuralReduction(), a.schema().structuralReduction());
            });
            work.add(entries.size() + 2L);
            return List.copyOf(entries);
        });
        buckets = Map.copyOf(table);
        work.add(table.size() + 1L);
        preparation = work.units;
    }

    public long preparationWork() { return preparation; }

    /** Both single-schema and whole-model knockouts take effect after the same root admission. */
    public CostAwareTacticRouter.Result solve(CheckedSchemaTaskSolver.Task task, Set<String> disabled) {
        Objects.requireNonNull(disabled);
        var work = new Work();
        work.add(2);
        long budget = task.budget().totalWork();
        boolean eligible = !RetainedOperation.isObserved() && task.goal().objective() == NativeNodeCountObjective.INSTANCE;
        if (!eligible || !assumptionsAvailable(task, work)) {
            var answer = fallback.solveWithSharedNodeCounts(CostAwareTacticRouter.remaining(task, work.units));
            return result(work.units, false, answer, budget);
        }

        CheckedSchemaTaskSolver.Solution pilot = null;
        long pilotWork = 0;
        if (task.budget().maxPrimitiveSteps() > 0) {
            long pilotCap = Math.min(budget, Math.max(PRIMITIVE_PILOT_FLOOR, budget / 2));
            pilot = fallback.solveWithSharedNodeCounts(CostAwareTacticRouter.remaining(task, budget - pilotCap + work.units));
            pilotWork = pilot.evidence().totalWork();
            if (pilot.solved() || pilotWork >= budget - work.units) return result(work.units, false, pilot, budget);
        }

        Site first = find(task.source(), Set.of(), work, budget - pilotWork);
        if (first == null && pilot != null) return result(work.units, false, pilot, budget);
        var solver = fallback;
        if (first != null) {
            var providers = new ArrayList<>(primitives);
            providers.add(new LocalProvider(first, disabled, budget - pilotWork));
            solver = new CheckedSchemaTaskSolver(new TypedLearnedMoveInventory.SearchSession(providers, List.of(), verifier), null);
            work.add(2L * providers.size() + 5);
        }
        if (pilot != null && pilotWork >= budget - work.units) return result(work.units, first != null, pilot, budget);
        var answer = solver.solveWithSharedNodeCounts(CostAwareTacticRouter.remaining(task, work.units + pilotWork));
        if (pilot != null && !answer.solved() && pilot.evidence().outputScore() < answer.evidence().outputScore())
            return result(work.units + answer.evidence().totalWork(), first != null, pilot, budget);
        return result(work.units + pilotWork, first != null, answer, budget);
    }

    private static CostAwareTacticRouter.Result result(long selectionWork, boolean admitted, CheckedSchemaTaskSolver.Solution answer,
            long budget) {
        return new CostAwareTacticRouter.Result(selectionWork, admitted, answer.evidence(), budget, answer.solved());
    }

    private boolean assumptionsAvailable(CheckedSchemaTaskSolver.Task task, Work work) {
        var required = model.requiredAssumptions();
        work.add(1);
        if (required.isEmpty()) return true;
        var available = AssumptionSignature.ofExpressions(task.assumptions()).normalizedAssumptions();
        work.add(available.size() + (1L + available.size()) * required.size());
        return available.containsAll(required);
    }

    private final class LocalProvider implements TypedMoveSearch.TypedProvider {
        private final Site first;
        private final Set<String> disabled;
        private final long budget;
        private Boolean allDisabled;

        LocalProvider(Site first, Set<String> disabled, long budget) {
            this.first = first;
            this.disabled = disabled;
            this.budget = budget;
        }

        @Override public Descriptor descriptor() { return model.localDescriptor(); }

        @Override public Batch candidates(MoveState state, MoveContext context) {
            var work = new Work();
            work.add(3);
            if (!context.carries(model.requiredAssumptions(), state) || state.expression().length() > 262_144
                    || allDisabled(work)) return model.localCandidate(null, work);
            Expr source = CODEC.decodeExpression(state.expression());
            Site site = source == first.source() && !disabled.contains(first.schema().id())
                ? first : find(source, disabled, work, budget);
            return model.localCandidate(site, work);
        }

        private boolean allDisabled(Work work) {
            if (disabled.size() < model.schemas().size()) return false;
            if (allDisabled == null) {
                allDisabled = model.schemas().stream().allMatch(schema -> {
                    work.add(1);
                    return disabled.contains(schema.id());
                });
            }
            return allDisabled;
        }
    }

    private Site find(Expr source, Set<String> disabled, Work work, long cap) {
        return new Scan(source, disabled, work, cap).visit(source, 0, 0);
    }

    private final class Scan {
        private final Expr source;
        private final Set<String> disabled;
        private final Work work;
        private final long cap;
        private int visits;

        Scan(Expr source, Set<String> disabled, Work work, long cap) {
            this.source = source;
            this.disabled = disabled;
            this.work = work;
            this.cap = cap;
        }

        Site visit(Expr expression, int pathBits, int depth) {
            work.add(1);
            if (++visits > MAX_SITES || depth > MAX_DEPTH || work.units >= cap) return null;
            if (!(expression instanceof BinaryExpr binary)) return null;
            work.add(3);
            int key = key(binary.operator(), symbol(binary.left()), symbol(binary.right()));
            for (Entry entry : buckets.getOrDefault(key, List.of())) {
                work.add(2);
                if (disabled.contains(entry.schema().id())) continue;
                var match = entry.matcher().match(expression, matchLimits);
                work.add((long) match.evaluatedSteps() + match.patternBranches());
                if (match.complete() && match.matched()) {
                    var path = new ArrayList<Integer>(depth);
                    for (int i = depth - 1; i >= 0; i--) path.add((pathBits >> i) & 1);
                    work.add(2L * depth + 1);
                    return new Site(entry.schema(), source, path, match.matches().getFirst().bindings());
                }
                if (work.units >= cap) return null;
            }
            Site left = visit(binary.left(), pathBits << 1, depth + 1);
            return left == null ? visit(binary.right(), (pathBits << 1) | 1, depth + 1) : left;
        }
    }

    private static int key(BinaryOperator root, int left, int right) {
        return ((BINARY + root.ordinal()) * SYMBOLS + left) * SYMBOLS + right;
    }

    private static int firstKind(int symbol) { return symbol == ANY ? NUMBER : symbol; }
    private static int lastKind(int symbol) { return symbol == ANY ? SYMBOLS - 1 : symbol; }

    private static int symbol(Expr expression) {
        if (expression instanceof BinaryExpr binary) return BINARY + binary.operator().ordinal();
        return expression instanceof NumberExpr ? NUMBER : OTHER;
    }

    private static int symbol(PatternExpr pattern) {
        if (pattern instanceof PatternExpr.Operation operation) return BINARY + operation.operator().ordinal();
        if (pattern instanceof PatternExpr.Placeholder) return ANY;
        return pattern instanceof PatternExpr.LiteralNumber ? NUMBER : OTHER;
    }
}
