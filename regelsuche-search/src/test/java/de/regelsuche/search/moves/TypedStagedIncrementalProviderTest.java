package de.regelsuche.search.moves;

import static de.regelsuche.search.moves.IncrementalProviderContract.*;
import static de.regelsuche.search.moves.IncrementalProviderContractTest.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.transform.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TypedStagedIncrementalProviderTest {
    private static final Expr TARGET = NumberExpr.exact("1/3");
    private static final Expr SOURCE = new BinaryExpr(TARGET, BinaryOperator.ADD, new NumberExpr(0));
    private static final AstRewriteTransport TRANSPORT = new AstRewriteTransport(List.of(RULE), 100, 100);

    @Test void typedRegisteredSuccessNeverOpensLaterPrimitiveBatch() {
        var primitive = TypedMoveSearch.primitiveProvider(nativeProvider().descriptor(), TRANSPORT);
        var definition = definition("model-1", Transport.TYPED_AST_JSON);
        var provider = new RegisteredIncrementalMoveProvider(descriptor(), definition, new Registry(List.of(
            new Registration(definition, (state, context, meter) -> typedSource(primitive, state, context, meter)))));
        var result = new TypedMoveSearch().search(problem(List.of(primitive, provider)));
        assertTrue(result.reached());
        assertEquals(TARGET, result.witness().getFirst().target().expression());
        var receipt = result.encodedResult().stagedIncrementalExecution();
        assertNull(receipt.expansions().getFirst().lanes().getLast().cursor());
        assertEquals(Kind.TYPED_PRIMITIVE_BATCH, receipt.providers().getFirst().definition().kind());
        assertEquals(Transport.TYPED_AST_JSON, receipt.providers().getLast().definition().transport());
        assertEquals(1, result.metrics().generatedSuccessors());
        assertEquals(1, result.metrics().primitiveWork());
    }

    @Test void parserTextRegistrationsAreNotSilentlyRelabeledAsTyped() {
        assertThrows(IllegalArgumentException.class, () -> problem(List.of(registered(nativeFactory()))));
        assertThrows(IllegalArgumentException.class, () -> problem(List.of(nativeProvider())));
    }

    @Test void batchIsMaterializedOnlyOnPositivePullAndAllUnconsumedWorkIsRetained() {
        var primitive = TypedMoveSearch.primitiveProvider(nativeProvider().descriptor(), TRANSPORT);
        var codec = new de.regelsuche.search.program.CompiledAstReplayCodec();
        Expr source = new BinaryExpr(SOURCE, BinaryOperator.MUL, SOURCE);
        try (var picker = new IncrementalMovePicker(List.of(primitive), MovePriorityPolicy.INVENTORY_ORDER,
                MoveState.root(codec.encodeExpression(source)), MoveContext.frozen(codec.encodeExpression(TARGET)))) {
            assertTrue(picker.next(0).isEmpty());
            assertNull(picker.stagedReceipt().lanes().getFirst().cursor());
            assertTrue(picker.next(1000).isPresent());
            assertEquals(2, picker.generatedMoves().size());
            assertEquals(2, picker.workMetrics().candidateWork().primitiveRewrites());
            var first = picker.stagedReceipt().lanes().getFirst().cursor();
            assertEquals(1, first.emittedCandidates());
            picker.close();
            assertEquals(2, picker.workMetrics().candidateWork().primitiveRewrites());
            assertTrue(picker.stagedReceipt().lanes().getFirst().cursor().closed());
            assertFalse(picker.complete());
        }
    }

    @Test void everyMaterializedBatchMoveMustBindTheSourceBeforeEarlySuccess() {
        var primitive = TypedMoveSearch.primitiveProvider(nativeProvider().descriptor(), TRANSPORT);
        var provider = batchProvider((state, context) -> {
            var batch = primitive.candidates(state, context);
            var leaf = batch.moves().getFirst().transformation();
            var sequence = new TransformationProvenance.Sequence("foreign-source", List.of(leaf));
            var foreign = new Transformation(leaf.rule(), leaf.transformedExpression(), leaf.kind(), leaf.mayIncreaseComplexity(),
                leaf.estimatedCostDelta(), leaf.equivalencePreservingByConstruction(), leaf.applicationKey(), leaf.assumptions(),
                leaf.packId(), leaf.license(), leaf.primitiveRuleIds(), sequence);
            return new MoveProvider.Batch(List.of(batch.moves().getFirst(), SearchMove.from(foreign, nativeProvider().descriptor(), 1)),
                TransformationWorkMetrics.flatEngine(2), true);
        });
        var result = new TypedMoveSearch().search(problem(List.of(provider)));
        assertFalse(result.reached(), "an unconsumed wrong-source candidate must invalidate the materialized batch");
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE, result.outcome());
        assertEquals(0, result.metrics().generatedSuccessors());
        assertEquals(2, result.metrics().primitiveWork());
        assertEquals(0, result.metrics().verificationWork());
        assertTrue(result.metrics().searchWork() > 0);
    }

    @Test void batchConsumptionPreservesTheOriginalSearchMoveAndSharedCosts() {
        var primitive = TypedMoveSearch.primitiveProvider(nativeProvider().descriptor(), TRANSPORT);
        var original = new java.util.concurrent.atomic.AtomicReference<SearchMove>();
        var provider = batchProvider((state, context) -> {
            var batch = primitive.candidates(state, context);
            var move = batch.moves().getFirst();
            var enriched = new SearchMove(move.transformation(), move.sourceKind(), move.ruleId(), "batch-family", 77, 42, 9,
                move.primitiveExpansion(), move.assumptions(), SearchMove.ProofStrength.VERIFIED, move.provenance(),
                java.util.Set.of("retained-capability"), new SearchMove.ValueEvidence(1, 0, 1, 3, 1, false, "batch-evidence"));
            original.set(enriched);
            return new MoveProvider.Batch(List.of(enriched), batch.work(), batch.complete());
        });
        var codec = new de.regelsuche.search.program.CompiledAstReplayCodec();
        try (var picker = new IncrementalMovePicker(List.of(provider), MovePriorityPolicy.INVENTORY_ORDER,
                MoveState.root(codec.encodeExpression(SOURCE)), MoveContext.frozen(codec.encodeExpression(TARGET)))) {
            assertEquals(originalAfterPull(picker, original), picker.generatedMoves().getFirst());
            assertEquals(1, picker.workMetrics().candidateWork().primitiveRewrites());
            picker.close();
            assertEquals(1, picker.workMetrics().candidateWork().primitiveRewrites());
        }
    }
    private static SearchMove originalAfterPull(IncrementalMovePicker picker, java.util.concurrent.atomic.AtomicReference<SearchMove> original) {
        var emitted = picker.next(1000).orElseThrow();
        assertEquals(original.get(), emitted);
        return emitted;
    }
    private static TypedMoveSearch.TypedProvider batchProvider(java.util.function.BiFunction<MoveState, MoveContext, MoveProvider.Batch> generate) {
        return new TypedMoveSearch.TypedProvider() {
            @Override public Descriptor descriptor() { return nativeProvider().descriptor(); }
            @Override public Batch candidates(MoveState state, MoveContext context) { return generate.apply(state, context); }
        };
    }

    private static Source typedSource(MoveProvider primitive, MoveState state, MoveContext context, Meter meter) {
        return new Source() {
            @Override public Optional<Transformation> next(long allowance) {
                var batch = primitive.candidates(state, context);
                meter.charge(Operation.MATCH, batch.work().totalWorkUnits());
                meter.charge(batch.work().candidateWork());
                return batch.moves().stream().findFirst().map(SearchMove::transformation);
            }
            @Override public Status status() { return Status.EXHAUSTED; }
        };
    }
    private static TypedMoveSearch.Problem problem(List<MoveProvider> providers) {
        return new TypedMoveSearch.Problem(SOURCE, TypedMoveSearch.Context.frozen(TARGET), providers,
            TypedMoveSearch.Policy.INVENTORY_ORDER, TypedMoveSearch.primitiveReplay(TRANSPORT), state -> 0,
            MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED_INCREMENTAL, new MoveSearch.Budget(2, 2, 0, 10, 1000));
    }
}
