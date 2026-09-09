package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.search.program.RewriteCandidate;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class StagedMoveSearchTest {
    // Synthetic graph admission is deliberately separate from the real polynomial verifier's tests.
    private static final MoveVerifier GRAPH = (state, move, context) -> new MoveVerifier.Verification(true,
        move.transformation().primitiveStepCount(), List.of("synthetic-graph-edge"), "TEST_FIXTURE");
    private static MoveProvider provider(String id, SearchMove.SourceKind kind, de.regelsuche.transform.TransformationEngine engine, boolean complete) {
        return new EngineMoveProvider(new MoveProvider.Descriptor(id, id, kind, SearchMove.ProofStrength.REPLAYABLE, List.of(),
            new SearchMove.ValueEvidence(1, 0, 1, 2, 1, true, "fixture-reference"), "fixture"), engine, complete);
    }
    private static MoveSearch.Result search(List<MoveProvider> providers, String target, MoveSearch.Scheduling scheduling,
            MoveSearch.Mode mode, long work) {
        return new MoveSearch().search(new MoveSearch.Problem("a", MoveContext.frozen(target), providers,
            MovePriorityPolicy.INVENTORY_ORDER, GRAPH, state -> 0, mode, scheduling, new MoveSearch.Budget(4, 4, 0, 500, work)));
    }
    @Test void successfulEarlyChildPreventsExpensiveGenerationAndStillPaysForPrimitiveProof() {
        var expensive = new AtomicInteger();
        var macro = new RewriteCandidate("shortcut", "a", "c", List.of(new Transformation("one", "b"), new Transformation("two", "c"))).toTransformation();
        var learned = provider("learned", SearchMove.SourceKind.LEARNED, expression -> expression.equals("a") ? List.of(macro) : List.of(), true);
        var solver = provider("solver", SearchMove.SourceKind.SOLVER, expression -> { expensive.incrementAndGet(); return List.of(); }, true);
        var result = search(List.of(solver, learned), "c", MoveSearch.Scheduling.STAGED, MoveSearch.Mode.FAST, 1000);
        assertTrue(result.reached()); assertEquals(0, expensive.get());
        assertEquals(1, result.metrics().firstHitDepth()); assertEquals(2, result.metrics().firstHitPrimitiveDepth());
        assertEquals(2, result.metrics().verificationWork()); assertEquals(2, result.witness().getFirst().move().primitiveExpansion().size());
        assertTrue(search(List.of(solver, learned), "c", MoveSearch.Scheduling.EAGER_CONTROL, MoveSearch.Mode.FAST, 1000).reached());
        assertTrue(expensive.get() > 0);
    }
    @Test void largeLearnedBatchYieldsToPrimitiveLaneAndReferenceRetainsEveryReachableState() {
        var learned = provider("noise", SearchMove.SourceKind.LEARNED, expression -> expression.equals("a")
            ? IntStream.range(0, 50).mapToObj(i -> new Transformation("noise", "n" + i)).toList() : List.of(), true);
        var primitive = provider("base", SearchMove.SourceKind.PRIMITIVE, expression -> expression.equals("a")
            ? List.of(new Transformation("base", "b")) : List.of(), true);
        var picker = new StagedMovePicker(List.of(learned, primitive), MovePriorityPolicy.INVENTORY_ORDER, MoveState.root("a"), MoveContext.frozen("b"));
        assertEquals("noise", picker.next().orElseThrow().ruleId()); assertEquals("noise", picker.next().orElseThrow().ruleId());
        assertEquals("base", picker.next().orElseThrow().ruleId());
        var base = search(List.of(primitive), "absent", MoveSearch.Scheduling.STAGED, MoveSearch.Mode.COMPLETE_BOUNDED_REFERENCE, 100000);
        var augmented = search(List.of(learned, primitive), "absent", MoveSearch.Scheduling.STAGED, MoveSearch.Mode.COMPLETE_BOUNDED_REFERENCE, 100000);
        assertTrue(base.completeBoundedRelation()); assertTrue(augmented.completeBoundedRelation());
        assertTrue(augmented.reachedStates().containsAll(base.reachedStates()));
        assertEquals(51, augmented.metrics().generatedSuccessors());
    }
    @Test void budgetOverrunOpaqueProviderAndInvalidProofCannotClaimSuccessOrClosure() {
        var costly = provider("costly", SearchMove.SourceKind.LEARNED, expression -> IntStream.range(0, 100)
            .mapToObj(i -> new Transformation("costly", "b")).toList(), true);
        var overrun = search(List.of(costly), "b", MoveSearch.Scheduling.STAGED, MoveSearch.Mode.FAST, 10);
        assertFalse(overrun.reached()); assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, overrun.outcome());
        assertTrue(overrun.metrics().totalWork() > 10); assertEquals(100, overrun.metrics().unconsumedSuccessors());
        var opaque = provider("opaque", SearchMove.SourceKind.PRIMITIVE, expression -> List.of(), false);
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE, search(List.of(opaque), "b", MoveSearch.Scheduling.STAGED,
            MoveSearch.Mode.COMPLETE_BOUNDED_REFERENCE, 100).outcome());
        var invalid = provider("invalid", SearchMove.SourceKind.LEARNED, expression -> List.of(new Transformation("bad", "b")), true);
        var rejected = new MoveSearch().search(new MoveSearch.Problem("a", MoveContext.frozen("b"), List.of(invalid),
            MovePriorityPolicy.INVENTORY_ORDER, (s, m, c) -> new MoveVerifier.Verification(false, 9, List.of(), "REJECTED"),
            state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(4, 4, 0, 10, 1000)));
        assertFalse(rejected.reached()); assertFalse(rejected.completeBoundedRelation()); assertEquals(9, rejected.metrics().verificationWork());
    }
}
