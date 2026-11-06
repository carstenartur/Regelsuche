package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.IncrementalMoveProvider;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StructuralExpansionMeterTest {
    @Test
    void preservesFailureWorkAndRejectsNestedObservationBeforeWork() {
        var failure = assertThrows(CheckedSchemaTaskSolver.ProviderFailure.class, () ->
            StructuralExpansionRules.observe(() -> {
                RetainedOperation.work(37);
                throw new IllegalArgumentException("broken source");
            }));
        assertTrue(failure.observedProviderWork() >= 37);
        assertFalse(failure.accountingComplete());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertFalse(RetainedOperation.isObserved());
        var outer = new CountingSink();
        try (var scope = RetainedOperation.open(outer)) {
            long before = outer.work;
            assertThrows(IllegalStateException.class, () -> StructuralExpansionRules.observe(() -> {
                RetainedOperation.work(10_000);
                return 0;
            }));
            assertEquals(before, outer.work, "nested operation must be rejected before executing");
            assertTrue(RetainedOperation.isObserved(), "the outer scope must remain intact");
        }
        assertFalse(RetainedOperation.isObserved());
    }

    @Test
    void chargesGenerationAndSourceBoundReplayOfExistingRules() {
        var inventory = TypedLearnedMoveInventory.primitives(TraceStrategyTransferExample.inventory());
        var session = StructuralExpansionRules.attach(inventory.newSearchSession(false, 0, 0));
        var provider = session.providers().stream()
            .filter(item -> item.descriptor().id().endsWith("ast_canonical_normalize"))
            .findFirst().orElseThrow();
        var parser = new ExpressionParser();
        var codec = new CompiledAstReplayCodec();
        var source = parser.parseExactTerm("z+y+x").expression();
        var batch = provider.candidates(MoveState.root(codec.encodeExpression(source)), MoveContext.frozen(""));
        assertFalse(batch.moves().isEmpty());
        assertTrue(batch.work().delegatedMechanicalWorkUnits() > 10,
            "AST traversal and canonicalization must be charged beyond flat candidate events");
        var context = TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION);
        var actual = new TypedMoveSearch.State(source, 0, 0, "", List.of(), Set.of(), 0);
        var foreign = new TypedMoveSearch.State(parser.parseExactTerm("a+b+c").expression(), 0, 0, "", List.of(), Set.of(), 0);
        var accepted = session.verifier().verify(actual, batch.moves().getFirst(), context);
        var rejected = session.verifier().verify(foreign, batch.moves().getFirst(), context);
        assertTrue(accepted.accepted() && accepted.work() > 10);
        assertFalse(rejected.accepted());
        assertTrue(rejected.work() > 10, "failed replay is paid too");
    }

    @Test
    void incrementalExpansionKeepsTheWholeAtomicBatchBill() {
        var inventory = TypedLearnedMoveInventory.primitives(TraceStrategyTransferExample.inventory());
        var base = inventory.newSearchSession(false, 0, 0);
        var batchSession = StructuralExpansionRules.attach(base);
        var lazySession = StructuralExpansionRules.attach(base, true);
        var batchProvider = batchSession.providers().stream()
            .filter(item -> item.descriptor().id().endsWith("ast_power_two_to_product")).findFirst().orElseThrow();
        var incremental = (IncrementalMoveProvider) lazySession.providers().stream()
            .filter(item -> item.descriptor().id().equals(batchProvider.descriptor().id())).findFirst().orElseThrow();
        var state = MoveState.root(new CompiledAstReplayCodec().encodeExpression(
            new ExpressionParser().parseExactTerm("(x+y)^2").expression()));
        var batch = batchProvider.candidates(state, MoveContext.frozen(""));
        assertFalse(batch.moves().isEmpty());
        var cursor = incremental.openSession(state, MoveContext.frozen(""));
        long before = cursor.snapshot().work().metrics().totalWorkUnitsV2();
        assertTrue(cursor.next(0).isEmpty());
        assertEquals(before, cursor.snapshot().work().metrics().totalWorkUnitsV2());
        var transformation = cursor.next(1_000_000).orElseThrow();
        long paid = cursor.snapshot().work().metrics().totalWorkUnitsV2();
        cursor.close();
        assertTrue(paid >= batch.work().totalWorkUnitsV2(), "the full measured batch must be retained");
        assertTrue(cursor.snapshot().work().metrics().totalWorkUnitsV2() >= paid);
        var context = TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION);
        var parser = new ExpressionParser();
        var actual = new TypedMoveSearch.State(parser.parseExactTerm("(x+y)^2").expression(), 0, 0, "", List.of(), Set.of(), 0);
        var foreign = new TypedMoveSearch.State(parser.parseExactTerm("(x+z)^2").expression(), 0, 0, "", List.of(), Set.of(), 0);
        var move = SearchMove.from(transformation, incremental.descriptor(), paid);
        assertTrue(lazySession.verifier().verify(actual, move, context).accepted());
        var rejection = lazySession.verifier().verify(foreign, move, context);
        assertFalse(rejection.accepted());
        assertTrue(rejection.work() > 0);

        var tight = incremental.openSession(state, MoveContext.frozen(""));
        for (int i = 0; i < 8 && tight.snapshot().work().metrics().totalWorkUnitsV2() < batch.work().totalWorkUnitsV2(); i++) {
            tight.next(1);
        }
        long overrun = tight.snapshot().work().metrics().totalWorkUnitsV2();
        tight.close();
        assertTrue(overrun >= batch.work().totalWorkUnitsV2(), "a small pull cannot erase an atomic overrun");
        var malformed = incremental.openSession(MoveState.root("not-typed-json"), MoveContext.frozen(""));
        assertTrue(malformed.next(1_000_000).isEmpty());
        assertEquals(de.regelsuche.search.moves.IncrementalProviderContract.Status.FAILED, malformed.snapshot().status());
        assertFalse(malformed.snapshot().accountingComplete());
        assertTrue(malformed.snapshot().work().metrics().totalWorkUnitsV2() > 0);
        malformed.close();
    }

    private static final class CountingSink implements RetainedOperation.Sink {
        private long work;
        @Override public void executionWork(long units) { work += units; }
        @Override public void validationWork(long units) { work += units; }
        @Override public void checkpoint() { }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }
    }
}
