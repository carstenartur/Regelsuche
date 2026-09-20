package de.regelsuche.search.moves;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.transform.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TypedSourceOnlySearchTest {
    private static final long BUDGET = 10_000;
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static final MoveProvider.Descriptor DESCRIPTOR = new MoveProvider.Descriptor("zero", "zero",
        SearchMove.SourceKind.PRIMITIVE, SearchMove.ProofStrength.REPLAYABLE, List.of(),
        SearchMove.ValueEvidence.UNKNOWN, "zero-v1");
    private static final AstRewriteTransport TRANSPORT = new PreparedAstRewriteTransformationEngine(List.of(
        new PatternRewriteRule("zero", PatternExpr.op(ADD, PatternExpr.var("A"), PatternExpr.num(0)), PatternExpr.var("A"))), 64, 128).astTransport();

    @Test void sourceOnlyExplorationSelectsAReplayedPathAndPreservesTypedLeaves() {
        Expr leaf = new FunctionExpr("f", List.of(VariableExpr.scoped(new SymbolId(new UUID(0, 22), 3)),
            NumberExpr.exact("1/3"), new BinaryExpr(new VariableExpr("a"), MUL,
                new BinaryExpr(new VariableExpr("b"), MUL, new VariableExpr("c")))));
        Expr source = addZero(addZero(leaf));
        var result = assertDoesNotThrow(() -> run(source, BUDGET));
        assertEquals(leaf, result.incumbent().expression());
        assertEquals(2, result.witness().size());
        assertTrue(result.improved());
        assertTrue(result.withinBudget());
        assertFalse(result.search().reached());
        assertEquals(-1, result.search().metrics().firstHitDepth());
        assertEquals(source, result.witness().getFirst().source().expression());
        assertEquals(leaf, result.witness().getLast().target().expression());
        assertTrue(result.selectionWork() > 0);
        assertTrue(result.replayWork() > 0);
        assertEquals(result.search().metrics().totalWork() + result.selectionWork() + result.replayWork(), result.totalWork());
    }

    @Test void unchangedInputRemainsAnExplicitCandidate() {
        Expr source = new VariableExpr("x");
        var result = assertDoesNotThrow(() -> run(source, BUDGET));
        assertEquals(source, result.incumbent().expression());
        assertFalse(result.improved());
        assertTrue(result.witness().isEmpty());
        assertEquals(0, result.replayWork());
        assertTrue(result.withinBudget());
    }

    @Test void selectionAndReplayCannotHideAnOverrun() {
        Expr source = addZero(new VariableExpr("x"));
        var ample = assertDoesNotThrow(() -> run(source, BUDGET));
        var limited = run(source, ample.totalWork() - 1);
        assertEquals(ample.incumbent(), limited.incumbent());
        assertEquals(ample.totalWork(), limited.totalWork());
        assertTrue(limited.search().metrics().totalWork() < limited.workBudget());
        assertFalse(limited.withinBudget());
    }

    @Test void atomicGenerationOverrunStaysVisible() {
        var result = assertDoesNotThrow(() -> run(addZero(new VariableExpr("x")), 2));
        assertFalse(result.withinBudget());
        assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, result.search().outcome());
        assertTrue(result.totalWork() > 2);
    }

    @Test void cheaperButProofRejectedProposalIsNotAnIncumbent() {
        Expr source = addZero(new VariableExpr("x"));
        TypedMoveSearch.TypedProvider untrusted = new TypedMoveSearch.TypedProvider() {
            @Override public Descriptor descriptor() { return DESCRIPTOR; }
            @Override public Batch candidates(MoveState state, MoveContext context) {
                var proposal = new Transformation("zero", CODEC.encodeExpression(new NumberExpr(0)), RewriteKind.SIMPLIFY,
                    false, -2, true, "forged", List.of(), "", "");
                return new Batch(List.of(SearchMove.from(proposal, DESCRIPTOR, 1)), TransformationWorkMetrics.flatEngine(1), true);
            }
        };
        var result = assertDoesNotThrow(() -> new TypedSourceOnlySearch().search(problem(source, BUDGET, untrusted), TypedSourceOnlySearchTest::score));
        assertEquals(source, result.incumbent().expression());
        assertTrue(result.witness().isEmpty());
        assertTrue(result.search().events().stream().anyMatch(e -> e.decision() == MoveSearch.Decision.PROOF_REJECTED));
    }

    @Test void tiesRetainTheInputInsteadOfAnUnnecessaryPath() {
        Expr source = addZero(new VariableExpr("x"));
        var result = assertDoesNotThrow(() -> new TypedSourceOnlySearch().search(problem(source, BUDGET,
            TypedMoveSearch.primitiveProvider(DESCRIPTOR, TRANSPORT)), state -> new TypedSourceOnlySearch.Score(1, 1)));
        assertEquals(source, result.incumbent().expression());
        assertTrue(result.witness().isEmpty());
    }

    @Test void targetedContextsCannotEnterSourceOnlySelection() {
        var source = addZero(new VariableExpr("x"));
        var p = problem(source, BUDGET, TypedMoveSearch.primitiveProvider(DESCRIPTOR, TRANSPORT));
        var targeted = new TypedMoveSearch.Problem(source, TypedMoveSearch.Context.frozen(new VariableExpr("x")),
            p.providers(), p.policy(), p.verifier(), p.stateScore(), p.mode(), p.scheduling(), p.budget());
        assertThrows(IllegalArgumentException.class, () -> new TypedSourceOnlySearch().search(targeted, TypedSourceOnlySearchTest::score));
    }

    private static TypedSourceOnlySearch.Result run(Expr source, long budget) {
        return new TypedSourceOnlySearch().search(problem(source, budget,
            TypedMoveSearch.primitiveProvider(DESCRIPTOR, TRANSPORT)), TypedSourceOnlySearchTest::score);
    }
    private static TypedMoveSearch.Problem problem(Expr source, long work, MoveProvider provider) {
        return new TypedMoveSearch.Problem(source, TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION),
            List.of(provider), MovePriorityPolicy.INVENTORY_ORDER, TypedMoveSearch.primitiveReplay(TRANSPORT), state -> 0,
            MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(6, 6, 0, 128, work));
    }
    private static Expr addZero(Expr source) { return new BinaryExpr(source, ADD, new NumberExpr(0)); }
    private static TypedSourceOnlySearch.Score score(TypedMoveSearch.State state) {
        long nodes = nodes(state.expression());
        return new TypedSourceOnlySearch.Score(nodes, nodes);
    }
    private static long nodes(Expr expression) {
        if (expression instanceof BinaryExpr binary) return 1 + nodes(binary.left()) + nodes(binary.right());
        if (expression instanceof FunctionExpr f) return 1 + f.arguments().stream().mapToLong(TypedSourceOnlySearchTest::nodes).sum();
        return 1;
    }
}
