package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.transform.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TypedSourceOnlyQualityTest {
    private static final Expr X = new VariableExpr("x");
    private static final Expr INPUT = new BinaryExpr(X, BinaryOperator.ADD, new NumberExpr(0));
    private static final PatternExpr A = PatternExpr.var("A");
    private static final AstRewriteTransport TRANSPORT = new AstRewriteTransport(List.of(
        new PatternRewriteRule("zero", PatternExpr.op(BinaryOperator.ADD, A, PatternExpr.num(0)), A)), 64, 128);
    private static final TypedSourceOnlySearch.Objective OBJECTIVE = state ->
        new TypedSourceOnlySearch.Score(state.expression() instanceof BinaryExpr ? 3 : 1, 1);

    @Test void sufficientVerifiedQualityStopsBeforeOpeningUnneededProviders() {
        var expensive = new AtomicInteger();
        var p = problem(INPUT, 10000, expensive, false, MoveSearch.Mode.FAST);
        var baseline = new TypedSourceOnlySearch().search(p, OBJECTIVE);
        assertTrue(expensive.get() > 0);
        expensive.set(0);
        var result = new TypedSourceOnlySearch().searchUntil(p, OBJECTIVE, 1,
            SearchContinuationContract.DECLARED_STATE_LOCAL);
        assertEquals(0, expensive.get(), "do not search the rest of the frontier after sufficient verified quality");
        assertEquals(X, result.incumbent().expression());
        assertEquals(1, result.witness().size());
        assertFalse(result.search().reached());
        assertEquals("QUALITY_REACHED", result.search().outcome().name());
        assertTrue(result.replayWork() > 0);
        assertTrue(result.withinBudget());
        assertTrue(result.totalWork() < baseline.totalWork());
        System.out.println("QUALITY_DIAGNOSTIC baseline=" + baseline.totalWork() + " online=" + result.totalWork());
    }

    @Test void alreadySufficientInputNeedsNoGenerationAndRetainsTheInput() {
        var expensive = new AtomicInteger();
        var result = new TypedSourceOnlySearch().searchUntil(problem(X, 100, expensive, false, MoveSearch.Mode.FAST),
            OBJECTIVE, 1, SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(0, expensive.get());
        assertEquals(X, result.incumbent().expression());
        assertTrue(result.witness().isEmpty());
        assertTrue(result.totalWork() >= 1);
        assertEquals("QUALITY_REACHED", result.search().outcome().name());
    }

    @Test void expensiveObjectiveCannotDeclareAnOverBudgetSuccess() {
        var result = new TypedSourceOnlySearch().searchUntil(problem(X, 10, new AtomicInteger(), false, MoveSearch.Mode.FAST),
            state -> new TypedSourceOnlySearch.Score(0, 11), 0, SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, result.search().outcome());
        assertFalse(result.withinBudget());
    }

    @Test void rejectedProofCannotSupplyTheQualityWitness() {
        var result = new TypedSourceOnlySearch().searchUntil(problem(INPUT, 10000, new AtomicInteger(), true, MoveSearch.Mode.FAST),
            OBJECTIVE, 1, SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(INPUT, result.incumbent().expression());
        assertTrue(result.witness().isEmpty());
        assertNotEquals("QUALITY_REACHED", result.search().outcome().name());
    }

    @Test void stableTiesRetainTheInputAndAnUnreachableThresholdIsNotSuccess() {
        var result = new TypedSourceOnlySearch().searchUntil(problem(INPUT, 10000, new AtomicInteger(), false, MoveSearch.Mode.FAST),
            state -> new TypedSourceOnlySearch.Score(5, 1), 4, SearchContinuationContract.DECLARED_STATE_LOCAL);
        assertEquals(INPUT, result.incumbent().expression());
        assertTrue(result.witness().isEmpty());
        assertNotEquals("QUALITY_REACHED", result.search().outcome().name());
    }

    @Test void finalIndependentReplayStillCountsAgainstTheTotalBudget() {
        var baseline = new TypedSourceOnlySearch().searchUntil(problem(INPUT, 10000, new AtomicInteger(), false, MoveSearch.Mode.FAST),
            OBJECTIVE, 1, SearchContinuationContract.PATH_SENSITIVE);
        var tight = new TypedSourceOnlySearch().searchUntil(problem(INPUT, baseline.totalWork() - 1, new AtomicInteger(), false, MoveSearch.Mode.FAST),
            OBJECTIVE, 1, SearchContinuationContract.PATH_SENSITIVE);
        assertFalse(tight.withinBudget());
        assertEquals(baseline.incumbent().expression(), tight.incumbent().expression());
    }

    @Test void qualityStoppingCannotSilentlyAlterTheCompleteReferenceMode() {
        assertThrows(IllegalArgumentException.class, () -> new TypedSourceOnlySearch().searchUntil(
            problem(INPUT, 10000, new AtomicInteger(), false, MoveSearch.Mode.COMPLETE_BOUNDED_REFERENCE),
            OBJECTIVE, 1, SearchContinuationContract.PATH_SENSITIVE));
    }

    private static TypedMoveSearch.Problem problem(Expr source, long budget, AtomicInteger expensive,
            boolean reject, MoveSearch.Mode mode) {
        var descriptor = new MoveProvider.Descriptor("zero", "zero", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "fixture");
        var costly = new TypedMoveSearch.TypedProvider() {
            @Override public Descriptor descriptor() {
                return new Descriptor("expensive", "expensive", SearchMove.SourceKind.SOLVER,
                    SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "fixture");
            }
            @Override public Batch candidates(MoveState state, MoveContext context) {
                expensive.incrementAndGet();
                return new Batch(List.of(), TransformationWorkMetrics.flatEngine(40), false);
            }
        };
        return new TypedMoveSearch.Problem(source, TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION),
            List.of(TypedMoveSearch.primitiveProvider(descriptor, TRANSPORT), costly), TypedMoveSearch.Policy.INVENTORY_ORDER,
            reject ? (s, m, c) -> new MoveVerifier.Verification(false, 1, List.of(), "FORGED") : TypedMoveSearch.primitiveReplay(TRANSPORT),
            state -> 0, mode, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(8, 8, 0, 100, budget));
    }
}
