package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.transform.AstRewriteTransport;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Budget regressions for the online incumbent and the separately retained success witness. */
class TypedSourceOnlyBudgetBoundaryTest {
    private static final Expr X = new VariableExpr("x");
    private static final Expr INPUT = new BinaryExpr(X, BinaryOperator.ADD, new NumberExpr(0));
    private static final PatternExpr A = PatternExpr.var("A");
    private static final AstRewriteTransport TRANSPORT = new AstRewriteTransport(List.of(
        new PatternRewriteRule("zero", PatternExpr.op(BinaryOperator.ADD, A, PatternExpr.num(0)), A)), 64, 128);
    private static final TypedSourceOnlySearch.Objective OBJECTIVE = state ->
        new TypedSourceOnlySearch.Score(state.expression() instanceof BinaryExpr ? 3 : 1, 1);

    @Test void overBudgetChildObjectiveRetainsReplayableIncumbentButNoSuccessWitness() {
        for (var contract : SearchContinuationContract.values()) {
            var result = new TypedSourceOnlySearch().searchUntil(problem(100), state ->
                new TypedSourceOnlySearch.Score(state.expression() instanceof BinaryExpr ? 3 : 1,
                    state.expression() instanceof BinaryExpr ? 1 : 100), 1, contract);
            assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, result.search().outcome());
            assertFalse(result.withinBudget());
            assertTrue(result.search().witness().isEmpty());
            assertEquals(X, result.incumbent().expression());
            assertEquals(1, result.witness().size());
            assertTrue(result.replayWork() > 0);
            assertVerifiedLineage(result);
        }
    }

    @Test void witnessMaterializationOverrunDoesNotEraseTheIncumbentLineage() {
        for (var contract : SearchContinuationContract.values()) {
            var solver = new TypedSourceOnlySearch();
            var ample = solver.searchUntil(problem(10000), OBJECTIVE, 1, contract);
            long cap = ample.search().metrics().totalWork() - 1;
            var tight = solver.searchUntil(problem(cap), OBJECTIVE, 1, contract);
            assertEquals(MoveSearch.Outcome.QUALITY_REACHED, ample.search().outcome());
            assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, tight.search().outcome());
            assertFalse(tight.withinBudget());
            assertTrue(tight.search().witness().isEmpty());
            assertEquals(ample.witness(), tight.witness());
            assertEquals(ample.search().metrics(), tight.search().metrics());
            assertVerifiedLineage(tight);
        }
    }

    @Test void everyTightBudgetRetainsAContinuousVerifiedIncumbentPath() {
        for (var contract : SearchContinuationContract.values()) {
            for (long cap = 1; cap <= 100; cap++) {
                var result = new TypedSourceOnlySearch().searchUntil(problem(cap), OBJECTIVE, 1, contract);
                assertVerifiedLineage(result);
                assertFalse(result.search().reached());
                if (result.search().metrics().totalWork() > cap) {
                    assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, result.search().outcome());
                    assertTrue(result.search().witness().isEmpty());
                }
                if (result.search().outcome() == MoveSearch.Outcome.QUALITY_REACHED && result.withinBudget()) {
                    assertEquals(X, result.incumbent().expression());
                }
            }
        }
    }

    @Test void repeatedSearchesOwnSeparateFrontiersAndLedgers() {
        var solver = new TypedSourceOnlySearch();
        var problem = problem(10000);
        for (var contract : SearchContinuationContract.values()) {
            var first = solver.searchUntil(problem, OBJECTIVE, 1, contract);
            var second = solver.searchUntil(problem, OBJECTIVE, 1, contract);
            assertEquals(first, second);
            assertVerifiedLineage(second);
        }
    }

    private static void assertVerifiedLineage(TypedSourceOnlySearch.Result result) {
        var cursor = result.search().initialState();
        assertEquals(INPUT, cursor.expression());
        for (var step : result.witness()) {
            assertEquals(cursor, step.source());
            assertTrue(step.verification().accepted());
            cursor = step.target();
        }
        assertEquals(result.incumbent(), cursor);
    }

    private static TypedMoveSearch.Problem problem(long budget) {
        var descriptor = new MoveProvider.Descriptor("zero", "zero", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "fixture");
        return new TypedMoveSearch.Problem(INPUT,
            TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION),
            List.of(TypedMoveSearch.primitiveProvider(descriptor, TRANSPORT)),
            TypedMoveSearch.Policy.INVENTORY_ORDER, TypedMoveSearch.primitiveReplay(TRANSPORT), state -> 0,
            MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(8, 8, 0, 100, budget));
    }
}
