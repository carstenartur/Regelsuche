package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.TypedMoveSearch;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** General expansion is selected without giving a target expression or a rewrite sequence. */
@Timeout(180)
class StructuralExpansionSearchTest {
    private static final MoveSearch.Budget BUDGET = new MoveSearch.Budget(32, 8, 10_000_000, 512, 10_000_000);

    @Test
    void expandsAndCollectsHistoricalInputsWithSourceOnlySearch() {
        var knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(),
            TaskConstructionProfile.STRUCTURAL_EXPANSION_V1);
        for (String source : List.of("(x+y)^2", "(a-b)*(a+b)")) {
            var task = task(source, BUDGET);
            var result = knowledge.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
            assertTrue(result.solved(), source);
            var evidence = result.solution().orElseThrow().evidence();
            assertTrue(evidence.withinBudget());
            assertFalse(evidence.witness().isEmpty());
            new ExactPolynomialAnalysis().requireEquivalent(source, ExpressionFormatter.format(evidence.incumbent().expression()));
            System.out.println("EXPANSION_SOURCE_ONLY source=" + source + " work=" + result.totalWork()
                + " steps=" + evidence.witness().size() + " output=" + ExpressionFormatter.format(evidence.incumbent().expression()));
        }
    }

    @Test
    void expansionGoalRejectsUncollectedAndUnexpandedForms() {
        var profile = TaskConstructionProfile.STRUCTURAL_EXPANSION_V1;
        var goal = StructuralTaskObjective.EXPANDED_POLYNOMIAL;
        var knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(), profile);
        var tiny = new MoveSearch.Budget(4, 1, 1_000_000, 16, 1);
        for (String source : List.of("(x+y)^2", "x*x+x*y+y*x+y*y", "x/(x-x)+x", "x-(y+z)", "-(x+y)",
                "x+0", "0+x", "x-0")) {
            var state = new TypedMoveSearch.State(new ExpressionParser().parseExactTerm(source).expression(),
                0, 0, "", List.of(), Set.of(), 0);
            var score = goal.evaluate(state);
            assertTrue(score.value() > 0 && score.work() > 0, source);
        }
        for (String source : List.of("0", "-x", "-3*x", "x+y+z")) {
            var state = new TypedMoveSearch.State(new ExpressionParser().parseExactTerm(source).expression(),
                0, 0, "", List.of(), Set.of(), 0);
            assertEquals(0, goal.evaluate(state).value(), source);
        }
        assertFalse(knowledge.solve(task("(x+y)^2", tiny), CumulativeTaskKnowledge.Strategy.BASELINE).solved());
        assertFalse(knowledge.solve(task("x*x+x*y+y*x+y*y", tiny), CumulativeTaskKnowledge.Strategy.BASELINE).solved());
        var normal = knowledge.solve(task("x^2+2*x*y+y^2", BUDGET), CumulativeTaskKnowledge.Strategy.BASELINE);
        assertTrue(normal.solved());
        assertTrue(normal.solution().orElseThrow().evidence().witness().isEmpty());
        var restored = CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(), knowledge.toCanonicalJson());
        assertEquals(profile, restored.constructionProfile());
        assertEquals("expanded-v1", goal.key());
    }

    @Test
    void expansionProvidersDoNotConsumeWorkForOtherGoals() {
        var genome = TraceStrategyTransferExample.inventory();
        var existing = CumulativeTaskKnowledge.empty(genome, TaskConstructionProfile.STRUCTURAL_SQUARE_V1);
        var expanded = CumulativeTaskKnowledge.empty(genome, TaskConstructionProfile.STRUCTURAL_EXPANSION_V1);
        for (var task : List.of(
                StructuralConstructionTest.task("x*x+2*x*y+y*y", StructuralTaskObjective.PERFECT_SQUARE),
                StructuralConstructionTest.task("y*y+(2*x)*y+x*x", StructuralTaskObjective.PERFECT_SQUARE),
                StructuralConstructionTest.task("12*x^4", StructuralTaskObjective.FACTORIZATION))) {
            var before = existing.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
            var after = expanded.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
            assertEquals(before.solved(), after.solved());
            assertEquals(before.totalWork(), after.totalWork(), "unneeded expansion providers must not run or consume scheduling work");
            assertEquals(before.solution().orElseThrow().evidence().incumbent().expression(),
                after.solution().orElseThrow().evidence().incumbent().expression());
        }
    }

    @Test
    void optionalBridgeProfileRetainsExpansionRulesForOtherGoals() {
        var profile = TaskConstructionProfile.STRUCTURAL_EXPANSION_BRIDGE_V1;
        var inventory = TypedLearnedMoveInventory.primitives(TraceStrategyTransferExample.inventory());
        var task = StructuralConstructionTest.task("12*x^4", StructuralTaskObjective.FACTORIZATION);
        var solver = profile.attach(inventory.newTaskSolver());
        assertTrue(solver.problem(task).providers().stream().map(de.regelsuche.search.moves.MoveProvider::descriptor)
            .anyMatch(StructuralExpansionRules::isExpansionProvider));
        var restored = CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(),
            CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(), profile).toCanonicalJson());
        assertEquals(profile, restored.constructionProfile());
        var wrappedGoal = new CheckedSchemaTaskSolver.Goal(StructuralTaskObjective.EXPANDED_POLYNOMIAL::evaluate, 0);
        var customTask = new CheckedSchemaTaskSolver.Task(task.source(), List.of(), wrappedGoal, task.budget());
        var standard = TaskConstructionProfile.STRUCTURAL_EXPANSION_V1.attach(inventory.newTaskSolver());
        assertFalse(standard.problem(customTask).providers().stream().map(de.regelsuche.search.moves.MoveProvider::descriptor)
            .anyMatch(StructuralExpansionRules::isExpansionProvider), "a wrapped objective has no declared goal identity");
        assertTrue(solver.problem(customTask).providers().stream().map(de.regelsuche.search.moves.MoveProvider::descriptor)
            .anyMatch(StructuralExpansionRules::isExpansionProvider), "the explicit bridge profile permits custom goals");
    }

    @Test
    void unsupportedSourcesCannotBecomeSuccessByDiscardingTheirDomain() {
        var knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(),
            TaskConstructionProfile.STRUCTURAL_EXPANSION_V1);
        for (String source : List.of("(x-(1/y))+(1/y)", "x/x", "0/(x-x)")) {
            var failure = org.junit.jupiter.api.Assertions.assertThrows(CheckedSchemaTaskSolver.UnsupportedTaskException.class,
                () -> knowledge.solve(task(source, BUDGET), CumulativeTaskKnowledge.Strategy.BASELINE));
            assertTrue(failure.inspectionWork() > 0, source + " must be rejected with paid inspection");
        }
    }

    @Test
    void expandedGoalAloneRejectsInvalidGenomeSteps() {
        var genome = TraceStrategyTransferExample.inventory();
        var invalid = genome.withRewrites(List.of(genome.rewrites().getFirst().withPatterns("(?A+?B)*?C", "?A*?C")));
        var result = CumulativeTaskKnowledge.empty(invalid)
            .solve(task("(x+y)*z", BUDGET), CumulativeTaskKnowledge.Strategy.BASELINE);
        assertFalse(result.solved(), "discarding a summand is not a valid expansion");
        assertTrue(result.solution().orElseThrow().evidence().search().events().stream()
            .anyMatch(event -> event.decision() == MoveSearch.Decision.PROOF_REJECTED
                && event.verification() != null && event.verification().work() > 0));
    }

    @Test
    void directExpandedGoalChargesPolynomialProductsWithoutAConstructionProfile() {
        var result = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory())
            .solve(task("x^32*y^32*z^32*w^32", new MoveSearch.Budget(8, 2, 1_000_000, 32, 160)),
                CumulativeTaskKnowledge.Strategy.BASELINE);
        assertFalse(result.solved(), "an expanded result cannot hide polynomial-product work");
        assertTrue(result.totalWork() > 160);
        var wrapped = new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm("x^32*y^32*z^32*w^32").expression(),
            List.of(), new CheckedSchemaTaskSolver.Goal(StructuralTaskObjective.EXPANDED_POLYNOMIAL::evaluate, 0),
            new MoveSearch.Budget(8, 2, 1_000_000, 32, 160));
        var wrappedResult = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(),
            TaskConstructionProfile.STRUCTURAL_EXPANSION_V1).solve(wrapped, CumulativeTaskKnowledge.Strategy.BASELINE);
        assertFalse(wrappedResult.solved(), "the supplied expansion evaluator must report products through a callback too");
        assertTrue(wrappedResult.totalWork() > 160);
    }

    private static CheckedSchemaTaskSolver.Task task(String source, MoveSearch.Budget budget) {
        return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source).expression(), List.of(),
            StructuralTaskObjective.EXPANDED_POLYNOMIAL.goal(), budget);
    }
}
