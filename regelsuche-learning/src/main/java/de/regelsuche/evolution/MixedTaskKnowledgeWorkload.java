package de.regelsuche.evolution;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import java.util.List;

/** Public, prospective within-family workload. Inputs are fixed before any timing or learning. */
public final class MixedTaskKnowledgeWorkload {
    public static final String REVISION = "regelsuche.knowledge-mixed-workload/v2";
    private static final MoveSearch.Budget BUDGET = new MoveSearch.Budget(32, 32, 200_000, 512, 200_000);
    public record Case(String role, String source, int maximumNodes) {
        public CheckedSchemaTaskSolver.Task task() { return MixedTaskKnowledgeWorkload.task(source, maximumNodes); }
    }
    private MixedTaskKnowledgeWorkload() { }

    /** Equal role weights match evaluation; constants distinguish canonical polynomials, not just names. */
    public static List<Case> promotion() { return cases(new int[] {29, 31, 37, 41, 43, 47}, 2, 2); }
    public static List<Case> evaluation() { return cases(new int[] {53, 59, 61, 67, 71, 73}, 3, 3); }
    private static List<Case> cases(int[] offsets, int negativeCoefficient, int smallConstant) {
        return List.of(
            new Case("shallow", nested("r", 1, "t") + "+" + offsets[0], 7),
            new Case("medium", nested("r", 2, "t") + "+" + offsets[1], 9),
            new Case("deep", nested("r", 3, "t") + "+" + offsets[2], 11),
            new Case("compound-sum", nested("(r+s)", 1, "t") + "+" + offsets[3], 7),
            new Case("compound-product", nested("(r*s)", 2, "t") + "+" + offsets[4], 9),
            new Case("offset", nested("z", 2, "w") + "+" + offsets[5], 9),
            new Case("negative", "(r+s)*(r-s)+s*(s+" + negativeCoefficient + ")", 1),
            new Case("already-solved", Integer.toString(smallConstant), 1));
    }
    public static List<TaskKnowledgeSelfImprover.Batch> seedBatches() { return List.of(batch(1), batch(2)); }
    public static List<TaskKnowledgeSelfImprover.Batch> improvementBatches() {
        return List.of(batch(3), new TaskKnowledgeSelfImprover.Batch(
            List.of(task("x+13", 1), task("y+17", 1)), List.of(task("z+19", 1)), promotionTasks()));
    }
    static List<CheckedSchemaTaskSolver.Task> promotionTasks() { return promotion().stream().map(Case::task).toList(); }
    private static TaskKnowledgeSelfImprover.Batch batch(int depth) {
        int goal = 2 * depth + 3;
        return new TaskKnowledgeSelfImprover.Batch(
            List.of(task(nested("a", depth, "b") + "+3", goal), task("5*(" + nested("c", depth, "d") + ")", goal)),
            List.of(task(nested("p*q", depth, "v"), goal), task(nested("z", depth, "w") + "+7", goal),
                task("x", 1), task("(x+y)*(x-y)+y*(y+1)", 1)), promotionTasks());
    }
    static CheckedSchemaTaskSolver.Task task(String source, int nodes) {
        return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source).expression(), List.of(),
            CheckedSchemaTaskSolver.Goal.atMostNodes(nodes), BUDGET);
    }
    private static String nested(String base, int depth, String prefix) {
        if (depth == 0) return base;
        String inner = nested(base, depth - 1, prefix), b = prefix + depth;
        return "((" + inner + "+" + b + ")*(" + inner + "-" + b + ")+" + b + "*" + b + ")";
    }
}
