package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The reference is inspected only after the source-only execution has returned its candidates. */
@Timeout(180)
class StructuralAlternativeSearchTest {
    private static final ExpressionParser PARSER = new ExpressionParser();

    @Test void twoHistoricalFormsAppearAmongCheckedAlternativesWithoutSupplyingTheirTargets() {
        var knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(),
            TaskConstructionProfile.STRUCTURAL_EXPANSION_BRIDGE_V1);
        check(knowledge, "y*y+(2*x)*y+x*x", StructuralTaskObjective.PERFECT_SQUARE, 4, 400, "(x+y)^2");
        check(knowledge, "(a-b)*(a+b)", StructuralTaskObjective.EXPANDED_POLYNOMIAL, 6, 600, "a*a-b*b");
    }

    private static void check(CumulativeTaskKnowledge knowledge, String source, StructuralTaskObjective goal,
            int depth, int states, String reference) {
        var task = new CheckedSchemaTaskSolver.Task(parse(source), List.of(), goal.goal(),
            new MoveSearch.Budget(32, depth, 10_000_000, states, 10_000_000));
        var execution = knowledge.explore(task, CumulativeTaskKnowledge.Strategy.BASELINE, 500_000, 32);
        assertTrue(execution.withinBudget(), source);
        var evidence = execution.evidence().orElseThrow();
        Expr expected = parse(reference); // Reference is not part of the task, goal, or search.
        var match = evidence.candidates().stream().filter(candidate -> candidate.state().expression().equals(expected))
            .findFirst().orElseThrow(() -> new AssertionError("missing alternative for " + source));
        assertFalse(match.witness().isEmpty());
        var cursor = task.source();
        for (var step : match.witness()) {
            assertEquals(cursor, step.source().expression());
            assertTrue(step.verification().accepted());
            new ExactPolynomialAnalysis().requireEquivalent(ExpressionFormatter.format(cursor),
                ExpressionFormatter.format(step.target().expression()));
            cursor = step.target().expression();
        }
        assertEquals(expected, cursor);
        System.out.println("HISTORICAL_ALTERNATIVES source=" + source + " candidates=" + evidence.candidates().size()
            + " truncated=" + evidence.truncated() + " total=" + execution.totalWork()
            + " selection=" + evidence.selectionWork() + " replay=" + evidence.replayWork());
    }

    @Test void sourceOutsideCheckedDomainCannotProduceAlternatives() {
        var knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(),
            TaskConstructionProfile.STRUCTURAL_EXPANSION_BRIDGE_V1);
        var task = new CheckedSchemaTaskSolver.Task(parse("x/x"), List.of(), StructuralTaskObjective.EXPANDED_POLYNOMIAL.goal(),
            new MoveSearch.Budget(8, 2, 1000, 32, 1000));
        assertThrows(CheckedSchemaTaskSolver.UnsupportedTaskException.class,
            () -> knowledge.explore(task, CumulativeTaskKnowledge.Strategy.BASELINE, 100, 3));
    }

    private static Expr parse(String text) { return PARSER.parseExactTerm(text).expression(); }
}
