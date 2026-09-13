package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import de.regelsuche.transform.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncrementalNativeMoveReplayTest {
    private static final String SOURCE = "((a + 0) + (b + 0)) + 0";
    private static final String TARGET = ExpressionFormatter.format(new ExpressionParser().parseTerm("(a + 0) + (b + 0)"));
    private static final PatternRewriteRule ADD_ZERO = (PatternRewriteRule) AstRewriteTransformationEngine.defaultRules().stream()
        .filter(rule -> rule.id().equals("ast_add_zero_right")).findFirst().orElseThrow();
    private static final MoveProvider.Descriptor DESCRIPTOR = new MoveProvider.Descriptor("native-add-zero", "ast_add_zero_right",
        SearchMove.SourceKind.PRIMITIVE, SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "native-add-zero-control/v1");

    @Test void outerTargetDoesNotExecuteTheTwoInnerRewritesAndStillPaysForRealReplay() {
        var result = search(MoveSearch.Scheduling.INCREMENTAL_NATIVE_ORDER);
        assertTrue(result.reached());
        assertEquals(1, result.witness().size());
        assertEquals(TARGET, result.witness().getFirst().target().expression());
        assertEquals("ast_add_zero_right", result.witness().getFirst().move().ruleId());
        assertTrue(result.witness().getFirst().verification().accepted());
        assertEquals(1, result.witness().getFirst().verification().receipts().size());
        assertTrue(result.metrics().verificationWork() > 3);
        assertEquals(1, result.metrics().generatedSuccessors(), "outer goal must suspend generation before the two inner matches");
        assertEquals(1, result.metrics().primitiveWork());
        var execution = result.incrementalExecution();
        assertEquals(IncrementalMoveExecution.WORK_REVISION, execution.workRevision());
        var cursor = execution.expansions().getFirst().lanes().getFirst().cursor();
        assertTrue(cursor.closed());
        assertEquals(TransformationCursor.Status.CLOSED, cursor.status());
        assertEquals(1, cursor.attempts().size());
        assertEquals(List.of(), cursor.attempts().getFirst().path());
        assertEquals(1, cursor.work().units(TransformationCursor.Operation.INSTANTIATE));
        assertEquals(1, cursor.work().units(TransformationCursor.Operation.OCCURRENCE_VISIT));
        assertEquals(1, cursor.work().units(TransformationCursor.Operation.CLOSE));
        assertFalse(cursor.complete());
        assertEquals(result.witness().getFirst().verification().work(), result.metrics().verificationWork());
        assertTrue(LearnedSchedulingArtifacts.resultJson(result).contains(IncrementalMoveExecution.WORK_REVISION));
    }

    @Test void historicalNativeBatchExportsRemainDeterministic() {
        for (var scheduling : List.of(MoveSearch.Scheduling.EAGER_CONTROL, MoveSearch.Scheduling.STAGED)) {
            var result = search(scheduling);
            assertTrue(result.reached());
            String json = LearnedSchedulingArtifacts.resultJson(result);
            String expected = scheduling == MoveSearch.Scheduling.EAGER_CONTROL
                ? "sha256:4304041435f2bc5a6045b05926aa0250ff47db43c5eb0b73f66f663c40ad953d"
                : "sha256:4eeca7eca810aa334fa3cdf6eaf476412b778b9b86137a229989ce9a57773aaf";
            assertEquals(expected, SchematicProofPlan.hash(json));
            assertNull(result.incrementalExecution());
            assertEquals(json, LearnedSchedulingArtifacts.resultJson(search(scheduling)));
        }
    }

    @Test void completeSmallNativeClosureEqualsTheHistoricalBoundedRelation() {
        var incremental = search(MoveSearch.Scheduling.INCREMENTAL_NATIVE_ORDER, "absent", 100000, 100);
        var historical = search(MoveSearch.Scheduling.STAGED, "absent", 100000, 100);
        assertEquals(MoveSearch.Outcome.BOUNDED_EXHAUSTED, incremental.outcome());
        assertTrue(incremental.completeBoundedRelation());
        assertTrue(historical.completeBoundedRelation());
        assertEquals(historical.reachedStates(), incremental.reachedStates());
        // The existing formatter flattens addition; distinct occurrence rewrites can share a target.
        assertEquals(java.util.Set.of(SOURCE, "a + 0 + b + 0", "a + b + 0 + 0", "a + 0 + b", "a + b + 0", "a + b"),
            incremental.reachedStates().stream().map(MoveState::expression).collect(java.util.stream.Collectors.toSet()));
        assertEquals(8, incremental.metrics().generatedSuccessors());
        assertTrue(incremental.events().stream().flatMap(event -> event.verificationResult().stream()).allMatch(MoveVerifier.Verification::accepted));
        assertTrue(incremental.incrementalExecution().expansions().stream().allMatch(expansion -> expansion.closed()
            && expansion.lanes().getFirst().cursor().closed()));
        assertEquals(incremental.incrementalExecution().expansions().stream().flatMap(expansion -> expansion.lanes().stream())
            .mapToLong(lane -> lane.cursor().work().primitiveRewrites()).sum(), incremental.metrics().primitiveWork());
        assertEquals(LearnedSchedulingArtifacts.resultJson(incremental),
            LearnedSchedulingArtifacts.resultJson(search(MoveSearch.Scheduling.INCREMENTAL_NATIVE_ORDER, "absent", 100000, 100)));
    }

    @Test void closeWorkCanInvalidateAnOtherwiseVerifiedTargetAndStateLimitsCloseSuspensions() {
        var full = search(MoveSearch.Scheduling.INCREMENTAL_NATIVE_ORDER);
        var limited = search(MoveSearch.Scheduling.INCREMENTAL_NATIVE_ORDER, TARGET, full.metrics().totalWork() - 1, 100);
        assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, limited.outcome());
        assertFalse(limited.reached());
        assertTrue(limited.witness().isEmpty());
        assertEquals(full.metrics().totalWork(), limited.metrics().totalWork());
        assertEquals(full.metrics().verificationWork(), limited.metrics().verificationWork());
        assertTrue(limited.events().getFirst().verification().accepted());
        assertTrue(limited.incrementalExecution().expansions().getFirst().lanes().getFirst().cursor().closed());
        var states = search(MoveSearch.Scheduling.INCREMENTAL_NATIVE_ORDER, TARGET, 100000, 1);
        assertEquals(MoveSearch.Outcome.STATE_LIMIT, states.outcome());
        assertEquals(1, states.metrics().generatedSuccessors());
        assertEquals(1, states.incrementalExecution().expansions().getFirst().lanes().getFirst().cursor().work()
            .units(TransformationCursor.Operation.CLOSE));
    }

    @Test void nativeReplayRejectsForgeryAndUnfrozenRuleIdentifiers() {
        var verifier = PrimitiveReplayMoveVerifier.nativePatterns(List.of(ADD_ZERO));
        var state = MoveState.root(SOURCE);
        var forged = SearchMove.from(new Transformation(ADD_ZERO.id(), "a + 7"), DESCRIPTOR, 1);
        assertFalse(verifier.verify(state, forged, MoveContext.frozen(TARGET)).accepted());
        var foreign = SearchMove.from(new Transformation("other-rule", TARGET), DESCRIPTOR, 1);
        assertFalse(verifier.verify(state, foreign, MoveContext.frozen(TARGET)).accepted());
    }

    @Test void atomicGenerationAndVerificationOverrunsRetainDistinctWorkWithoutClaimingSuccess() {
        var generation = search(MoveSearch.Scheduling.INCREMENTAL_NATIVE_ORDER, TARGET, 8, 100);
        assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, generation.outcome());
        assertEquals(1, generation.metrics().generatedSuccessors());
        assertEquals(1, generation.metrics().unconsumedSuccessors());
        assertEquals(0, generation.metrics().consumedSuccessors());
        assertEquals(0, generation.metrics().verificationWork());
        assertTrue(generation.events().isEmpty());
        assertEquals(1, generation.metrics().primitiveWork());
        var full = search(MoveSearch.Scheduling.INCREMENTAL_NATIVE_ORDER);
        long budget = full.metrics().totalWork() - full.metrics().verificationWork() + 1;
        var verification = search(MoveSearch.Scheduling.INCREMENTAL_NATIVE_ORDER, TARGET, budget, 100);
        assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, verification.outcome());
        assertEquals(full.metrics().verificationWork(), verification.metrics().verificationWork());
        assertEquals(MoveSearch.Decision.WORK_LIMIT, verification.events().getFirst().decision());
        assertTrue(verification.events().getFirst().verification().accepted());
        assertTrue(verification.metrics().totalWork() > budget);
        assertEquals(1, verification.metrics().primitiveWork());
        assertTrue(verification.incrementalExecution().expansions().getFirst().lanes().getFirst().cursor().closed());
    }

    private static MoveSearch.Result search(MoveSearch.Scheduling scheduling) {
        return search(scheduling, TARGET, 100000, 100);
    }

    private static MoveSearch.Result search(MoveSearch.Scheduling scheduling, String goal, long budget, int states) {
        var engine = new PreparedAstRewriteTransformationEngine(List.of(ADD_ZERO), Integer.MAX_VALUE, Integer.MAX_VALUE);
        MoveProvider provider = scheduling == MoveSearch.Scheduling.INCREMENTAL_NATIVE_ORDER
            ? new NativeIncrementalMoveProvider(DESCRIPTOR, engine) : new EngineMoveProvider(DESCRIPTOR, engine, true);
        return new MoveSearch().search(new MoveSearch.Problem(SOURCE, MoveContext.frozen(goal), List.of(provider),
            MovePriorityPolicy.INVENTORY_ORDER, PrimitiveReplayMoveVerifier.nativePatterns(List.of(ADD_ZERO)), state -> state.searchDepth() * 100,
            goal.equals("absent") ? MoveSearch.Mode.COMPLETE_BOUNDED_REFERENCE : MoveSearch.Mode.FAST,
            scheduling, new MoveSearch.Budget(3, 3, 0, states, budget)));
    }

}
