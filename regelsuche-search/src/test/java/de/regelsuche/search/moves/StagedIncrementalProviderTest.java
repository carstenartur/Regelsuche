package de.regelsuche.search.moves;

import static de.regelsuche.search.moves.IncrementalProviderContractTest.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.transform.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StagedIncrementalProviderTest {
    @Test void registeredEarlySuccessLeavesLaterNativeStageUnopened() {
        var result = search(List.of(nativeProvider(), registered(nativeFactory())), 10000,
            replay());
        assertTrue(result.reached());
        assertEquals(1, result.metrics().generatedSuccessors());
        assertEquals(1, result.metrics().primitiveWork());
        assertEquals(1, result.metrics().familyMatches().get("zero"));
        var receipt = result.stagedIncrementalExecution();
        assertNull(result.incrementalExecution());
        assertTrue(receipt.accountingComplete());
        assertEquals(IncrementalProviderContract.Kind.REGISTERED_SCHEMA, receipt.providers().get(1).definition().kind());
        var lanes = receipt.expansions().getFirst().lanes();
        assertEquals(1, lanes.getFirst().providerIndex());
        assertTrue(lanes.getFirst().cursor().closed());
        assertNull(lanes.getLast().cursor(), "later native stage must never open");
    }

    @Test void rejectedRegisteredAssumptionsLeaveTheNativeStageAvailable() {
        var original = descriptor();
        var guarded = new MoveProvider.Descriptor(original.id(), original.ruleFamily(), original.sourceKind(), original.proofStrength(),
            List.of("a > 0"), original.valueEvidence(), original.provenanceId());
        var definition = definition("model-1", IncrementalProviderContract.Transport.PARSER_TEXT);
        var registry = new IncrementalProviderContract.Registry(List.of(new IncrementalProviderContract.Registration(definition, nativeFactory())));
        var result = search(List.of(new RegisteredIncrementalMoveProvider(guarded, definition, registry), nativeProvider()), 10000, replay());
        assertTrue(result.reached());
        assertEquals(SearchMove.SourceKind.PRIMITIVE, result.witness().getFirst().move().sourceKind());
        var rejected = result.stagedIncrementalExecution().expansions().getFirst().lanes().getFirst().cursor();
        assertEquals("ASSUMPTIONS_NOT_CARRIED", rejected.detailCode());
        assertEquals(0, rejected.work().units(IncrementalProviderContract.Operation.OPEN));
        assertEquals(1, rejected.work().units(IncrementalProviderContract.Operation.ADMISSION));
        assertTrue(rejected.complete());
        assertTrue(rejected.closed());
    }

    @Test void registryMetadataDoesNotAuthorizeAForgedMathematicalStep() {
        var forged = registered((state, context, meter) -> new IncrementalProviderContract.Source() {
            @Override public Optional<Transformation> next(long allowance) {
                meter.charge(new ExecutionWork(1, 0, 0));
                return Optional.of(new Transformation(RULE.id(), "a"));
            }
            @Override public IncrementalProviderContract.Status status() { return IncrementalProviderContract.Status.EXHAUSTED; }
        });
        var result = new MoveSearch().search(problem("a + 7", List.of(forged), 10000,
            replay()));
        assertFalse(result.reached());
        assertEquals(MoveSearch.Decision.PROOF_REJECTED, result.events().getFirst().decision());
        assertTrue(result.metrics().verificationWork() > 0);
        assertFalse(result.completeBoundedRelation());
    }

    @Test void failedCleanupCannotClaimBudgetRespectingSuccess() {
        var provider = registered((state, context, meter) -> {
            var delegate = nativeFactory().open(state, context, meter);
            return new IncrementalProviderContract.Source() {
                @Override public Optional<Transformation> next(long allowance) { return delegate.next(allowance); }
                @Override public IncrementalProviderContract.Status status() { return delegate.status(); }
                @Override public void close() {
                    delegate.close();
                    meter.charge(IncrementalProviderContract.Operation.CLOSE, 7);
                    throw new IllegalStateException("unobservable delegated cleanup remainder");
                }
            };
        });
        var result = search(List.of(provider), 10000, replay());
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE, result.outcome());
        assertFalse(result.reached());
        assertTrue(result.witness().isEmpty());
        assertTrue(result.events().getFirst().verification().accepted());
        assertEquals(1, result.metrics().primitiveWork());
        assertTrue(result.metrics().searchWork() > 7);
        assertFalse(result.stagedIncrementalExecution().accountingComplete());
    }

    @Test void nativeLegacyModeStillRefusesRegisteredProviders() {
        var provider = registered(nativeFactory());
        assertThrows(IllegalArgumentException.class, () -> new IncrementalMovePicker(List.of(provider), MoveState.root("a + 0"), CONTEXT));
        assertThrows(IllegalArgumentException.class, () -> new MoveSearch.Problem("a + 0", CONTEXT, List.of(provider),
            MovePriorityPolicy.INVENTORY_ORDER, (s, m, c) -> { throw new AssertionError(); }, state -> 0,
            MoveSearch.Mode.FAST, MoveSearch.Scheduling.INCREMENTAL_NATIVE_ORDER, new MoveSearch.Budget(2, 2, 0, 10, 100)));
    }

    @Test void stagedPickerPaysOrderingAndContextButZeroPullDoesNotOpenStages() {
        MovePriorityPolicy policy = new MovePriorityPolicy() {
            @Override public double score(SearchMove move, MoveState state, MoveContext context) { return 0; }
            @Override public long contextWork(MoveState state, MoveContext context) { return 9; }
        };
        try (var picker = new IncrementalMovePicker(List.of(nativeProvider(), registered(nativeFactory())), policy,
                MoveState.root("a + 0"), CONTEXT)) {
            assertEquals(11, picker.workMetrics().totalWorkUnits());
            assertTrue(picker.next(0).isEmpty());
            assertEquals(11, picker.workMetrics().totalWorkUnits());
            assertTrue(picker.stagedReceipt().lanes().stream().allMatch(lane -> lane.cursor() == null));
            assertTrue(picker.next(1000).isPresent());
            assertEquals(1, picker.generatedMoves().size());
            assertEquals(SearchMove.SourceKind.LEARNED, picker.generatedMoves().getFirst().sourceKind());
        }
    }

    @Test void atomicCandidateOverrunPreservesGeneratedButUnconsumedWork() {
        var result = search(List.of(registered(nativeFactory())), 11, (s, m, c) -> { throw new AssertionError(); });
        assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, result.outcome());
        assertEquals(1, result.metrics().generatedSuccessors());
        assertEquals(1, result.metrics().unconsumedSuccessors());
        assertEquals(0, result.metrics().consumedSuccessors());
        assertEquals(1, result.metrics().primitiveWork());
        assertTrue(result.metrics().totalWork() > 11);
        assertTrue(result.stagedIncrementalExecution().expansions().getFirst().lanes().getFirst().cursor().closed());
    }

    @Test void atomicOvershootKeepsKnownExactWorkEvenWithoutAnEndpoint() {
        var provider = registered((state, context, meter) -> {
            meter.charge(new ExecutionWork(0, 2, 40));
            throw new IllegalStateException("atomic exact calculation failed");
        });
        var result = search(List.of(provider), 8, (s, m, c) -> { throw new AssertionError(); });
        assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, result.outcome());
        assertEquals(40, result.metrics().primitiveWork());
        assertEquals(0, result.metrics().generatedSuccessors());
        assertTrue(result.metrics().totalWork() > 40);
        assertEquals(0, result.metrics().verificationWork());
    }

    @Test void typedPrimitiveBatchParticipatesOnlyWhenPulled() {
        var transport = new AstRewriteTransport(List.of(RULE), 100, 100);
        var primitive = TypedMoveSearch.primitiveProvider(nativeProvider().descriptor(), transport);
        Expr target = NumberExpr.exact("1/3");
        Expr source = new BinaryExpr(target, BinaryOperator.ADD, new NumberExpr(0));
        var result = new TypedMoveSearch().search(new TypedMoveSearch.Problem(source,
            TypedMoveSearch.Context.frozen(target), List.of(primitive), TypedMoveSearch.Policy.INVENTORY_ORDER,
            TypedMoveSearch.primitiveReplay(transport), state -> 0, MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED_INCREMENTAL, new MoveSearch.Budget(2, 2, 0, 10, 1000)));
        assertTrue(result.reached());
        assertEquals(target, result.witness().getFirst().target().expression());
        assertEquals(1, result.metrics().primitiveWork());
    }

    @Test void twoLearnedEmissionsGiveNativeMathematicsATurn() {
        try (var picker = new IncrementalMovePicker(List.of(registered(nativeFactory()), nativeProvider()),
                MovePriorityPolicy.INVENTORY_ORDER, MoveState.root("(a + 0) * (b + 0) * (c + 0)"), CONTEXT)) {
            assertEquals(SearchMove.SourceKind.LEARNED, picker.next(1000).orElseThrow().sourceKind());
            assertEquals(SearchMove.SourceKind.LEARNED, picker.next(1000).orElseThrow().sourceKind());
            assertEquals(MovePriorityPolicy.Stage.NORMAL_PRIMITIVE.ordinal(), picker.nextStage());
            assertEquals(SearchMove.SourceKind.PRIMITIVE, picker.next(1000).orElseThrow().sourceKind());
            assertEquals(3, picker.generatedMoves().size());
        }
    }

    @Test void arbitraryBatchProviderStillRequiresExplicitSupportedAdmission() {
        var batch = new EngineMoveProvider(descriptor(), engine(), true);
        assertThrows(IllegalArgumentException.class, () -> problem("a + 0", List.of(batch), 1000, replay()));
        assertThrows(IllegalArgumentException.class, () -> new IncrementalMovePicker(List.of(batch),
            MovePriorityPolicy.INVENTORY_ORDER, MoveState.root("a + 0"), CONTEXT));
    }

    private static MoveVerifier replay() {
        return (state, move, context) -> {
            var candidates = engine().transform(state.expression());
            return new MoveVerifier.Verification(candidates.contains(move.transformation()), 1 + candidates.size(),
                candidates.stream().map(Transformation::applicationKey).toList(), "independent-native-replay");
        };
    }

    private static MoveSearch.Result search(List<MoveProvider> providers, long budget, MoveVerifier verifier) {
        return new MoveSearch().search(problem("a + 0", providers, budget, verifier));
    }
    private static MoveSearch.Problem problem(String source, List<MoveProvider> providers, long budget, MoveVerifier verifier) {
        return new MoveSearch.Problem(source, CONTEXT, providers, MovePriorityPolicy.INVENTORY_ORDER, verifier,
            state -> state.searchDepth() * 100, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED_INCREMENTAL,
            new MoveSearch.Budget(3, 3, 100, 20, budget));
    }
}
