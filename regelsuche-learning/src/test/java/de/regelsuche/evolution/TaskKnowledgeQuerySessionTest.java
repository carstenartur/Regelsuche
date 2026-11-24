package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CancellationException;
import de.regelsuche.search.moves.MoveSearch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TaskKnowledgeQuerySessionTest {
    private static CumulativeTaskKnowledge knowledge;

    @BeforeAll static void acquire() {
        var learned = CumulativeTaskKnowledgeContract.generations();
        knowledge = CumulativeTaskKnowledge.restorePrepared(TraceStrategyTransferExample.inventory(), learned.toCanonicalJson());
    }

    private static CheckedSchemaTaskSolver.Task useful() {
        return TaskKnowledgeSessionTest.task(CumulativeTaskKnowledgeContract.nested("r*s", 3, "t") + "+11", 11);
    }

    @Test void repeatedQueriesAvoidCandidateGenerationButPayFreshVerificationAndFinalReplay() {
        var task = useful();
        var baseline = knowledge.solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        try (var session = new TaskKnowledgeQuerySession(knowledge, 8, 1_000_000)) {
            var cold = session.solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            var repeated = session.solve(useful(), CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            // A fresh equal task must hit; reuse is not an object-identity trick.
            var warm = session.solve(useful(), CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            assertTrue(baseline.solved()); assertTrue(cold.solved()); assertTrue(warm.solved());
            assertFalse(cold.reused()); assertFalse(repeated.reused()); assertTrue(warm.reused());
            var before = baseline.solution().orElseThrow().evidence();
            var after = warm.execution().solution().orElseThrow().evidence();
            assertEquals(before.incumbent(), after.incumbent());
            assertEquals(before.witness(), after.witness());
            assertNotSame(cold.execution().solution().orElseThrow(), warm.execution().solution().orElseThrow());
            assertTrue(after.search().metrics().verificationWork() > 0);
            assertTrue(before.replayWork() > 0);
            assertEquals(before.replayWork(), after.replayWork());
            assertTrue(warm.totalWork() < baseline.totalWork(),
                () -> "repeated query=" + warm.totalWork() + " persistent baseline=" + baseline.totalWork());
            assertFalse(after.search().completeBoundedRelation());
            assertEquals(1, session.statistics().hits());
            assertEquals(1, session.statistics().retainedQueries());
        }
    }

    @Test void exactSourceGoalStrategyAndEveryBudgetDimensionSeparateQueries() {
        var task = useful();
        var b = task.budget();
        var budgets = List.of(
            new MoveSearch.Budget(31, 32, 200_000, 512, 200_000),
            new MoveSearch.Budget(32, 31, 200_000, 512, 200_000),
            new MoveSearch.Budget(32, 32, 199_999, 512, 200_000),
            new MoveSearch.Budget(32, 32, 200_000, 511, 200_000),
            new MoveSearch.Budget(32, 32, 200_000, 512, 199_999),
            new MoveSearch.Budget(32, 32, 200_000, 512, 200_000, 31));
        try (var session = new TaskKnowledgeQuerySession(knowledge, 32, 2_000_000)) {
            assertTrue(session.solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC).solved());
            for (var budget : budgets) {
                var changed = new CheckedSchemaTaskSolver.Task(task.source(), task.assumptions(), task.goal(), budget);
                assertFalse(session.solve(changed, CumulativeTaskKnowledge.Strategy.AUTOMATIC).reused());
            }
            assertFalse(session.solve(new CheckedSchemaTaskSolver.Task(task.source(), List.of("r!=0"), task.goal(), b),
                CumulativeTaskKnowledge.Strategy.AUTOMATIC).reused());
            assertFalse(session.solve(new CheckedSchemaTaskSolver.Task(task.source(), List.of(),
                CheckedSchemaTaskSolver.Goal.atMostNodes(9), b), CumulativeTaskKnowledge.Strategy.AUTOMATIC).reused());
            assertFalse(session.solve(TaskKnowledgeSessionTest.task(CumulativeTaskKnowledgeContract.nested("q*s", 3, "t") + "+11", 11),
                CumulativeTaskKnowledge.Strategy.AUTOMATIC).reused());
            assertFalse(session.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE).reused());
            assertEquals(0, session.statistics().hits());
            assertFalse(session.solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC).reused());
            assertTrue(session.solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC).reused());
        }
    }

    @Test void independentGenerationsAndOwnersNeverShareQueryPaths() {
        var empty = CumulativeTaskKnowledge.emptyPrepared(TraceStrategyTransferExample.inventory(), TaskConstructionProfile.NONE);
        assertNotEquals(knowledge.id(), empty.id());
        try (var first = new TaskKnowledgeQuerySession(knowledge, 8, 1_000_000);
             var second = new TaskKnowledgeQuerySession(empty, 8, 1_000_000)) {
            first.solve(useful(), CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            var independent = second.solve(useful(), CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            assertFalse(independent.reused());
            assertEquals(empty.id(), independent.execution().generationId());
            assertEquals(0, second.statistics().hits());
        }
    }

    @Test void unsolvedEmptyAndBudgetExhaustedQueriesNeverBecomeRetainedAnswers() {
        var negative = TaskKnowledgeSessionTest.task("(r+s)*(r-s)+s*(s+3)", 1);
        var small = TaskKnowledgeSessionTest.task("3", 1);
        var source = useful();
        var tiny = new CheckedSchemaTaskSolver.Task(source.source(), source.assumptions(), source.goal(),
            new MoveSearch.Budget(32, 32, 200_000, 512, 2));
        try (var session = new TaskKnowledgeQuerySession(knowledge, 8, 1_000_000)) {
            for (int repeat = 0; repeat < 2; repeat++) {
                var miss = session.solve(negative, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
                assertFalse(miss.solved()); assertFalse(miss.reused());
                assertTrue(session.solve(small, CumulativeTaskKnowledge.Strategy.AUTOMATIC).solved());
                var exhausted = session.solve(tiny, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
                assertFalse(exhausted.solved()); assertFalse(exhausted.reused());
                assertTrue(exhausted.totalWork() > 0);
            }
            assertEquals(0, session.statistics().retainedQueries());
            assertEquals(0, session.statistics().hits());
        }
    }

    @Test void interruptedQueriesDoNotReturnAnEarlierProof() {
        try (var session = new TaskKnowledgeQuerySession(knowledge, 8, 1_000_000)) {
            session.solve(useful(), CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            session.solve(useful(), CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            Thread.currentThread().interrupt();
            try {
                assertThrows(CancellationException.class, () -> session.solve(useful(), CumulativeTaskKnowledge.Strategy.AUTOMATIC));
            } finally { Thread.interrupted(); }
            assertEquals(0, session.statistics().hits());
        }
    }

    @Test void customObjectivesStayFreshAndFailuresMakeAccountingIncomplete() {
        int[] calls = {0};
        var source = useful();
        var goal = new CheckedSchemaTaskSolver.Goal(state -> {
            calls[0]++;
            throw new IllegalStateException("objective failed");
        }, 1);
        var task = new CheckedSchemaTaskSolver.Task(source.source(), List.of(), goal, source.budget());
        try (var session = new TaskKnowledgeQuerySession(knowledge, 8, 1_000_000)) {
            for (int i = 0; i < 2; i++) assertThrows(TaskKnowledgeQuerySession.QueryFailure.class,
                () -> session.solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC));
            assertEquals(2, calls[0]);
            assertEquals(0, session.statistics().hits());
            assertEquals(0, session.statistics().retainedQueries());
            assertEquals(2, session.statistics().failures());
            assertFalse(session.statistics().accountingComplete());
        }
    }

    @Test void capacityAndCharacterBoundsEvictOrBypassWithoutChangingProofs() {
        var other = TaskKnowledgeSessionTest.task(CumulativeTaskKnowledgeContract.nested("r*s", 3, "t") + "+13", 11);
        try (var session = new TaskKnowledgeQuerySession(knowledge, 1, 1_000_000)) {
            session.solve(useful(), CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            session.solve(useful(), CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            session.solve(other, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            assertFalse(session.solve(useful(), CumulativeTaskKnowledge.Strategy.AUTOMATIC).reused());
            assertEquals(2, session.statistics().evictions());
            assertEquals(0, session.statistics().retainedQueries());
            assertTrue(session.statistics().retainedCharacters() <= 1_000_000);
        }
        try (var session = new TaskKnowledgeQuerySession(knowledge, 8, 1)) {
            for (int i = 0; i < 2; i++) assertTrue(session.solve(useful(), CumulativeTaskKnowledge.Strategy.AUTOMATIC).solved());
            assertEquals(0, session.statistics().retainedQueries());
            assertEquals(0, session.statistics().retainedCharacters());
        }
    }

    @Test void disabledReuseKeepsThePersistentExecutionAndCloseReleasesPaths() {
        var baseline = knowledge.solve(useful(), CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        try (var disabled = new TaskKnowledgeQuerySession(knowledge, 0, 0)) {
            var result = disabled.solve(useful(), CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            assertFalse(result.reused()); assertEquals(baseline.totalWork(), result.totalWork());
            assertEquals(baseline.solution().orElseThrow().evidence().witness(), result.execution().solution().orElseThrow().evidence().witness());
        }
        var session = new TaskKnowledgeQuerySession(knowledge, 8, 1_000_000);
        session.solve(useful(), CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        session.solve(useful(), CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        assertEquals(1, session.statistics().retainedQueries());
        session.close(); session.close();
        assertEquals(0, session.statistics().retainedQueries());
        assertEquals(0, session.statistics().retainedCharacters());
        assertThrows(IllegalStateException.class, () -> session.solve(useful(), CumulativeTaskKnowledge.Strategy.AUTOMATIC));
    }

    @Test void paidRetentionCannotTurnATightBudgetIntoASuccess() {
        var task = useful();
        long work = knowledge.solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC).totalWork();
        var b = task.budget();
        var tight = new CheckedSchemaTaskSolver.Task(task.source(), task.assumptions(), task.goal(),
            new MoveSearch.Budget(b.maxPrimitiveSteps(), b.maxSearchDepth(), b.maxTheoryWork(), b.maxStates(), work + 1));
        try (var session = new TaskKnowledgeQuerySession(knowledge, 8, 1_000_000)) {
            var result = session.solve(tight, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            assertFalse(result.solved());
            assertTrue(result.totalWork() > tight.budget().totalWork());
            assertEquals(0, session.statistics().retainedQueries());
            assertEquals(result.totalWork(), session.statistics().knownWork());
        }
    }

    @Test void replayActuallyCallsTheExistingVerifierAndRejectsAForeignRule() {
        var original = TypedLearnedMoveInventory.primitives(TraceStrategyTransferExample.inventory()).newSearchSession(false, 0, 0);
        int[] verifications = {0};
        var instrumented = new TypedLearnedMoveInventory.SearchSession(original.providers(), List.of(), (state, move, context) -> {
            verifications[0]++;
            return original.verifier().verify(state, move, context);
        });
        var solver = new CheckedSchemaTaskSolver(instrumented, null);
        var task = TaskKnowledgeSessionTest.task("((x+0)*1)+0", 1);
        var first = solver.solve(task);
        assertTrue(first.solved()); assertFalse(first.evidence().witness().isEmpty());
        verifications[0] = 0;
        var replayed = solver.replay(task, new RetainedTaskPath(first.evidence().witness()));
        assertTrue(replayed.solved());
        assertEquals(2 * first.evidence().witness().size(), verifications[0], "admission and independent final replay");

        var edge = first.evidence().witness().getFirst();
        var descriptor = new de.regelsuche.search.moves.MoveProvider.Descriptor("foreign", "foreign",
            de.regelsuche.search.moves.SearchMove.SourceKind.PRIMITIVE,
            de.regelsuche.search.moves.SearchMove.ProofStrength.REPLAYABLE, List.of(),
            de.regelsuche.search.moves.SearchMove.ValueEvidence.UNKNOWN, "foreign");
        var encoded = new de.regelsuche.search.program.CompiledAstReplayCodec().encodeExpression(new de.regelsuche.ast.NumberExpr(0));
        var forged = de.regelsuche.search.moves.SearchMove.from(new de.regelsuche.transform.Transformation("foreign", encoded), descriptor, 1);
        var falsePath = new RetainedTaskPath(List.of(new de.regelsuche.search.moves.TypedMoveSearch.WitnessStep(
            edge.source(), edge.target(), forged, edge.verification())));
        var rejected = solver.replay(task, falsePath);
        assertFalse(rejected.solved());
        assertTrue(rejected.evidence().search().events().stream().anyMatch(event ->
            event.decision() == MoveSearch.Decision.PROOF_REJECTED));
    }

    @Test void firstSuccessfulOccurrencesPayOnlyBoundedObservationAndAcquireNoWitness() {
        try (var session = new TaskKnowledgeQuerySession(knowledge, 8, 1_000_000)) {
            for (int i = 0; i < 16; i++) {
                var task = TaskKnowledgeSessionTest.task(CumulativeTaskKnowledgeContract.nested("r" + i + "*s", 3, "t") + "+11", 11);
                var result = session.solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
                assertTrue(result.solved()); assertFalse(result.reused());
                assertTrue(result.retentionWork() < 100, "first occurrence must not traverse the full witness graph");
                assertEquals(0, session.statistics().admissions());
                assertEquals(0, session.statistics().retainedQueries());
            }
            assertTrue(session.statistics().retainedCharacters() <= 1_000_000);
            assertEquals(8, session.statistics().retainedObservations());
        }
    }
}
