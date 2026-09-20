package de.regelsuche.search.moves;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.transform.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TypedPrimitiveCandidateCacheTest {
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static final PatternRewriteRule ZERO = new PatternRewriteRule("zero", PatternExpr.op(ADD,
        PatternExpr.var("A"), PatternExpr.num(0)), PatternExpr.var("A"));
    private static final PatternRewriteRule ONE = new PatternRewriteRule("one", PatternExpr.op(MUL,
        PatternExpr.var("A"), PatternExpr.num(1)), PatternExpr.var("A"));
    private static final List<PatternRewriteRule> RULES = List.of(ZERO, ONE);
    private static final MoveProvider.Descriptor DESCRIPTOR = new MoveProvider.Descriptor("arithmetic", "arithmetic",
        SearchMove.SourceKind.PRIMITIVE, SearchMove.ProofStrength.REPLAYABLE, List.of(),
        SearchMove.ValueEvidence.UNKNOWN, "immutable-arithmetic-v1");
    private static final MoveContext CONTEXT = MoveContext.frozen("");

    @Test void repeatedGenerationReusesCandidatesButPreservesTheirProofMetadata() {
        var cache = cache(8, 1_000_000);
        var source = state(addZero(new VariableExpr("x")));
        var first = cache.candidates(source, CONTEXT);
        var second = cache.candidates(source, CONTEXT);
        assertFalse(first.moves().isEmpty());
        assertEquals(first.moves(), second.moves());
        assertEquals(first.complete(), second.complete());
        assertSame(first.moves(), second.moves(), "reuse the immutable candidates, not regenerated lookalikes");
        assertEquals(1, cache.statistics().misses());
        assertEquals(1, cache.statistics().hits());
        assertEquals(0, second.work().sourceInvocations());
        assertTrue(second.work().totalWorkUnits() > 0, "lookup and delivery are not free");
        assertEquals(first.work().candidateWork(), second.work().candidateWork(), "do not erase mathematical work");
    }

    @Test void pathDependentStatesRemainDistinctButCanReusePureGeneration() {
        var cache = cache(8, 1_000_000);
        var root = state(addZero(new VariableExpr("x")));
        var deeper = new MoveState(root.expression(), 3, 4, "other", List.of(), Set.of("landmark"), 7);
        assertNotEquals(root, deeper);
        assertEquals(cache.candidates(root, CONTEXT).moves(), cache.candidates(deeper, CONTEXT).moves());
        assertEquals(1, cache.statistics().hits());
    }

    @Test void premisesPhaseAndGoalAreSeparateCacheContexts() {
        var cache = cache(8, 1_000_000);
        var root = state(addZero(new VariableExpr("x")));
        cache.candidates(root, CONTEXT);
        cache.candidates(new MoveState(root.expression(), 0, 0, "", List.of("x!=0"), Set.of(), 0), CONTEXT);
        cache.candidates(root, new MoveContext("", List.of("x!=0"), MoveContext.Phase.FROZEN_EVALUATION));
        cache.candidates(root, new MoveContext("", List.of(), MoveContext.Phase.TRAIN));
        cache.candidates(root, MoveContext.frozen("another-goal"));
        assertEquals(5, cache.statistics().misses());
        assertEquals(0, cache.statistics().hits());
        assertEquals(5, cache.statistics().entries());
    }

    @Test void scopedIdentityAndGroupingAreNotCollapsed() {
        var cache = cache(8, 1_000_000);
        Expr a = VariableExpr.scoped(new SymbolId(new UUID(0, 91), 1));
        Expr b = VariableExpr.scoped(new SymbolId(new UUID(0, 91), 2));
        var first = cache.candidates(state(addZero(a)), CONTEXT);
        var second = cache.candidates(state(addZero(b)), CONTEXT);
        assertNotEquals(first.moves(), second.moves());
        cache.candidates(state(addZero(new BinaryExpr(new BinaryExpr(a, MUL, b), MUL, NumberExpr.exact("1/3")))), CONTEXT);
        cache.candidates(state(addZero(new BinaryExpr(a, MUL, new BinaryExpr(b, MUL, NumberExpr.exact("1/3"))))), CONTEXT);
        assertEquals(4, cache.statistics().entries());
        assertEquals(0, cache.statistics().hits());
    }

    @Test void boundedFifoEvictionRegeneratesWithoutDroppingMoves() {
        var cache = cache(1, 1_000_000);
        var a = state(addZero(new VariableExpr("a")));
        var original = cache.candidates(a, CONTEXT);
        cache.candidates(state(addZero(new VariableExpr("b"))), CONTEXT);
        assertEquals(original.moves(), cache.candidates(a, CONTEXT).moves());
        assertEquals(3, cache.statistics().misses());
        assertEquals(1, cache.statistics().entries());
    }

    @Test void oversizedBatchesAreReturnedWithoutRetention() {
        var cache = cache(8, 1);
        var source = state(addZero(new VariableExpr("x")));
        assertFalse(cache.candidates(source, CONTEXT).moves().isEmpty());
        assertFalse(cache.candidates(source, CONTEXT).moves().isEmpty());
        assertEquals(0, cache.statistics().entries());
        assertEquals(0, cache.statistics().retainedCharacters());
        assertEquals(2, cache.statistics().bypasses());
    }

    @Test void disabledCacheIsTheUnmodifiedPrimitiveControl() {
        var cache = cache(0, 0);
        var source = state(addZero(new VariableExpr("x")));
        var plain = TypedMoveSearch.primitiveProvider(DESCRIPTOR, transport());
        assertEquals(plain.candidates(source, CONTEXT), cache.candidates(source, CONTEXT));
        assertEquals(plain.candidates(source, CONTEXT), cache.candidates(source, CONTEXT));
        assertEquals(0, cache.statistics().entries());
    }

    @Test void negativeGenerationResultsAreReusableWithoutClaimingCompleteness() {
        var cache = cache(8, 1_000_000);
        var source = state(new VariableExpr("x"));
        assertTrue(cache.candidates(source, CONTEXT).moves().isEmpty());
        var hit = cache.candidates(source, CONTEXT);
        assertTrue(hit.moves().isEmpty());
        assertFalse(hit.complete());
        assertEquals(1, cache.statistics().hits());
        assertTrue(hit.work().totalWorkUnits() > 0);
    }

    @Test void cachedCandidatesStillRequireFreshPrimitiveReplay() {
        var cache = cache(8, 1_000_000);
        Expr expression = addZero(new VariableExpr("x"));
        cache.candidates(state(expression), CONTEXT);
        var hit = cache.candidates(state(expression), CONTEXT);
        var source = new TypedMoveSearch.State(expression, 0, 0, "", List.of(), Set.of(), 0);
        var context = TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION);
        var accepted = TypedMoveSearch.primitiveReplay(transport()).verify(source, hit.moves().getFirst(), context);
        var rejected = TypedMoveSearch.primitiveReplay(new AstRewriteTransport(List.of(ONE), 64, 128))
            .verify(source, hit.moves().getFirst(), context);
        assertTrue(accepted.accepted());
        assertTrue(accepted.work() > 0);
        assertFalse(rejected.accepted(), "cached candidates are not proof authorization");
    }

    @Test void fullSearchKeepsEveryAdmittedStateAndFreshSelectedReplay() {
        Expr source = addZero(new BinaryExpr(addZero(new VariableExpr("x")), MUL, new NumberExpr(1)));
        var uncached = search(source, cache(0, 0));
        var reused = search(source, cache(64, 1_000_000));
        assertEquals(uncached.incumbent(), reused.incumbent());
        assertEquals(uncached.witness(), reused.witness());
        assertEquals(uncached.replayWork(), reused.replayWork());
        assertTrue(reused.replayWork() > 0);
        assertEquals(uncached.search().reachedStates(), reused.search().reachedStates());
        assertEquals(uncached.search().events(), reused.search().events());
    }

    @Test void cachesDoNotShareEntriesAcrossInstances() {
        var a = cache(8, 1_000_000);
        var b = cache(8, 1_000_000);
        var source = state(addZero(new VariableExpr("x")));
        a.candidates(source, CONTEXT);
        b.candidates(source, CONTEXT);
        assertEquals(1, a.statistics().entries());
        assertEquals(1, b.statistics().misses());
        assertEquals(0, b.statistics().hits());
    }

    @Test void rejectsMutableRuleSubclassesAndInvalidBounds() {
        var custom = new PatternRewriteRule("custom", PatternExpr.var("A"), PatternExpr.var("A")) {};
        assertThrows(IllegalArgumentException.class, () -> new TypedPrimitiveCandidateCache(DESCRIPTOR,
            List.of(custom), 64, 128, 8, 1_000_000));
        assertThrows(IllegalArgumentException.class, () -> cache(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> cache(1, -1));
    }

    private static TypedPrimitiveCandidateCache cache(int capacity, long characters) {
        return new TypedPrimitiveCandidateCache(DESCRIPTOR, RULES, 64, 128, capacity, characters);
    }
    private static AstRewriteTransport transport() { return new AstRewriteTransport(List.copyOf(RULES), 64, 128); }
    private static Expr addZero(Expr expression) { return new BinaryExpr(expression, ADD, new NumberExpr(0)); }
    private static MoveState state(Expr expression) {
        return new MoveState(CODEC.encodeExpression(expression), 0, 0, "", List.of(), Set.of(), 0);
    }
    private static TypedSourceOnlySearch.Result search(Expr source, MoveProvider provider) {
        var problem = new TypedMoveSearch.Problem(source,
            TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION),
            List.of(provider), MovePriorityPolicy.INVENTORY_ORDER, TypedMoveSearch.primitiveReplay(transport()),
            state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(8, 8, 0, 256, 100_000));
        return new TypedSourceOnlySearch().search(problem, state -> new TypedSourceOnlySearch.Score(
            CODEC.encodeExpression(state.expression()).length(), 1));
    }
}
