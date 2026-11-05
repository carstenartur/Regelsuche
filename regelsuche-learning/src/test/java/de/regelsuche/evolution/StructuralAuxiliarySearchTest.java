package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.SearchContinuationContract;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructuralAuxiliarySearchTest {
    private static final MoveSearch.Budget BUDGET = new MoveSearch.Budget(8, 1, 1_000_000, 64, 1_000_000);

    @Test
    void sourceOnlyFrontierConstructsAndChecksSourceDerivedAuxiliaries() {
        assertCrossTermReached("x^2+z+y^2", "2*x*y", false);
        assertCrossTermReached("(a+1)^2+t+(3*b)^2", "2*(a+1)*(3*b)", false);
        assertCrossTermReached("x^2+z+y^2", "2*x*y", true);
    }

    @Test
    void sourceOnlySearchCombinesAnAuxiliaryWithExistingSquareRecognition() {
        var profile = TaskConstructionProfile.STRUCTURAL_AUX_V1;
        var inventory = TypedLearnedMoveInventory.primitives(TraceStrategyTransferExample.inventory());
        var solver = profile.attach(inventory.newTaskSolver());
        var task = new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm("x^2+y^2").expression(),
            List.of(), StructuralTaskObjective.FACTORIZATION.goal(), new MoveSearch.Budget(8, 2, 1_000_000, 128, 2_000_000));
        var original = solver.problem(task);
        var providers = original.providers().stream().filter(provider ->
            provider.descriptor().id().endsWith(":AUXILIARY_COMPLEMENT")
                || provider.descriptor().id().endsWith(":RECOGNIZE_SQUARE")).toList();
        assertEquals(2, providers.size(), "the search can use only the two independent generic operations");
        var problem = new TypedMoveSearch.Problem(original.source(), original.context(), providers,
            original.policy(), original.verifier(), original.stateScore(), original.mode(),
            original.scheduling(), original.budget(), original.stateValue());
        var search = new TypedMoveSearch().search(problem);
        var exact = new ExactPolynomialAnalysis();
        boolean found = search.events().stream()
            .filter(event -> event.decision() == MoveSearch.Decision.ENQUEUED)
            .filter(event -> event.source().previousRule().endsWith(":AUXILIARY_COMPLEMENT"))
            .filter(event -> event.move().ruleId().endsWith(":RECOGNIZE_SQUARE"))
            .filter(event -> event.verification() != null && event.verification().accepted())
            .map(event -> event.target().expression())
            .filter(BinaryExpr.class::isInstance).map(BinaryExpr.class::cast)
            .filter(expression -> expression.operator() == BinaryOperator.SUB)
            .anyMatch(expression -> {
                exact.requireEquivalent("x^2+y^2", ExpressionFormatter.format(expression));
                return StructuralTaskObjective.isSquare(expression.left())
                    && exact.normalForm(ExpressionFormatter.format(expression.right())).equals(exact.normalForm("2*x*y"));
            });
        assertTrue(found, "the frontier must find and verify the two-step preparation without a target expression");
        assertTrue(search.accountingComplete());
    }

    @Test
    void discoversHistoricalFactorizationByCombiningGenericSteps() {
        discoversFactorization("x^4+4*y^4");
        discoversFactorization("16*x^4+4*y^4");
    }

    private static void discoversFactorization(String source) {
        var profile = TaskConstructionProfile.STRUCTURAL_AUX_V1;
        var inventory = TypedLearnedMoveInventory.primitives(TraceStrategyTransferExample.inventory());
        var solver = profile.attach(inventory.newTaskSolver());
        var budget = new MoveSearch.Budget(32, 7, 10_000_000, 512, 10_000_000);
        var task = new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source).expression(),
            List.of(), StructuralTaskObjective.FACTORIZATION.goal(), budget);
        var original = solver.problem(task);
        var providers = original.providers().stream().filter(provider -> {
            String id = provider.descriptor().id();
            return id.endsWith(":EXPOSE_SQUARE") || id.endsWith(":AUXILIARY_COMPLEMENT")
                || id.endsWith(":RECOGNIZE_SQUARE") || id.endsWith(":FACTOR_DIFFERENCE");
        }).toList();
        assertEquals(4, providers.size());
        var problem = new TypedMoveSearch.Problem(original.source(), original.context(), providers,
            original.policy(), original.verifier(), original.stateScore(), MoveSearch.Mode.FAST,
            original.scheduling(), original.budget(), original.stateValue());
        var withoutAuxiliary = new TypedMoveSearch.Problem(original.source(), original.context(), providers.stream()
            .filter(provider -> !provider.descriptor().id().endsWith(":AUXILIARY_COMPLEMENT")).toList(),
            original.policy(), original.verifier(), original.stateScore(), MoveSearch.Mode.FAST,
            original.scheduling(), original.budget(), original.stateValue());
        var control = new TypedSourceOnlySearch().searchUntil(withoutAuxiliary, StructuralTaskObjective.FACTORIZATION::evaluateWithProductWork,
            0, SearchContinuationContract.PATH_SENSITIVE);
        assertTrue(control.withinBudget());
        assertFalse(control.search().outcome() == MoveSearch.Outcome.QUALITY_REACHED,
            "without auxiliary generation these same restricted primitives do not reach a factorization");
        var result = new TypedSourceOnlySearch().searchUntil(problem, StructuralTaskObjective.FACTORIZATION::evaluateWithProductWork,
            0, SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(MoveSearch.Outcome.QUALITY_REACHED, result.search().outcome(),
            "the generic source-only frontier should find a nontrivial factorization");
        assertTrue(result.withinBudget());
        assertTrue(result.witness().stream().anyMatch(step -> step.move().ruleId().endsWith(":AUXILIARY_COMPLEMENT")));
        assertTrue(result.witness().stream().anyMatch(step -> step.move().ruleId().endsWith(":RECOGNIZE_SQUARE")));
        new ExactPolynomialAnalysis().requireEquivalent(source, ExpressionFormatter.format(result.incumbent().expression()));
        System.out.println("AUXILIARY_HISTORICAL work=" + result.totalWork() + " steps=" + result.witness().size()
            + " controlWork=" + control.totalWork() + " controlOutcome=" + control.search().outcome()
            + " output=" + ExpressionFormatter.format(result.incumbent().expression()));
    }

    private static void assertCrossTermReached(String source, String expectedCrossTerm, boolean incremental) {
        var profile = TaskConstructionProfile.STRUCTURAL_AUX_V1;
        var genome = TraceStrategyTransferExample.inventory();
        var inventory = TypedLearnedMoveInventory.primitives(genome);
        var solver = profile.attach(incremental
            ? inventory.newTaskSolver(CheckedLearnedSchemaModel.empty(genome.contentHash()))
            : inventory.newTaskSolver());
        var task = new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source).expression(),
            List.of(), StructuralTaskObjective.FACTORIZATION.goal(), BUDGET);
        var search = new TypedMoveSearch().search(solver.problem(task));
        var exact = new ExactPolynomialAnalysis();
        var expected = exact.normalForm(expectedCrossTerm);
        boolean reached = search.events().stream()
            .filter(event -> event.decision() == MoveSearch.Decision.ENQUEUED)
            .filter(event -> event.move().ruleId().endsWith(":AUXILIARY_COMPLEMENT"))
            .filter(event -> event.verification() != null && event.verification().accepted())
            .map(TypedMoveSearch.Event::target)
            .map(TypedMoveSearch.State::expression)
            .filter(BinaryExpr.class::isInstance).map(BinaryExpr.class::cast)
            .filter(expression -> expression.operator() == BinaryOperator.SUB)
            .anyMatch(expression -> {
                exact.requireEquivalent(source, ExpressionFormatter.format(expression));
                return exact.normalForm(ExpressionFormatter.format(expression.right())).equals(expected);
            });
        assertTrue(reached, "source-only frontier must retain a verified, source-derived cross term");
        assertTrue(search.metrics().totalWork() > 0);
        assertTrue(search.accountingComplete());
        assertTrue(search.metrics().totalWork() <= BUDGET.totalWork());
    }

    @Test
    void completeTaskSolverStillProducesVerifiedFactorization() {
        var genome = TraceStrategyTransferExample.inventory();
        var profile = TaskConstructionProfile.STRUCTURAL_AUX_V1;
        var task = new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm("x^4+4*y^4").expression(),
            List.of(), StructuralTaskObjective.FACTORIZATION.goal(), new MoveSearch.Budget(32, 7, 10_000_000, 512, 10_000_000));
        var execution = CumulativeTaskKnowledge.empty(genome, profile).solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
        var v2 = CumulativeTaskKnowledge.empty(genome, TaskConstructionProfile.STRUCTURAL_V2)
            .solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
        assertTrue(execution.solved(), "the public task solver must not lose the baseline result");
        assertTrue(v2.solved(), "the previous capability profile remains a solved control");
        new ExactPolynomialAnalysis().requireEquivalent("x^4+4*y^4",
            ExpressionFormatter.format(execution.solution().orElseThrow().evidence().incumbent().expression()));
        System.out.println("AUXILIARY_COMPLETE_SOLVER v2Work=" + v2.totalWork() + " auxiliaryWork=" + execution.totalWork());
    }

    @Test
    void unsuccessfulControlIsRetainedForBothProfiles() {
        var genome = TraceStrategyTransferExample.inventory();
        var task = new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm("u^2+2").expression(),
            List.of(), StructuralTaskObjective.FACTORIZATION.goal(), new MoveSearch.Budget(8, 2, 1_000_000, 32, 200_000));
        var v2 = CumulativeTaskKnowledge.empty(genome, TaskConstructionProfile.STRUCTURAL_V2)
            .solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
        var auxiliary = CumulativeTaskKnowledge.empty(genome, TaskConstructionProfile.STRUCTURAL_AUX_V1)
            .solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
        assertFalse(v2.solved());
        assertFalse(auxiliary.solved());
        System.out.println("AUXILIARY_UNSOLVED v2Work=" + v2.totalWork() + " auxiliaryWork=" + auxiliary.totalWork());
    }

    @Test
    void completeTaskSolverFindsNontrivialFactorsInsideAProduct() {
        var genome = TraceStrategyTransferExample.inventory();
        var task = new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm("12*x^4").expression(),
            List.of(), StructuralTaskObjective.FACTORIZATION.goal(), new MoveSearch.Budget(8, 3, 1_000_000, 64, 2_000_000));
        var control = CumulativeTaskKnowledge.empty(genome, TaskConstructionProfile.STRUCTURAL_V1)
            .solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
        var v2Control = CumulativeTaskKnowledge.empty(genome, TaskConstructionProfile.STRUCTURAL_V2)
            .solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
        var profile = TaskConstructionProfile.STRUCTURAL_AUX_V1;
        var result = CumulativeTaskKnowledge.empty(genome, profile).solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
        assertFalse(control.solved(), "the earlier bounded construction does not split this product root");
        assertFalse(v2Control.solved(), "the existing V2 profile does not split this product root");
        assertTrue(result.solved(), "source-derived factor splitting reaches a nontrivial product");
        assertTrue(result.solution().orElseThrow().evidence().witness().stream()
            .anyMatch(step -> step.move().ruleId().endsWith(":AUXILIARY_FACTOR")));
        new ExactPolynomialAnalysis().requireEquivalent("12*x^4",
            ExpressionFormatter.format(result.solution().orElseThrow().evidence().incumbent().expression()));
        System.out.println("AUXILIARY_FACTOR v1Work=" + control.totalWork() + " v2Work=" + v2Control.totalWork()
            + " auxiliaryWork=" + result.totalWork() + " output="
            + ExpressionFormatter.format(result.solution().orElseThrow().evidence().incumbent().expression()));
    }

    @Test
    void learnedAuxiliaryConstructionPaysForItsDevelopmentStream() {
        var genome = TraceStrategyTransferExample.inventory();
        var initial = CumulativeTaskKnowledge.empty(genome, TaskConstructionProfile.STRUCTURAL_AUX_V1);
        var objective = StructuralTaskObjective.FACTORIZATION;
        var lessons = List.of(StructuralConstructionTest.task("12*x^4", objective),
            StructuralConstructionTest.task("20*y^4", objective));
        var validation = List.of(StructuralConstructionTest.task("28*z^4", objective));
        var update = initial.learnBatch(lessons, validation, 5_000_000);
        assertTrue(update.promoted(), "only independently validated useful knowledge is promoted");
        assertEquals(2, update.training().size());
        for (var lesson : update.training()) {
            assertTrue(lesson.solved());
            assertTrue(lesson.solution().orElseThrow().evidence().witness().stream()
                .anyMatch(step -> step.move().ruleId().endsWith(":AUXILIARY_FACTOR")),
                "the lesson must actually use the new source-derived factor construction");
        }
        var restored = CumulativeTaskKnowledge.restore(genome, update.next().toCanonicalJson());
        assertEquals(TaskConstructionProfile.STRUCTURAL_AUX_V1, restored.constructionProfile());

        long baselineWork = initial.sessionSetupWork();
        long learnedWork = update.work().total() + restored.sessionSetupWork();
        var exact = new ExactPolynomialAnalysis();
        for (int coefficient : new int[] {36, 44, 52, 60, 68, 76, 84, 92}) {
            String source = coefficient + "*k^4";
            var task = StructuralConstructionTest.task(source, objective);
            var baseline = initial.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
            var learned = restored.solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            assertTrue(baseline.solved() && learned.solved(), "both arms must reach the same nontrivial goal");
            assertTrue(learned.solution().orElseThrow().learnedApplications() > 0,
                "the restored schema must actually be applied");
            assertTrue(baseline.totalWork() <= task.budget().totalWork());
            assertTrue(learned.totalWork() <= task.budget().totalWork());
            exact.requireEquivalent(source, ExpressionFormatter.format(
                baseline.solution().orElseThrow().evidence().incumbent().expression()));
            exact.requireEquivalent(source, ExpressionFormatter.format(
                learned.solution().orElseThrow().evidence().incumbent().expression()));
            baselineWork += baseline.totalWork(); learnedWork += learned.totalWork();
        }
        for (String source : List.of("x*y", "u^2+2")) {
            var task = new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source).expression(),
                List.of(), objective.goal(), new MoveSearch.Budget(8, 2, 1_000_000, 32, 200_000));
            var baseline = initial.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
            var learned = restored.solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            assertEquals(source.equals("x*y"), baseline.solved());
            assertEquals(baseline.solved(), learned.solved(), "already solved and failed controls remain in both arms");
            assertTrue(baseline.solution().orElseThrow().evidence().withinBudget());
            assertTrue(learned.solution().orElseThrow().evidence().withinBudget());
            assertEquals(baseline.solution().orElseThrow().evidence().outputScore(),
                learned.solution().orElseThrow().evidence().outputScore(), "control quality must remain unchanged");
            baselineWork += baseline.totalWork(); learnedWork += learned.totalWork();
        }
        assertTrue(learnedWork < baselineWork, "the stream must repay acquisition, validation and restore");
        System.out.println("AUXILIARY_LEARNING_STREAM baseline=" + baselineWork + " learned=" + learnedWork
            + " acquisition=" + update.work().total() + " restore=" + restored.sessionSetupWork());
    }

    @Test
    void earlierProfileExcludesAuxiliariesAndNewProfileSurvivesRestore() {
        var genome = TraceStrategyTransferExample.inventory();
        var oldSolver = TaskConstructionProfile.STRUCTURAL_V1
            .attach(TypedLearnedMoveInventory.primitives(genome).newTaskSolver());
        var task = StructuralConstructionTest.task("x^2+z+y^2", StructuralTaskObjective.FACTORIZATION);
        assertFalse(oldSolver.problem(task).providers().stream()
            .anyMatch(provider -> provider.descriptor().id().endsWith(":AUXILIARY_COMPLEMENT")));
        var v2Solver = TaskConstructionProfile.STRUCTURAL_V2
            .attach(TypedLearnedMoveInventory.primitives(genome).newTaskSolver());
        assertFalse(v2Solver.problem(task).providers().stream()
            .anyMatch(provider -> provider.descriptor().id().endsWith(":AUXILIARY_COMPLEMENT")
                || provider.descriptor().id().endsWith(":AUXILIARY_FACTOR")
                || provider.descriptor().id().endsWith(":RECOGNIZE_SQUARE")));
        var profile = TaskConstructionProfile.STRUCTURAL_AUX_V1;
        var initial = CumulativeTaskKnowledge.empty(genome, profile);
        var restored = CumulativeTaskKnowledge.restore(genome, initial.toCanonicalJson());
        assertEquals(profile, restored.constructionProfile());
        assertEquals(initial.id(), restored.id());
        var factorTask = StructuralConstructionTest.task("12*x^4", StructuralTaskObjective.FACTORIZATION);
        assertTrue(restored.solve(factorTask, CumulativeTaskKnowledge.Strategy.BASELINE).solved(),
            "restoring the profile must preserve the usable factor construction");
    }
}
