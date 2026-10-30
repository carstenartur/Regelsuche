package de.regelsuche.evolution;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy;
import java.util.List;

/** Executable with the real learner, search, schema checker and replay; no test doubles. */
final class CheckedSchemaTaskSolverContract {
    private static final MoveSearch.Budget AMPLE = new MoveSearch.Budget(24, 24, 100_000, 256, 100_000);
    private final TypedLearnedMoveInventory inventory;
    private final CheckedLearnedSchemaModel learned;
    private final CheckedLearnedSchemaModel restored;
    private final CheckedSchemaTaskSolver baseline;
    private final CheckedSchemaTaskSolver solver;

    CheckedSchemaTaskSolverContract() {
        var training = List.of(
            new TraceRewriteStrategyLearner.Input("lesson-offset", pair("a", "b", "c") + "+3"),
            new TraceRewriteStrategyLearner.Input("lesson-scale", "5*(" + pair("d", "e", "f") + ")"));
        var limits = new TraceRewriteStrategyLearner.Limits(
            WorkBudgetBestFirstSearchStrategy.Budget.primitive(8, 256, 32, 8, 100_000), 8, 8, 64);
        var formation = new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(), training, limits);
        inventory = formation.typedMoves();
        learned = inventory.checkedSchemas();
        restored = CheckedLearnedSchemaModel.load(learned.toCanonicalJson(), learned.inventoryHash());
        baseline = TypedLearnedMoveInventory.primitives(TraceStrategyTransferExample.inventory()).newTaskSolver();
        solver = inventory.newTaskSolver(restored);
    }

    void learnedProcedureReplacesSixPrimitiveStepsWithoutOpeningPrimitiveAlternatives() {
        var task = task(pair("u", "v", "w"), 5, AMPLE);
        var before = baseline.solve(task);
        var after = solver.solve(task);
        require(before.solved() && after.solved(), "both arms must solve the same declared task");
        require(before.evidence().incumbent().expression().equals(after.evidence().incumbent().expression()), "same output");
        require(before.primitiveSteps() == 6, "the baseline really derives the six-step result");
        require(after.learnedApplications() == 1 && after.primitiveSteps() == 0, "one genuinely learned application");
        require(after.evidence().search().events().stream().noneMatch(e -> e.move().sourceKind() == SearchMove.SourceKind.PRIMITIVE),
            "do not produce primitive alternatives after the learned result suffices");
        require(after.evidence().replayWork() > 0, "final independent replay remains paid");
        require(after.evidence().totalWork() < before.evidence().totalWork(), "query work must actually be saved in this regression");
        var commonBudget = new MoveSearch.Budget(24, 24, 100_000, 256, after.evidence().totalWork());
        require(solver.solve(task(pair("u", "v", "w"), 5, commonBudget)).solved(), "affordable learned completion");
        require(!baseline.solve(task(pair("u", "v", "w"), 5, commonBudget)).solved(), "same budget cannot fund the primitive route");
    }

    void primitiveFallbackSolvesOnlyTheUnfinishedRemainder() {
        var result = solver.solve(task(pair("r", "s", "t") + "+0", 5, AMPLE));
        require(result.solved(), "remaining add-zero task must be solved");
        require(result.learnedApplications() == 1 && result.primitiveSteps() == 1,
            "learned procedure handles the known part; the base handles only add-zero");
        require(result.evidence().witness().getFirst().move().sourceKind() == SearchMove.SourceKind.LEARNED, "knowledge first");
        require(result.evidence().witness().getLast().move().sourceKind() == SearchMove.SourceKind.PRIMITIVE, "residual fallback");
    }

    void taskCompletionDoesNotPretendToBeExhaustiveExploration() {
        var task = task(pair("u", "v", "w"), 5, AMPLE);
        var stopped = solver.solve(task);
        var explored = CompiledAstReplayCodec.withExpressionCache(256, 2_000_000,
            () -> new TypedSourceOnlySearch().search(solver.problem(task), task.goal().objective()));
        require(stopped.solved(), "task solved");
        require(!stopped.evidence().search().completeBoundedRelation(), "stopping is not exhaustive enumeration");
        require(stopped.evidence().totalWork() < explored.totalWork(), "verified completion must avoid subsequent exploration");
        require(stopped.evidence().outputScore() == explored.outputScore(), "same achieved quality in this regression");
    }

    void freshInvocationsAndReloadedModelsHaveTheSameResults() {
        var task = task(pair("k", "m", "n"), 5, AMPLE);
        var first = solver.solve(task);
        var second = solver.solve(task);
        var inMemory = inventory.newTaskSolver(learned).solve(task);
        require(first.evidence().totalWork() == second.evidence().totalWork(), "fresh query, no previous-result cache");
        require(first.evidence().witness().equals(second.evidence().witness()), "no mutable cursor leakage");
        require(first.evidence().witness().equals(inMemory.evidence().witness()), "save/load preserves learned execution");
        require(solver.preparationWork() > 0 && baseline.preparationWork() == 0, "schema compilation is explicit, not free");
    }

    void alreadySatisfiedTasksDoNotOpenProviders() {
        var result = solver.solve(task("x", 1, AMPLE));
        require(result.solved(), "input already satisfies declared criterion");
        require(result.evidence().search().events().isEmpty() && result.evidence().witness().isEmpty(), "no unnecessary work");
        require(result.evidence().replayWork() == 0, "no invented proof step");
    }

    void finalReplayAndOtherLimitsCanNeverBeHiddenByASolvedFlag() {
        var task = task(pair("u", "v", "w"), 5, AMPLE);
        var reference = solver.solve(task);
        long less = reference.evidence().totalWork() - 1;
        var limited = solver.solve(task(pair("u", "v", "w"), 5,
            new MoveSearch.Budget(24, 24, 100_000, 256, less)));
        require(!limited.solved(), "final replay cannot spend an invisible second budget");
        require(!solver.solve(task(pair("u", "v", "w"), 5, new MoveSearch.Budget(0, 0, 0, 1, 1))).solved(), "tiny budget is not success");
    }

    void missingAssumptionsDoNotAuthorizeLearnedApplications() {
        var gated = inventory.newTaskSolver(restored.requiring(List.of("u > 0")));
        var absent = gated.solve(task(pair("u", "v", "w"), 5, AMPLE));
        require(absent.solved() && absent.learnedApplications() == 0, "base remains available without granting missing premises");
        var carried = new CheckedSchemaTaskSolver.Task(parse(pair("u", "v", "w")), List.of("u > 0"),
            CheckedSchemaTaskSolver.Goal.atMostNodes(5), AMPLE);
        require(gated.solve(carried).learnedApplications() == 1, "explicitly carried premise enables the schema");
        require(gated.solve(task(pair("u", "v", "w"), 5, AMPLE)).learnedApplications() == 0, "premises never leak across calls");
    }

    void unsupportedAndNearMissTasksRemainExplicitlyUnsolved() {
        var unsupported = solver.solve(task("sin(x)+1", 1, AMPLE));
        require(!unsupported.solved() && unsupported.learnedApplications() == 0, "unsupported is not a fabricated proof");
        var nearMiss = solver.solve(task("(x+y)*(x-y)+y*(y+1)", 1, AMPLE));
        require(!nearMiss.solved(), "a cheap but unattainable criterion is not success");
        require(nearMiss.evidence().totalWork() > 0, "failed work remains visible");
    }

    void invalidGoalsAndInputsAreRejectedBeforeExecution() {
        expect(IllegalArgumentException.class, () -> CheckedSchemaTaskSolver.Goal.atMostNodes(0));
        expect(NullPointerException.class, () -> new CheckedSchemaTaskSolver.Goal(null, 1));
        expect(NullPointerException.class, () -> solver.solve(null));
        expect(NullPointerException.class, () -> inventory.newTaskSolver(null));
        expect(IllegalArgumentException.class, () -> CheckedLearnedSchemaModel.load(learned.toCanonicalJson(), "sha256:" + "0".repeat(64)));
        var assumptions = new java.util.ArrayList<String>();
        var task = new CheckedSchemaTaskSolver.Task(parse("x"), assumptions, CheckedSchemaTaskSolver.Goal.atMostNodes(1), AMPLE);
        assumptions.add("x > 0");
        require(task.assumptions().isEmpty(), "task assumptions are a defensive immutable snapshot");
    }

    private static void expect(Class<? extends Throwable> expected, Runnable action) {
        try { action.run(); }
        catch (Throwable failure) {
            if (expected.isInstance(failure)) return;
            throw new AssertionError("unexpected failure", failure);
        }
        throw new AssertionError("expected " + expected.getSimpleName());
    }

    void printDevelopmentComparison() {
        System.out.println("TASK_SETUP training=" + inventory.formationWork() + " schemaFormation=" + learned.formationWork()
            + " restoration=" + restored.loadWork() + " compilation=" + solver.preparationWork());
        for (String input : List.of(pair("u", "v", "w"), pair("p*q", "r", "s") + "+0", "x", "(x+y)*(x-y)+y*(y+1)")) {
            int goal = input.equals("x") ? 1 : input.contains("p*q") ? 7 : input.contains("y+1") ? 1 : 5;
            var task = task(input, goal, AMPLE);
            var b = baseline.solve(task); var l = solver.solve(task);
            System.out.println("TASK_CASE input=" + input + " bound=" + goal + " baseSolved=" + b.solved()
                + " learnedSolved=" + l.solved() + " baseWork=" + b.evidence().totalWork()
                + " learnedWork=" + l.evidence().totalWork() + " learnedApplications=" + l.learnedApplications()
                + " primitiveSteps=" + l.primitiveSteps());
        }
    }

    static String pair(String a, String b, String c) {
        String inner = "((" + a + "+" + b + ")*(" + a + "-" + b + ")+" + b + "*" + b + ")";
        return "((" + inner + "+" + c + ")*(" + inner + "-" + c + ")+" + c + "*" + c + ")";
    }
    private static Expr parse(String value) { return new ExpressionParser().parseExactTerm(value).expression(); }
    private static CheckedSchemaTaskSolver.Task task(String source, int nodes, MoveSearch.Budget budget) {
        return new CheckedSchemaTaskSolver.Task(parse(source), List.of(), CheckedSchemaTaskSolver.Goal.atMostNodes(nodes), budget);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws java.io.IOException {
        if (args.length == 2 && args[0].equals("--restore")) {
            var inventory = TypedLearnedMoveInventory.primitives(TraceStrategyTransferExample.inventory());
            var restored = CheckedLearnedSchemaModel.load(java.nio.file.Files.readString(java.nio.file.Path.of(args[1])),
                TraceStrategyTransferExample.inventory().contentHash());
            var result = inventory.newTaskSolver(restored).solve(task(pair("fresh", "b", "c"), 5, AMPLE));
            require(result.solved() && result.learnedApplications() == 1 && result.primitiveSteps() == 0, "fresh-process reuse");
            require(result.evidence().replayWork() > 0, "fresh-process independent replay");
            System.out.println("PASS: restored task solved in a fresh process without training");
            return;
        }
        if (args.length != 0 && !(args.length == 2 && args[0].equals("--write-model")))
            throw new IllegalArgumentException("expected no arguments, --write-model path or --restore path");
        var contract = new CheckedSchemaTaskSolverContract();
        contract.learnedProcedureReplacesSixPrimitiveStepsWithoutOpeningPrimitiveAlternatives();
        contract.primitiveFallbackSolvesOnlyTheUnfinishedRemainder();
        contract.taskCompletionDoesNotPretendToBeExhaustiveExploration();
        contract.freshInvocationsAndReloadedModelsHaveTheSameResults();
        contract.alreadySatisfiedTasksDoNotOpenProviders();
        contract.finalReplayAndOtherLimitsCanNeverBeHiddenByASolvedFlag();
        contract.missingAssumptionsDoNotAuthorizeLearnedApplications();
        contract.unsupportedAndNearMissTasksRemainExplicitlyUnsolved();
        contract.invalidGoalsAndInputsAreRejectedBeforeExecution();
        contract.printDevelopmentComparison();
        if (args.length == 2) java.nio.file.Files.writeString(java.nio.file.Path.of(args[1]), contract.learned.toCanonicalJson());
        System.out.println("PASS: nine real-model task-completion contracts");
    }
}
