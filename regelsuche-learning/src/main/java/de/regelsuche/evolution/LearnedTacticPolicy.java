package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.retention.RetainedGraph;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Cost-sensitive choice of a previously proved schema for the current search expression. */
public final class LearnedTacticPolicy implements RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v) {
        v.reference(modelId); v.reference(roots); v.reference(support); v.reference(fingerprint);
    }
    static final String PRIMITIVE = "";
    static final String ALL = "all-proved-schemata";
    private static final int POSITIONS = 7;
    private static final int MAX_TREE_DEPTH = 2;
    private static final int MIN_SUPPORT = 2;
    private static final int MAX_SUFFIXES = 2;
    private final String modelId;
    private final Map<String, Node> roots;
    private final Map<String,Set<Long>> support;
    private final String fingerprint;

    private LearnedTacticPolicy(String modelId, Map<String,Node> roots, Map<String,Set<Long>> support, CheckedSchemaSupport.Work work) {
        this.modelId = modelId;
        this.roots = Map.copyOf(roots);
        this.support = Map.copyOf(support);
        var config = CheckedSchemaSupport.JSON.createObjectNode().put("revision","state-tactic-policy/v1").put("model",modelId);
        var goals = config.putObject("goals");
        new TreeMap<>(roots).forEach((key,root) -> {
            var goal = goals.putObject(key);
            writeTree(goal.putObject("tree"),root);
            var shapes = goal.putArray("support");
            support.get(key).stream().sorted().forEach(shapes::add);
        });
        String canonical = CheckedSchemaSupport.write(config);
        work.add(canonical.length() + 1L);
        fingerprint = SchematicProofPlan.hash(canonical);
    }

    private static void writeTree(com.fasterxml.jackson.databind.node.ObjectNode value, Node node) {
        value.put("position",node.position()).put("action",node.action());
        var children = value.putObject("children");
        new TreeMap<>(node.children()).forEach((key,child) -> writeTree(children.putObject(Integer.toString(key)),child));
    }

    public String fingerprint() { return fingerprint; }

    public record Trial(int lesson, int suffix, String schemaId, boolean baselineSolved, long baselineWork,
            boolean candidateSolved, long candidateWork, long learnedSteps) {}
    public record Fit(LearnedTacticPolicy policy, long work, List<Trial> trials) {
        public Fit { trials = List.copyOf(trials); }
    }
    private record Row(int lesson, int[] features, Score[] scores) {}
    private record Score(int solved, long paid) {
        static final Score ZERO = new Score(0, 0);
        Score plus(Score other) { return new Score(Math.addExact(solved, other.solved), Math.addExact(paid, other.paid)); }
        boolean betterThan(Score other) { return solved > other.solved || solved == other.solved && paid < other.paid; }
    }
    private record Node(int position, String action, Map<Integer, Node> children) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v) { v.reference(action); v.reference(children); }
    }
    private record Choice(int index, Score score) {}

    /** Solve every calibration source against the same primitive solver and each individual checked schema. */
    public static Fit fit(EvolutionGenome genome, CheckedLearnedSchemaModel model,
            List<CheckedSchemaTaskSolver.Task> calibration) {
        var inventory = TypedLearnedMoveInventory.primitives(genome);
        if (!genome.contentHash().equals(model.inventoryHash())) throw new IllegalArgumentException("model inventory mismatch");
        var base = inventory.newTaskSolver();
        var work = new CheckedSchemaSupport.Work();
        var solvers = new ArrayList<CheckedSchemaTaskSolver>();
        var actions = new ArrayList<String>();
        solvers.add(base);
        actions.add(PRIMITIVE);
        for (var schema : model.schemas()) {
            var probe = inventory.solverForSchema(model, schema.id());
            work.add(probe.preparationWork() + 2);
            solvers.add(probe);
            actions.add(schema.id());
        }
        var all = inventory.newTaskSolverWithDomainReceipts(model);
        work.add(all.preparationWork() + 2);
        solvers.add(all);
        actions.add(ALL);
        var groups = new TreeMap<String,List<Row>>();
        var trials = new ArrayList<Trial>();
        for (int i = 0; i < calibration.size(); i++) {
            var task = calibration.get(i);
            var family = TaskKnowledgeProfile.objectiveKey(task);
            if (family == null) throw new IllegalArgumentException("unidentified training objective");
            var rows = groups.computeIfAbsent(family, ignored -> new ArrayList<>());
            var baseline = base.solve(task);
            observe(i, 0, task, baseline, solvers, actions, rows, trials, work);
            var witness = baseline.evidence().witness();
            for (int j = 0; j < Math.min(MAX_SUFFIXES, witness.size() - 1); j++) {
                var sub = new CheckedSchemaTaskSolver.Task(witness.get(j).target().expression(),
                    task.assumptions(), task.goal(), task.budget());
                observe(i, j + 1, sub, base.solve(sub), solvers, actions, rows, trials, work);
            }
        }
        var roots = new TreeMap<String,Node>();
        groups.forEach((name, rows) -> roots.put(name, fitTree(rows, actions, MAX_TREE_DEPTH, work)));
        var support = new TreeMap<String,Set<Long>>();
        groups.forEach((goal,rows) -> {
            var observed = new java.util.TreeSet<Long>();
            for (Row row : rows) {
                observed.add(shapeKey(row.features()));
                work.add(POSITIONS + 1);
            }
            support.put(goal,Collections.unmodifiableSortedSet(observed));
        });
        var policy = new LearnedTacticPolicy(model.identity(), roots, support, work);
        return new Fit(policy, work.units, trials);
    }

    private static void observe(int lesson, int suffix, CheckedSchemaTaskSolver.Task task,
            CheckedSchemaTaskSolver.Solution base, List<CheckedSchemaTaskSolver> solvers, List<String> actions,
            List<Row> rows, List<Trial> trials, CheckedSchemaSupport.Work work) {
        var costs = new Score[solvers.size()];
        if (!base.evidence().accountingComplete()) throw new IllegalStateException("incomplete primitive calibration");
        costs[0] = new Score(base.solved() ? 1 : 0, base.evidence().totalWork());
        work.add(costs[0].paid() + 1);
        for (int i = 1; i < solvers.size(); i++) {
            var candidate = solvers.get(i).solve(task);
            if (!candidate.evidence().accountingComplete()) throw new IllegalStateException("incomplete tactic calibration");
            long paid = candidate.evidence().totalWork();
            long used = candidate.learnedApplications();
            trials.add(new Trial(lesson,suffix,actions.get(i),base.solved(),costs[0].paid(),
                candidate.solved(),paid,used));
            var score = new Score(candidate.solved() ? 1 : 0, paid);
            // A cheaper primitive-only witness gives no positive evidence for this tactic.
            costs[i] = used == 0 && score.betterThan(costs[0]) ? costs[0] : score;
            work.add(paid + candidate.evidence().witness().size() + 5);
        }
        int[] features = new int[POSITIONS];
        for (int p = 0; p < POSITIONS; p++) features[p] = feature(task.source(), p, work);
        rows.add(new Row(lesson,features,costs));
    }

    private static Choice cheapest(List<Row> rows, int actions, CheckedSchemaSupport.Work work) {
        var sums = new Score[actions];
        java.util.Arrays.fill(sums, Score.ZERO);
        var support = new HashSet<Integer>();
        for (var row : rows) {
            support.add(row.lesson());
            for (int i = 0; i < actions; i++) sums[i] = sums[i].plus(row.scores()[i]);
        }
        int best = 0;
        if (support.size() >= MIN_SUPPORT) {
            for (int i = 1; i < actions; i++) if (sums[i].betterThan(sums[best])) best = i;
        }
        work.add((long) rows.size() * (actions + 1) + 2L * actions + 1);
        return new Choice(best,sums[best]);
    }

    private static Node fitTree(List<Row> rows, List<String> actions, int depth, CheckedSchemaSupport.Work work) {
        Choice leaf = cheapest(rows, actions.size(), work);
        Score best = leaf.score();
        Map<Integer,List<Row>> winning = Map.of();
        int bestPosition = -1;
        if (depth > 0) {
            for (int pos = 0; pos < POSITIONS; pos++) {
                var split = new TreeMap<Integer,List<Row>>();
                for (Row row : rows) split.computeIfAbsent(row.features()[pos], k -> new ArrayList<>()).add(row);
                work.add(rows.size() + 1L);
                if (split.size() < 2) continue;
                var score = Score.ZERO;
                for (var group : split.values()) score = score.plus(cheapest(group, actions.size(), work).score());
                // Pay for consulting this feature on every training state when comparing alternatives.
                score = score.plus(new Score(0, (long)rows.size() * featurePrice(pos)));
                if (score.betterThan(best)) {
                    best = score;
                    bestPosition = pos;
                    winning = split;
                }
            }
        }
        if (bestPosition < 0) return new Node(-1,actions.get(leaf.index()),Map.of());
        var children = new TreeMap<Integer,Node>();
        winning.forEach((value, group) -> children.put(value,fitTree(group, actions, depth-1, work)));
        return new Node(bestPosition,PRIMITIVE,Collections.unmodifiableMap(children));
    }

    private static int featurePrice(int position) {
        return 3 + Integer.numberOfTrailingZeros(Integer.highestOneBit(position + 1));
    }

    // Binary heap positions address at most two edges. Variables, constants and function names
    // are anonymous. Neither the absolute size goal nor a benchmark/task identifier is a feature.
    private static int feature(Expr source, int position, CheckedSchemaSupport.Work work) {
        int index = position + 1;
        work.add(featurePrice(position));
        for (int bit = Integer.highestOneBit(index) >>> 1; bit != 0; bit >>>= 1) {
            if (!(source instanceof BinaryExpr b)) return 0;
            source = (index & bit) == 0 ? b.left() : b.right();
        }
        if (source instanceof BinaryExpr b) return 4 + b.operator().ordinal();
        if (source instanceof NumberExpr) return 1;
        if (source instanceof FunctionExpr) return 2;
        return 3;
    }

    private static final int[] PREORDER = {0,1,3,4,2,5,6};

    private static long shapeKey(int[] features) {
        long key = 0;
        for (int pos : PREORDER) key = (key << 8) | features[pos];
        return key;
    }

    private static long shapeKey(Expr source, int level, CheckedSchemaSupport.Work work) {
        // Store a preorder depth-two fingerprint directly from the immutable tree.
        int value;
        if (source == null) value = 0;
        else if (source instanceof BinaryExpr binary) value = 4 + binary.operator().ordinal();
        else if (source instanceof NumberExpr) value = 1;
        else if (source instanceof FunctionExpr) value = 2;
        else value = 3;
        work.add(1);
        if (level == 0) return value;
        BinaryExpr b = source instanceof BinaryExpr binary ? binary : null;
        int childBytes = level == 2 ? 3 : 1;
        return (long)value << (16 * childBytes)
            | shapeKey(b == null ? null : b.left(),level - 1,work) << (8 * childBytes)
            | shapeKey(b == null ? null : b.right(),level - 1,work);
    }

    String select(Expr source, String goal, CheckedSchemaSupport.Work work) {
        work.add(2);
        Node n = goal == null ? null : roots.get(goal);
        if (n == null) return PRIMITIVE;
        if (!support.get(goal).contains(shapeKey(source,2,work))) return ALL;
        while (n != null && n.position() >= 0) n = n.children().get(feature(source,n.position(),work));
        return n == null ? ALL : n.action();
    }

    void requireModel(CheckedLearnedSchemaModel model) {
        if (!modelId.equals(model.identity())) throw new IllegalArgumentException("policy belongs to a different schema model");
    }

    record Dispatch(LearnedTacticPolicy policy, Map<String,CheckedSchemaMatcherPlan> plans,
            Set<String> disabled) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v) { v.reference(plans); v.reference(disabled); v.reference(policy); }
        CheckedSchemaMatcherPlan choose(Expr expression, String goal, CheckedSchemaMatcherPlan all, CheckedSchemaSupport.Work work) {
            String id = policy.select(expression,goal,work);
            work.add(2);
            return ALL.equals(id) ? all : plans.get(id);
        }
    }
}
