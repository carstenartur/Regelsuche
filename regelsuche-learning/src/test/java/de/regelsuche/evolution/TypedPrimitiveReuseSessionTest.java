package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Integration regressions, not an independent benchmark or a modified v1 corpus. */
@Timeout(180)
class TypedPrimitiveReuseSessionTest {
    private static TypedLearnedMoveInventory inventory;
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();

    @BeforeAll static void formRealKnowledge() {
        inventory = new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits()).typedMoves();
    }

    @Test void sessionsOwnTheirCachesAndRetainTheActualLearnedPrograms() {
        assertFalse(inventory.learnedPrograms().isEmpty());
        var first = inventory.newSearchSession(true, 64, 1_000_000);
        var second = inventory.newSearchSession(true, 64, 1_000_000);
        assertEquals(inventory.primitiveProviders().size(), first.primitiveCaches().size());
        assertEquals(inventory.providers().size(), first.providers().size());
        for (int i = 0; i < first.primitiveCaches().size(); i++) {
            assertNotSame(first.primitiveCaches().get(i), second.primitiveCaches().get(i));
            assertEquals(0, first.primitiveCaches().get(i).statistics().entries());
        }
        assertEquals(inventory.learnedPrograms(), first.providers().stream()
            .filter(provider -> provider.descriptor().sourceKind() == SearchMove.SourceKind.LEARNED).toList());
    }

    @Test void actualCompiledRulesReuseTheSameSourceAcrossPathStates() {
        var session = inventory.newSearchSession(false, 64, 1_000_000);
        var expression = new ExpressionParser().parseTerm("(x+0)*1");
        var root = new MoveState(CODEC.encodeExpression(expression), 0, 0, "", List.of(), Set.of(), 0);
        var otherPath = new MoveState(root.expression(), 3, 4, "other", List.of(), Set.of("observed"), 0);
        var context = MoveContext.frozen("");
        assertEquals(8, session.primitiveCaches().size());
        for (int i = 0; i < session.providers().size(); i++) {
            var original = inventory.primitiveProviders().get(i).candidates(root, context);
            var cold = session.providers().get(i).candidates(root, context);
            var reused = session.providers().get(i).candidates(otherPath, context);
            assertEquals(original.moves(), cold.moves());
            assertEquals(original.moves(), reused.moves());
            assertEquals(original.complete(), reused.complete());
            assertEquals(1, session.primitiveCaches().get(i).statistics().hits());
            assertEquals(0, reused.work().sourceInvocations());
        }
    }

    @Test void primitiveAndLearnedSearchRetainEveryEventStateAndFreshReplay() {
        long hits = 0;
        for (boolean learned : List.of(false, true)) {
            for (String source : List.of("((a+b)*(a-b)+b*b)+((c+d)*(c-d)+d*d)",
                    "((a+0)*1)+(b+0)", "a+23", "(a+b)*(a-b)+b*(b+2)")) {
                var plain = inventory.newSearchSession(learned, 0, 0);
                var cached = inventory.newSearchSession(learned, 64, 1_000_000);
                var baseline = search(source, plain, 200_000);
                var actual = search(source, cached, 200_000);
                assertEquals(baseline.incumbent(), actual.incumbent(), source);
                assertEquals(baseline.witness(), actual.witness(), source);
                assertEquals(baseline.replayWork(), actual.replayWork(), source);
                assertEquals(baseline.search().events(), actual.search().events(), source);
                assertEquals(baseline.search().reachedStates(), actual.search().reachedStates(), source);
                assertEquals(baseline.search().outcome(), actual.search().outcome(), source);
                long rowHits = cached.primitiveCaches().stream().mapToLong(cache -> cache.statistics().hits()).sum();
                long misses = cached.primitiveCaches().stream().mapToLong(cache -> cache.statistics().misses()).sum();
                hits += rowHits;
                System.out.printf("REUSE_DIAGNOSTIC learned=%s source=%s hits=%d misses=%d originalWork=%d cachedWork=%d replayWork=%d%n",
                    learned, source, rowHits, misses, baseline.totalWork(), actual.totalWork(), actual.replayWork());
            }
        }
        assertTrue(hits > 0, "the real search must actually reuse generation, not just wrap providers");
    }

    @Test void tooSmallBudgetsDoNotHideReuseBookkeeping() {
        var session = inventory.newSearchSession(false, 8, 1_000_000);
        var result = search("(x+0)*1", session, 2);
        assertFalse(result.withinBudget());
        assertTrue(result.totalWork() > 2);
        assertEquals(result.search().metrics().totalWork() + result.selectionWork() + result.replayWork(), result.totalWork());
    }

    @Test void disabledSessionsKeepTheOriginalProvidersAndInvalidLimitsAreRejected() {
        assertEquals(inventory.primitiveProviders(), inventory.newSearchSession(false, 0, 0).providers());
        assertEquals(inventory.providers(), inventory.newSearchSession(true, 0, 0).providers());
        assertTrue(inventory.newSearchSession(true, 0, 0).primitiveCaches().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> inventory.newSearchSession(true, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> inventory.newSearchSession(true, 10, -1));
    }

    private static TypedSourceOnlySearch.Result search(String source, TypedLearnedMoveInventory.SearchSession session, long work) {
        var expression = new ExpressionParser().parseTerm(source);
        var problem = new TypedMoveSearch.Problem(expression,
            TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION),
            session.providers(), MovePriorityPolicy.INVENTORY_ORDER, session.verifier(), state -> 0,
            MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(9, 9, 0, 256, work, 24));
        return new TypedSourceOnlySearch().search(problem, state -> TypedPolynomialSurfaceCost.evaluate(state.expression()));
    }
}
