package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CheckedSchemaIndexedDispatchTest {
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static final String PAIR = "((a+b)*(a-b)+b*b)+((c+d)*(c-d)+d*d)";
    private static CheckedLearnedSchemaModel model;
    private static String schemaId;

    @BeforeAll static void learnAndRestore() {
        var formation = new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
        var learned = CheckedLearnedSchemaModel.learn(formation);
        model = CheckedLearnedSchemaModel.load(learned.toCanonicalJson(), learned.inventoryHash());
        schemaId = model.providers().getFirst().candidates(state("(x+y)*(x-y)+y*y"), context())
            .moves().stream().filter(move -> CODEC.decodeExpression(move.transformation().transformedExpression())
                .equals(parse("x^2"))).findFirst().orElseThrow().transformation().rule();
    }

    @Test void explicitOptInPaysPreparationAndHasADistinctIdentityWithoutRecompilingOnReuse() {
        var original = plan(model);
        var originalReceipt = original.compilationReceipt();
        var indexed = original.withIndexedDispatch();
        assertSame(indexed, indexed.withIndexedDispatch());
        assertEquals(originalReceipt, original.compilationReceipt());
        assertTrue(indexed.compilationWork() > original.compilationWork());
        assertEquals(CheckedSchemaMatcherPlan.INDEXED_WORK_REVISION, indexed.compilationReceipt().revision());
        assertNotEquals(original.provider().contractDefinition(), indexed.provider().contractDefinition());
        assertEquals(indexed.compilationReceipt(), CheckedSchemaMatcherPlan.prepareIndexed(model, 1, Map.of(), Set.of(schemaId)).compilationReceipt());
        assertDoesNotThrow(() -> RetainedGraph.measure(indexed));
    }

    @Test void mathematicalRegistrationDoesNotPromoteTheNewExecutionAccounting() {
        var original = plan(model).nativeProvider();
        var indexed = plan(model).withIndexedDispatch().nativeProvider();
        var inventory = (NativeVerifierProvider.ExecutionInventory) model.nativeVerifier();
        assertNotNull(inventory.executionRevision(original));
        assertNull(inventory.executionRevision(indexed));
        assertDoesNotThrow(() -> NativeVerifier.registered(List.of(indexed)));
    }

    @Test void learnedAndRestoredSchemasKeepTheSameOrderedCheckedApplications() {
        for (String source : List.of("(x+y)*(x-y)+y*y", PAIR, "x+y", "(x+y)*(x-y)+z*z")) {
            var original = drain(plan(model), source, 100_000);
            var indexed = drain(plan(model).withIndexedDispatch(), source, 100_000);
            assertEquals(original.transformations(), indexed.transformations(), source);
            assertEquals(original.status(), indexed.status(), source);
            assertEquals(original.complete(), indexed.complete(), source);
        }
        assertEquals(2, drain(plan(model).withIndexedDispatch(), PAIR, 100_000).transformations().size());
    }

    @Test void tinyPullsAndIndependentSessionsDoNotChangeTheWitnessesOrKeepAnOpenApplication() {
        var prepared = plan(model).withIndexedDispatch();
        var generous = drain(prepared, PAIR, 100_000);
        var tiny = drain(prepared, PAIR, 1);
        assertEquals(generous.transformations(), tiny.transformations());
        assertEquals(generous.status(), tiny.status());
        assertEquals(generous.transformations(), drain(prepared, PAIR, 100_000).transformations());
    }

    @Test void nativeAndEncodedCursorsAgreeAndExposeTheirActualNewOwnership() {
        var prepared = plan(model).withIndexedDispatch();
        var cursor = prepared.nativeProvider().openSession(new TypedMoveSearch.State(parse(PAIR), 0, 0, "", List.of(), Set.of(), 0),
            TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION));
        var actual = new ArrayList<Transformation>();
        try {
            for (int pulls = 0; pulls < 10_000; pulls++) {
                cursor.next(1).ifPresent(proof -> actual.add(proof.exportLegacy()));
                assertDoesNotThrow(() -> RetainedGraph.measure(cursor));
                if (terminal(cursor.snapshot().status())) break;
            }
            assertTrue(terminal(cursor.snapshot().status()), "cursor must terminate");
            assertEquals(drain(prepared, PAIR, 100_000).transformations(), actual);
        } finally { cursor.close(); }
        assertEquals(0, cursor.snapshot().work().prepaidApplications().openApplications());
    }

    @Test void missingAssumptionsAndUnsupportedDomainsStillCannotProduceEvidence() {
        var missing = plan(model.requiring(List.of("x > 0"))).withIndexedDispatch().provider()
            .openSession(state("(x+y)*(x-y)+y*y"), context());
        try {
            assertTrue(missing.next(100_000).isEmpty());
            assertEquals(0, missing.snapshot().work().mathematics().exactTheorySteps());
        } finally { missing.close(); }
        var unsupported = drain(plan(model).withIndexedDispatch(), "(1/x+y)*(1/x-y)+y*y", 100_000);
        assertTrue(unsupported.transformations().isEmpty());
        assertFalse(unsupported.complete());
    }

    @Test void closingAPaidIndexedApplicationAbandonsRatherThanRefundingIt() {
        var cursor = plan(model).withIndexedDispatch().provider().openSession(state(PAIR), context());
        assertTrue(cursor.next(0).isEmpty());
        for (int pulls = 0; pulls < 10_000 && cursor.snapshot().work().prepaidApplications().openApplications() == 0; pulls++) {
            assertTrue(cursor.next(1).isEmpty());
        }
        var before = cursor.snapshot();
        assertEquals(1, before.work().prepaidApplications().openApplications());
        cursor.close();
        var after = cursor.snapshot();
        assertEquals(0, after.work().prepaidApplications().openApplications());
        assertEquals(1, after.work().prepaidApplications().abandonedApplications());
        assertTrue(after.work().metrics().totalWorkUnitsV2() >= before.work().metrics().totalWorkUnitsV2());
        assertEquals(0, after.work().mathematics().exactTheorySteps());
        long paid = after.work().metrics().totalWorkUnitsV2();
        cursor.close();
        assertEquals(paid, cursor.snapshot().work().metrics().totalWorkUnitsV2());
    }

    @Test void retainPaidPreparationAndQueryDiagnosticsWithoutAssumingAWinner() {
        var original = plan(model);
        var indexed = original.withIndexedDispatch();
        long originalQueries = 0, indexedQueries = 0;
        for (int query = 1; query <= 32; query++) {
            String input = query % 2 == 0 ? PAIR : "(x+y)*(x-y)+z*z";
            var before = drain(original, input, 100_000);
            var after = drain(indexed, input, 100_000);
            assertEquals(before.transformations(), after.transformations());
            assertEquals(before.complete(), after.complete());
            originalQueries = Math.addExact(originalQueries, before.queryWork());
            indexedQueries = Math.addExact(indexedQueries, after.queryWork());
            if (query == 1 || query == 8 || query == 32) {
                System.out.println("CHECKED_SCHEMA_INDEXED_DIAGNOSTIC queries=" + query
                    + " originalPreparation=" + original.compilationWork()
                    + " indexedPreparation=" + indexed.compilationWork()
                    + " originalCursorWorkIncludingClose=" + originalQueries
                    + " indexedCursorWorkIncludingClose=" + indexedQueries
                    + " fullLifecycleQualified=false");
            }
        }
    }

    private record Run(List<Transformation> transformations, IncrementalProviderContract.Status status,
            boolean complete, long queryWork) { }

    private static Run drain(CheckedSchemaMatcherPlan plan, String source, long allowance) {
        var cursor = plan.provider().openSession(state(source), context());
        var results = new ArrayList<Transformation>();
        IncrementalProviderContract.Status status;
        boolean complete;
        try {
            for (int pulls = 0; pulls < 10_000; pulls++) {
                cursor.next(allowance).ifPresent(results::add);
                if (terminal(cursor.snapshot().status())) break;
            }
            var snapshot = cursor.snapshot();
            assertTrue(terminal(snapshot.status()), snapshot.detailCode());
            assertTrue(snapshot.accountingComplete(), snapshot.detailCode());
            status = snapshot.status();
            complete = snapshot.complete();
        } finally {
            cursor.close();
            assertEquals(0, cursor.snapshot().work().prepaidApplications().openApplications());
        }
        return new Run(List.copyOf(results), status, complete, cursor.snapshot().work().metrics().totalWorkUnitsV2());
    }
    private static boolean terminal(IncrementalProviderContract.Status status) {
        assertNotEquals(IncrementalProviderContract.Status.FAILED, status);
        return status == IncrementalProviderContract.Status.EXHAUSTED || status == IncrementalProviderContract.Status.INCONCLUSIVE;
    }
    private static CheckedSchemaMatcherPlan plan(CheckedLearnedSchemaModel selected) {
        return CheckedSchemaMatcherPlan.prepare(selected, 1, Map.of(), Set.of(schemaId));
    }
    private static Expr parse(String source) { return new ExpressionParser().parseExactTerm(source).expression(); }
    private static MoveState state(String source) { return MoveState.root(CODEC.encodeExpression(parse(source))); }
    private static MoveContext context() { return MoveContext.frozen("unused"); }
}
