package de.regelsuche.search.moves;

import static de.regelsuche.search.moves.IncrementalProviderContract.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.VariableExpr;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class FinalProducerOwnershipTest {
    private static final class Limit extends IllegalArgumentException {}
    private static final class Probe implements RetainedOperation.Sink {
        RetainedOperation scope;
        final Limit primary = new Limit();
        final Set<RetainedOperation.Frame> frames = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Set<Object>> observations = new ArrayList<>();
        Predicate<Set<Object>> fail;
        RuntimeException cleanup;
        boolean failed, closeFailed;
        long work;
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); }
        @Override public void executionWork(long units) {
            work += units;
            if (failed && !closeFailed && units == 4) { closeFailed = true; throw cleanup == null ? primary : cleanup; }
            if (!failed && fail != null && fail.test(graph(scope))) { failed = true; throw primary; }
        }
        @Override public void validationWork(long units) { fail("metadata cannot perform mathematical verification"); }
        @Override public void checkpoint() {
            RetainedGraph.measure(scope);
            var live = graph(scope);
            observations.add(live);
            for (var value : live) if (value instanceof RetainedOperation.Frame frame) frames.add(frame);
        }
        void released() {
            assertEquals(0, RetainedGraph.measure(scope).retained().characters());
            for (var frame : frames) assertEquals(new RetainedGraph.Usage(0, 0, 4), RetainedGraph.measure(frame).retained());
        }
    }
    private static Set<Object> graph(Object root) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        var pending = new ArrayDeque<Object>();
        var visitor = new RetainedGraph.Visitor() {
            @Override public void reference(Object value) { if (value != null) pending.addLast(value); }
            @Override public void requireExact(Object value, Class<?> type) { assertEquals(type, value.getClass()); }
        };
        visitor.reference(root);
        while (!pending.isEmpty()) {
            var value = pending.removeFirst();
            if (!seen.add(value)) continue;
            if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
            else if (value instanceof Map<?, ?> map) map.forEach((key, entry) -> { visitor.reference(key); visitor.reference(entry); });
            else if (value instanceof Collection<?> collection) collection.forEach(visitor::reference);
            else if (value instanceof Object[] array) for (var item : array) visitor.reference(item);
        }
        return seen;
    }
    private static boolean mutableListOf(Set<Object> live,Class<?> type,int size) {
        return live.stream().anyMatch(value -> value instanceof ArrayList<?> list && list.size() == size
            && !list.isEmpty() && type.isInstance(list.getFirst()));
    }
    private static boolean overlap(Set<Object> live,List<?> copy) {
        return live.contains(copy) && live.stream().anyMatch(value -> value instanceof ArrayList<?> source
            && source != copy && source.size() == copy.size() && !source.isEmpty() && source.getFirst() == copy.getFirst());
    }
    private static MoveWitnessPath<String,String,String> path() {
        return MoveWitnessPath.<String,String,String>root()
            .append(new SearchExecution.Step<>("s", "a", "first", "valid-first"))
            .append(new SearchExecution.Step<>("a", "b", "second", "valid-second"));
    }
    @Test void witnessMaterializationOwnsScratchAndImmutableCopyInPathOrder() {
        var path = path(); var probe = new Probe();
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            var steps = path.steps();
            assertEquals(List.of("first", "second"), steps.stream().map(SearchExecution.Step::move).toList());
            assertTrue(probe.observations.stream().anyMatch(live -> live.contains(path) && overlap(live, steps)));
            assertThrows(UnsupportedOperationException.class, steps::clear);
        }
        probe.released();
    }
    @Test void failedWitnessAllocationStillObservesItsCompletedContentsAndPrimary() {
        var path = path();
        failed(live -> mutableListOf(live, SearchExecution.Step.class, 2), path::steps);
    }

    private record Provider(MoveProvider.Descriptor descriptor) implements NativeMoveProvider, StagedIncrementalLanes.Source<String> {
        @Override public Mathematics mathematicalKind() { return Mathematics.PRIMITIVE; }
        @Override public boolean batch() { return true; }
        @Override public boolean nativeTransport() { return true; }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(descriptor); }
        @Override public ObjectCursor<String> open(java.util.function.Consumer<List<String>> ignored,java.util.function.LongSupplier work) {
            return new Cursor(NativeIncrementalSources.definition(this));
        }
    }
    private static final class Cursor implements ObjectCursor<String>,RetainedGraph.View {
        private final Definition definition;
        private boolean closed;
        Cursor(Definition definition) { this.definition = definition; }
        @Override public Optional<String> next(long allowance) { return Optional.empty(); }
        @Override public Snapshot snapshot() {
            RetainedOperation.checkpoint();
            return new Snapshot(definition, Status.EXHAUSTED, closed, false, true, new Work(Map.of(), ExecutionWork.ZERO), 0, "complete");
        }
        @Override public void close() { closed = true; }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(definition); }
    }
    private enum Ranking implements SearchBatches.Ranking<String> { INSTANCE;
        @Override public double score(String move) { return 0; }
        @Override public int stage(MoveProvider.Descriptor descriptor) { return 0; }
        @Override public double providerScore(MoveProvider.Descriptor descriptor) { return 0; }
        @Override public long contextWork() { return 0; }
        @Override public void requireSource(String move) {}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {}
    }
    private static Provider provider(String id) {
        return new Provider(new MoveProvider.Descriptor(id, "family", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.VERIFIED, List.of(), SearchMove.ValueEvidence.UNKNOWN, "model"));
    }
    private static StagedIncrementalLanes<String,String> lanes() {
        var lanes = new StagedIncrementalLanes<String,String>(List.of(provider("first"), provider("second")), Ranking.INSTANCE, "source");
        assertTrue(lanes.next(10_000).isEmpty());
        lanes.close();
        return lanes;
    }
    @Test void laneReceiptOwnsActualSnapshotsMutableListImmutableCopyAndExpansion() {
        var lanes = lanes(); var probe = new Probe();
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            var receipt = lanes.receipt();
            assertTrue(receipt.closed());
            assertEquals(List.of(0, 1), receipt.lanes().stream().map(StagedIncrementalMoveExecution.Lane::providerIndex).toList());
            assertTrue(receipt.lanes().stream().allMatch(lane -> lane.cursor().closed()));
            assertTrue(probe.observations.stream().anyMatch(live -> live.contains(lanes) && live.contains(receipt)
                && overlap(live, receipt.lanes())));
        }
        probe.released();
    }
    @Test void expansionConstructorObservesCallerListCopyAndCompletedValue() {
        var input = new ArrayList<>(lanes().receipt().lanes()); var probe = new Probe();
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            var expansion = new SearchExecution.Expansion<>("source", true, input);
            assertTrue(probe.observations.stream().anyMatch(live -> live.contains(expansion) && live.contains(input)
                && live.contains(expansion.lanes())));
            assertNotSame(input, expansion.lanes());
        }
        probe.released();
    }
    @Test void failedLaneReceiptAndExpansionCopyKeepTheirPrimaryAndReleaseOwners() {
        var lanes = lanes();
        failed(live -> mutableListOf(live, StagedIncrementalMoveExecution.Lane.class, 1), lanes::receipt);
        var input = new ArrayList<>(lanes.receipt().lanes());
        failed(live -> live.contains(input) && live.stream().anyMatch(value -> value instanceof List<?> copy && copy != input
            && copy.size() == input.size() && copy.getFirst() == input.getFirst()), () -> new SearchExecution.Expansion<>("source", true, input));
    }
    private enum RejectingVerifier implements NativeVerifier,RetainedGraph.View { INSTANCE;
        @Override public NativeVerification verify(TypedMoveSearch.State source,NativeSearchMove move,TypedMoveSearch.Context context) {
            throw new AssertionError("metadata fixture must never verify a move");
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {}
    }
    private enum Objective implements TypedSourceOnlySearch.Objective,RetainedGraph.View { INSTANCE;
        @Override public TypedSourceOnlySearch.Score evaluate(TypedMoveSearch.State state) { return new TypedSourceOnlySearch.Score(0, 0); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {}
    }
    private static NativeMoveSearch.Problem problem() {
        var source = new VariableExpr("source");
        return new NativeMoveSearch.Problem(source, TypedMoveSearch.Context.frozen(source), List.of(provider("first"), provider("second")),
            MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED_INCREMENTAL, new MoveSearch.Budget(2, 2, 0, 10, 10_000_000),
            NativeMovePriorityPolicy.INVENTORY_ORDER, NativeMoveSearch.ZeroScore.INSTANCE, NativeStateValue.NONE, RejectingVerifier.INSTANCE);
    }
    private static SearchExecution.Result<TypedMoveSearch.State,NativeSearchMove,NativeVerification,NativeStateValue.Assessment> emptyResult() {
        return new SearchExecution.Result<>(MoveSearch.Outcome.INCONCLUSIVE, List.of(), List.of(), Set.of(), List.of(),
            new MoveSearch.Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, Map.of()), false, Map.of(), List.of(), List.of(), List.of(), List.of());
    }
    private static NativeMoveSearch.Result nativeResult(NativeMoveSearch.Problem problem,SearchExecution.Result<?,?,?,?> result) {
        try {
            var constructor = NativeMoveSearch.Result.class.getDeclaredConstructor(NativeMoveSearch.Problem.class, SearchExecution.Result.class,
                long.class, boolean.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(problem, result, 0, false, false);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    @Test void nativeProviderInventoryOwnsDefinitionsWrappersCopiesAndFinalResult() {
        var problem = problem(); var searched = emptyResult(); var probe = new Probe();
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            var result = nativeResult(problem, searched);
            assertTrue(probe.observations.stream().anyMatch(live -> live.contains(result)
                && mutableListOf(live, StagedIncrementalMoveExecution.Provider.class, 2)
                && live.stream().anyMatch(value -> value instanceof List<?> copy && !(value instanceof ArrayList<?>)
                    && copy.size() == 2 && copy.getFirst() instanceof StagedIncrementalMoveExecution.Provider && overlap(live, copy))));
            assertTrue(probe.observations.stream().anyMatch(live -> live.stream().anyMatch(Definition.class::isInstance)
                && !live.stream().anyMatch(StagedIncrementalMoveExecution.Provider.class::isInstance)));
        }
        probe.released();
    }
    @Test void failedNativeProviderInventoryStillObservesActualCopyAndPrimary() {
        var problem = problem(); var searched = emptyResult();
        failed(live -> mutableListOf(live, StagedIncrementalMoveExecution.Provider.class, 2), () -> nativeResult(problem, searched));
    }
    @Test void qualityWitnessCopyOwnsInputCopyAndCompletedResultAndPreservesFailure() {
        var problem = problem(); var searched = emptyResult(); var nativeResult = nativeResult(problem, searched);
        // An attempted metadata record confers no proof authority.
        var input = new ArrayList<SearchExecution.Step<TypedMoveSearch.State,NativeSearchMove,NativeVerification>>();
        input.add(new SearchExecution.Step<>(null, null, null, null));
        var probe = new Probe();
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            var quality = new NativeMoveSearch.QualityResult(nativeResult, null, 0, 0, input, 0, 100);
            assertTrue(probe.observations.stream().anyMatch(live -> live.contains(quality) && live.contains(input)
                && live.contains(quality.witness())));
        }
        probe.released();
        failed(live -> live.contains(input) && live.stream().anyMatch(value -> value instanceof List<?> copy && copy != input
            && copy.size() == 1 && copy.getFirst() == input.getFirst()),
            () -> new NativeMoveSearch.QualityResult(nativeResult, null, 0, 0, input, 0, 100));
    }
    @Test void alreadyAbortedNativeSearchStillReturnsItsFiniteProviderInventory() {
        var result = assertDoesNotThrow(() -> new NativeMoveSearch().search(problem(), SearchContinuationContract.PATH_SENSITIVE,
            new SearchExpressionStore.Limits(1, 1, 1, 0)));
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE, result.observedOutcome());
        assertFalse(result.observationsComplete());
        assertTrue(result.accounting().work() > 0);
        assertFalse(result.withinBudget());
        var original = problem();
        var sourceOnly = new NativeMoveSearch.Problem(original.source(),
            TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION), original.providers(), original.mode(),
            original.scheduling(), original.budget(), original.policy(), original.stateScore(), original.stateValue(), original.verifier());
        var quality = assertDoesNotThrow(() -> new NativeMoveSearch().searchUntil(sourceOnly, Objective.INSTANCE, 0,
            SearchContinuationContract.PATH_SENSITIVE, new SearchExpressionStore.Limits(1, 1, 1, 0)));
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE, quality.search().observedOutcome());
        assertFalse(quality.hasIncumbent());
        assertFalse(quality.withinBudget());
    }
    private static void failed(Predicate<Set<Object>> fail,Runnable action) {
        for (boolean distinct : List.of(false, true)) {
            var probe = new Probe(); probe.fail = fail;
            if (distinct) probe.cleanup = new IllegalStateException("producer cleanup failed");
            try (var scope = RetainedOperation.open(probe)) {
                probe.scope = scope;
                assertSame(probe.primary, assertThrows(Limit.class, action::run));
                assertTrue(probe.observations.stream().anyMatch(fail));
                assertEquals(distinct ? List.of(probe.cleanup) : List.of(), List.of(probe.primary.getSuppressed()));
            }
            probe.released();
        }
    }
}
