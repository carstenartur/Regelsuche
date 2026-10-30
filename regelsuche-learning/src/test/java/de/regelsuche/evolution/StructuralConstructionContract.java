package de.regelsuche.evolution;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.TypedMoveSearch;
import java.util.List;

/** Real constructor, frontier, independent replay and existing learner; no substituted solver. */
public final class StructuralConstructionContract {
    static final MoveSearch.Budget BUDGET = new MoveSearch.Budget(24, 12, 1_000_000, 128, 2_000_000);
    static CheckedSchemaTaskSolver.Task task(String input, StructuralTaskObjective objective) {
        return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(input).expression(), List.of(), objective.goal(), BUDGET);
    }
    public void structuralGoalsRejectTrivialAndFalseShapes() {
        var bare = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
        require(!bare.solve(task("1*(x^4+4*y^4)", StructuralTaskObjective.FACTORIZATION), CumulativeTaskKnowledge.Strategy.BASELINE).solved(), "unit factors are not a solution");
        var zero = new TypedMoveSearch.State(new ExpressionParser().parseExactTerm("(x-x)*y").expression(), 0, 0, "", List.of(), java.util.Set.of(), 0);
        require(StructuralTaskObjective.FACTORIZATION.evaluate(zero).value() > 0, "semantic zero cannot be a nonconstant factor");
        require(!bare.solve(task("x^2+0^2", StructuralTaskObjective.SUM_OF_TWO_SQUARES), CumulativeTaskKnowledge.Strategy.BASELINE).solved(), "zero square padding is not success");
    }
    public void connectsHistoricalConstructionsToTheSameTaskSolver() {
        var bare = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
        var prepared = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(), TaskConstructionProfile.STRUCTURAL_V1);
        var s = task("x^4+4*y^4", StructuralTaskObjective.FACTORIZATION);
        require(!bare.solve(s, CumulativeTaskKnowledge.Strategy.BASELINE).solved(), "unchanged restricted control");
        var sophie = prepared.solve(s, CumulativeTaskKnowledge.Strategy.BASELINE);
        require(sophie.solved(), "generic preparation must make the factorization reachable in the task solver");
        require(sophie.solution().orElseThrow().evidence().replayWork() > 0, "independent final checking is paid");
        var brahmagupta = prepared.solve(task("(a^2+b^2)*(c^2+d^2)", StructuralTaskObjective.SUM_OF_TWO_SQUARES), CumulativeTaskKnowledge.Strategy.BASELINE);
        require(brahmagupta.solved(), "existing residual strategy is usable through the same frontier");
        System.out.println("STRUCTURAL_SOPHIE work=" + sophie.totalWork() + " output=" + sophie.solution().orElseThrow().evidence().incumbent().expression());
        System.out.println("STRUCTURAL_BRAHMAGUPTA work=" + brahmagupta.totalWork() + " output=" + brahmagupta.solution().orElseThrow().evidence().incumbent().expression());
    }
    public void learnsStructuralSolutionsAndRestoresThem() {
        var genome = TraceStrategyTransferExample.inventory();
        var start = CumulativeTaskKnowledge.empty(genome, TaskConstructionProfile.STRUCTURAL_V1);
        var lessons = List.of(task("x^4+4*y^4", StructuralTaskObjective.FACTORIZATION),
            task("a^4+4*b^4", StructuralTaskObjective.FACTORIZATION));
        var validation = List.of(task("(p*q)^4+4*r^4", StructuralTaskObjective.FACTORIZATION));
        var update = start.learnBatch(lessons, validation, 5_000_000);
        System.out.println("FACTOR_LEARNING reason=" + update.reason() + " schemas=" + update.next().schemaCount() + " costs=" + update.work());
        System.out.println("ATTEMPTS=" + update.attempts());
        require(update.promoted(), "a structural goal must allow a useful checked schema to be selected");
        var successor = update.next();
        var restored = CumulativeTaskKnowledge.restore(genome, successor.toCanonicalJson());
        require(restored.id().equals(successor.id()), "canonical profile-bound restore");
        require(restored.constructionProfile() == TaskConstructionProfile.STRUCTURAL_V1, "restore the exact capability profile");
        var transfer = task("(u*v)^4+4*w^4", StructuralTaskObjective.FACTORIZATION);
        var before = start.solve(transfer, CumulativeTaskKnowledge.Strategy.BASELINE);
        var after = restored.solve(transfer, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        require(after.solved() && after.solution().orElseThrow().learnedApplications() > 0, "automatic structural routing actually uses learned mathematics");
        System.out.println("TRANSFER_DIAGNOSTIC beforeSolved=" + before.solved() + " before=" + before.totalWork()
            + " outcome=" + before.solution().orElseThrow().evidence().search().outcome() + " after=" + after.totalWork());
        require(after.totalWork() < before.totalWork(), "new input is solved with less work against the same construction baseline");
        var known = task("m^4+4*n^4", StructuralTaskObjective.FACTORIZATION);
        var knownBase = start.solve(known, CumulativeTaskKnowledge.Strategy.BASELINE);
        var knownLearned = restored.solve(known, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        require(knownBase.solved() && knownLearned.solved() && knownLearned.totalWork() < knownBase.totalWork(),
            "equal-quality comparison on a previously solvable task");
        System.out.println("FACTOR_EQUAL_QUALITY baseline=" + knownBase.totalWork() + " learned=" + knownLearned.totalWork());
        System.out.println("FACTOR_TRANSFER baseline=" + before.totalWork() + " learned=" + after.totalWork()
            + " acquisition=" + successor.acquisitionWork() + " restore=" + restored.sessionSetupWork());
    }

    public void learnsVerifiedPartialProgressWithoutInventingCompletion() {
        var inventory = TypedLearnedMoveInventory.primitives(TraceStrategyTransferExample.inventory());
        var solver = TaskConstructionProfile.STRUCTURAL_V1.attach(inventory.newTaskSolver());
        var limited = new MoveSearch.Budget(24, 2, 1_000_000, 128, 2_000_000);
        var first = task("x^4+4*y^4", StructuralTaskObjective.FACTORIZATION);
        var second = task("p^4+4*q^4", StructuralTaskObjective.FACTORIZATION);
        var a = solver.solve(new CheckedSchemaTaskSolver.Task(first.source(), List.of(), first.goal(), limited));
        var b = solver.solve(new CheckedSchemaTaskSolver.Task(second.source(), List.of(), second.goal(), limited));
        System.out.println("PARTIAL solved=" + a.solved() + " score=" + a.evidence().inputScore() + "->" + a.evidence().outputScore()
            + " path=" + a.evidence().witness().size() + " work=" + a.evidence().totalWork());
        require(!a.solved() && a.evidence().withinBudget() && a.evidence().improved(), "an unfinished task has independently replayed useful progress");
        var empty = CheckedLearnedSchemaModel.empty(TraceStrategyTransferExample.inventory().contentHash());
        var legacy = CheckedLearnedSchemaModel.extendFromSolutions(empty, List.of(a, b));
        var partial = CheckedLearnedSchemaModel.extendFromSolutions(empty, List.of(a, b), true);
        require(legacy.schemas().isEmpty(), "legacy completed-only learner remains unchanged");
        require(!partial.schemas().isEmpty(), "new learner keeps a checked useful prefix without a false solved flag");
        System.out.println("PARTIAL_SCHEMAS=" + partial.schemas().size() + " formation=" + partial.formationWork());
    }

    public void learnsResidualCompositionAndPreservesBudgetAndProfile() throws Exception {
        var genome = TraceStrategyTransferExample.inventory();
        var initial = CumulativeTaskKnowledge.empty(genome, TaskConstructionProfile.STRUCTURAL_V1);
        var lessons = List.of(task("(a^2+b^2)*(c^2+d^2)", StructuralTaskObjective.SUM_OF_TWO_SQUARES),
            task("(p^2+q^2)*(r^2+s^2)", StructuralTaskObjective.SUM_OF_TWO_SQUARES));
        var validation = List.of(task("((u+1)^2+v^2)*(w^2+z^2)", StructuralTaskObjective.SUM_OF_TWO_SQUARES));
        var update = initial.learnBatch(lessons, validation, 5_000_000);
        System.out.println("RESIDUAL_LEARNING=" + update.reason() + " schemas=" + update.next().schemaCount() + " costs=" + update.work());
        require(update.promoted(), "a checked construction result can become a reusable theorem");
        var file = update.next().save(java.nio.file.Files.createTempDirectory("structural-knowledge-"));
        var restored = CumulativeTaskKnowledge.load(genome, file);
        var query = task("(m^2+n^2)*(r^2+s^2)", StructuralTaskObjective.SUM_OF_TWO_SQUARES);
        var before = initial.solve(query, CumulativeTaskKnowledge.Strategy.BASELINE);
        var after = restored.solve(query, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        require(before.solved() && after.solved() && after.solution().orElseThrow().learnedApplications() > 0, "same historical quality from a checked learned rule");
        require(after.totalWork() < before.totalWork(), "learning avoids the paid residual enumeration on later requests");
        var tiny = new CheckedSchemaTaskSolver.Task(query.source(), List.of(), query.goal(), new MoveSearch.Budget(24,12,1_000_000,128,1));
        require(!restored.solve(tiny, CumulativeTaskKnowledge.Strategy.AUTOMATIC).solved(), "classification and final replay share the query budget");
        boolean rejected = false;
        try { CumulativeTaskKnowledge.restore(genome, restored.toCanonicalJson().replace("STRUCTURAL_V1", "UNKNOWN_PROFILE")); }
        catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "loaded capabilities are a closed versioned set");
        System.out.println("RESIDUAL_TRANSFER baseline=" + before.totalWork() + " learned=" + after.totalWork()
            + " acquisition=" + update.next().acquisitionWork() + " restore=" + restored.sessionSetupWork());
        System.out.println("STRUCTURAL_SNAPSHOT=" + file);
    }

    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var test = new StructuralConstructionContract();
        test.structuralGoalsRejectTrivialAndFalseShapes(); test.connectsHistoricalConstructionsToTheSameTaskSolver();
        test.learnsStructuralSolutionsAndRestoresThem();
        test.learnsVerifiedPartialProgressWithoutInventingCompletion();
        test.learnsResidualCompositionAndPreservesBudgetAndProfile();
        System.out.println("PASS structural task integration");
    }
}
