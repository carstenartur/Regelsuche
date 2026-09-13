package de.regelsuche.search.moves;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.transform.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IncrementalMoveSearchContractTest {
    private static final PatternRewriteRule ADD_ZERO = new PatternRewriteRule("z-add-zero",
        PatternExpr.op(ADD, PatternExpr.var("A"), PatternExpr.num(0)), PatternExpr.var("A"));
    private static final MoveVerifier UNUSED = (state, move, context) -> { throw new AssertionError("verification must not be requested"); };

    @Test void providerAndNativeOccurrenceOrderAreFrozenWithoutOpeningTheNextProvider() {
        var first = provider("z-provider", ADD_ZERO, List.of());
        var secondRule = new PatternRewriteRule("a-add-zero", ADD_ZERO.source(), ADD_ZERO.target());
        var second = provider("a-provider", secondRule, List.of());
        var mutable = new ArrayList<MoveProvider>(List.of(first, second));
        String source = "(a + 0) + 0";
        try (var picker = new IncrementalMovePicker(mutable, MoveState.root(source), MoveContext.frozen("unused"))) {
            mutable.clear();
            var moves = new ArrayList<Transformation>();
            moves.add(picker.next().orElseThrow().transformation());
            assertEquals("z-add-zero", moves.getFirst().rule());
            assertNull(picker.receipt().lanes().get(1).cursor());
            for (var next = picker.next(); next.isPresent(); next = picker.next()) moves.add(next.orElseThrow().transformation());
            var expected = new ArrayList<>(new PreparedAstRewriteTransformationEngine(List.of(ADD_ZERO)).transform(source));
            expected.addAll(new PreparedAstRewriteTransformationEngine(List.of(secondRule)).transform(source));
            assertEquals(expected, moves);
            assertTrue(picker.complete());
            assertEquals(0, picker.workMetrics().priorityCandidatesOrdered());
        }
    }

    @Test void unsupportedBatchProvidersAndArbitraryPoliciesAreRejectedBeforeExecution() {
        var invocations = new AtomicInteger();
        var batch = new EngineMoveProvider(provider("native", ADD_ZERO, List.of()).descriptor(),
            source -> { invocations.incrementAndGet(); return List.of(); }, true);
        assertThrows(IllegalArgumentException.class, () -> problem("a", List.of(batch), MovePriorityPolicy.INVENTORY_ORDER, 100));
        assertThrows(IllegalArgumentException.class, () -> problem("a", List.of(provider("native", ADD_ZERO, List.of())),
            (move, state, context) -> { invocations.incrementAndGet(); return 0; }, 100));
        assertEquals(0, invocations.get());
        var nativeProvider = provider("native", ADD_ZERO, List.of());
        assertThrows(UnsupportedOperationException.class, () -> nativeProvider.candidates(MoveState.root("a"), MoveContext.frozen("b")));
        assertThrows(IllegalArgumentException.class, () -> new MoveSearch.Problem("a", MoveContext.frozen("b"), List.of(nativeProvider),
            MovePriorityPolicy.INVENTORY_ORDER, UNUSED, state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(3, 3, 0, 20, 100)));
    }

    @Test void missingAssumptionsRemainARecordedRejectionWithoutParsing() {
        var guarded = provider("guarded", ADD_ZERO, List.of("a > 0"));
        assertThrows(IllegalArgumentException.class, () -> guarded.openCursor(MoveState.root("a + 0"), MoveContext.frozen("absent")));
        var result = new MoveSearch().search(problem("a + 0", List.of(guarded),
            MovePriorityPolicy.INVENTORY_ORDER, 100));
        assertEquals(MoveSearch.Outcome.BOUNDED_EXHAUSTED, result.outcome());
        assertTrue(result.completeBoundedRelation());
        var lane = result.incrementalExecution().expansions().getFirst().lanes().getFirst();
        assertTrue(lane.assumptionChecked()); assertTrue(lane.assumptionRejected()); assertNull(lane.cursor());
        assertEquals(0, result.metrics().primitiveWork());
        assertEquals(0, result.metrics().verificationWork());
        assertTrue(result.metrics().searchWork() >= 2);
    }

    @Test void emptyFailedPrefixAndMatcherInconclusiveKeepWorkAndFailClosed() {
        var noMatch = new MoveSearch().search(problem("a + b + c + d + e", List.of(provider("native", ADD_ZERO, List.of())),
            MovePriorityPolicy.INVENTORY_ORDER, 12));
        assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, noMatch.outcome());
        var cursor = noMatch.incrementalExecution().expansions().getFirst().lanes().getFirst().cursor();
        assertTrue(cursor.closed()); assertFalse(cursor.complete());
        assertFalse(cursor.attempts().isEmpty());
        assertTrue(cursor.attempts().stream().allMatch(attempt -> attempt.outcome() == TransformationCursor.AttemptOutcome.NOT_MATCHED));
        assertEquals(0, noMatch.metrics().generatedSuccessors()); assertEquals(0, noMatch.metrics().verificationWork());
        assertTrue(noMatch.metrics().totalWork() >= 12);
        var ac = new PatternRewriteRule("limited-ac", PatternExpr.op(ADD, PatternExpr.variable("a"), PatternExpr.variable("b")),
            PatternExpr.variable("a"), RecognitionProfile.arithmeticAc());
        var bounded = new NativeIncrementalMoveProvider(provider("ac", ac, List.of()).descriptor(),
            new PreparedAstRewriteTransformationEngine(List.of(ac)), 1);
        var inconclusive = new MoveSearch().search(problem("b + a", List.of(bounded), MovePriorityPolicy.INVENTORY_ORDER, 1000));
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE, inconclusive.outcome());
        assertFalse(inconclusive.completeBoundedRelation());
        var observed = inconclusive.incrementalExecution().expansions().getFirst().lanes().getFirst().cursor();
        assertTrue(observed.attempts().stream().anyMatch(attempt -> attempt.outcome() == TransformationCursor.AttemptOutcome.MATCH_INCONCLUSIVE));
        assertTrue(observed.work().units(TransformationCursor.Operation.MATCHER_BRANCH) > 0);
    }

    @Test void invalidSourceKeepsSetupWorkAndIsNotACompleteDeadEnd() {
        var result = new MoveSearch().search(problem("(", List.of(provider("native", ADD_ZERO, List.of())),
            MovePriorityPolicy.INVENTORY_ORDER, 1000));
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE, result.outcome());
        assertFalse(result.completeBoundedRelation()); assertEquals(0, result.metrics().deadEnds());
        var cursor = result.incrementalExecution().expansions().getFirst().lanes().getFirst().cursor();
        assertEquals(TransformationCursor.Status.INVALID_INPUT, cursor.status());
        assertEquals(1, cursor.work().units(TransformationCursor.Operation.PARSE));
        assertTrue(cursor.closed());
    }

    private static NativeIncrementalMoveProvider provider(String id, PatternRewriteRule rule, List<String> assumptions) {
        return new NativeIncrementalMoveProvider(new MoveProvider.Descriptor(id, rule.id(), SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, assumptions, SearchMove.ValueEvidence.UNKNOWN, "native-controls/v1"),
            new PreparedAstRewriteTransformationEngine(List.of(rule), Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
    private static MoveSearch.Problem problem(String source, List<MoveProvider> providers, MovePriorityPolicy policy, long budget) {
        return new MoveSearch.Problem(source, MoveContext.frozen("absent"), providers, policy, UNUSED, state -> 0,
            MoveSearch.Mode.COMPLETE_BOUNDED_REFERENCE, MoveSearch.Scheduling.INCREMENTAL_NATIVE_ORDER,
            new MoveSearch.Budget(3, 3, 0, 20, budget));
    }
}
