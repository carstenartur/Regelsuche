package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.VariableExpr;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class ResultAssemblyOwnershipTest {
    private static final class Limit extends IllegalArgumentException {}
    private static final class Probe implements RetainedOperation.Sink {
        RetainedOperation scope;
        RetainedGraph.View kernel;
        final Limit primary = new Limit();
        final Set<RetainedOperation.Frame> frames = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Set<Object>> observations = new ArrayList<>();
        Predicate<Set<Object>> failDebit, failObservation;
        boolean failed, repeatOnClose, resourceFailed, assembling, failAtResult, assessmentOwnedAtSourceCheck;
        long work, finalObservationWork;
        RuntimeException cleanup;
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); visitor.reference(kernel); }
        @Override public void executionWork(long units) {
            work += units;
            if (failed && units == 4 && repeatOnClose) {
                repeatOnClose = false;
                throw cleanup == null ? primary : cleanup;
            }
            if (!failed && failDebit != null && failDebit.test(graph(scope))) { failed = true; throw primary; }
        }
        @Override public void validationWork(long units) { fail("assembly must not repeat mathematical verification"); }
        @Override public void checkpoint() {
            RetainedGraph.measure(scope);
            var live = graph(scope);
            observations.add(live);
            for (var value : live) if (value instanceof RetainedOperation.Frame frame) frames.add(frame);
            if (live.stream().anyMatch(SearchExecution.Result.class::isInstance)) {
                if (failAtResult) resourceFailed = true;
                work += finalObservationWork;
                finalObservationWork = 0;
            }
            if (resourceFailed && !assembling) throw new SearchExecution.ResourceLimit();
            if (!failed && failObservation != null && failObservation.test(live)) { failed = true; throw primary; }
        }
        void assertReleased() {
            kernel = null;
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

    /** Models a native state whose ordinary hash performs paid, observable identity work. */
    private record State(String expression, int searchDepth, int primitiveDepth, String previousRule,
            List<String> assumptions, Set<String> capabilities, int complexityDebt)
            implements SearchExecution.Position<String>, RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(expression); visitor.reference(previousRule); visitor.reference(assumptions); visitor.reference(capabilities);
        }
        @Override public int hashCode() {
            RetainedOperation.work(1);
            RetainedOperation.checkpoint();
            return Objects.hash(expression, searchDepth, primitiveDepth, previousRule, assumptions, capabilities, complexityDebt);
        }
    }
    private record Capability(String sourceExpression, Probe probe) implements SearchExecution.Capability<String>, RetainedGraph.View {
        Capability(String sourceExpression) { this(sourceExpression, null); }
        @Override public String sourceExpression() {
            RetainedOperation.checkpoint();
            if (probe != null) probe.assessmentOwnedAtSourceCheck = graph(probe.scope).stream().anyMatch(value ->
                value instanceof Assessment assessment && assessment.capabilities().containsValue(this));
            return sourceExpression;
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(sourceExpression); visitor.reference(probe); }
    }
    private record Assessment(Map<String, Capability> capabilities) implements SearchExecution.Assessment<String>, RetainedGraph.View {
        @Override public int complexity() { return 1; }
        @Override public double value() { return 0; }
        @Override public long searchWork() { return 5; }
        @Override public long primitiveWork() { return 2; }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(capabilities); }
    }
    private record Verification(boolean accepted, long work) implements SearchExecution.Verification, RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {}
    }
    private record Move(String source, String targetExpression, Set<String> delta)
            implements SearchExecution.Edge<String, Move>, RetainedGraph.View {
        @Override public int primitiveStepCount() { return 1; }
        @Override public ExecutionWork executionWork() { return new ExecutionWork(1, 0, 0); }
        @Override public String ruleId() { return "move"; }
        @Override public String ruleFamily() { return "family"; }
        @Override public List<String> assumptions() { return List.of(); }
        @Override public Move withCapabilityDelta(Set<String> value) {
            RetainedOperation.checkpoint();
            return new Move(source, targetExpression, Set.copyOf(value));
        }
        @Override public void requireSource(String expression) { assertEquals(source, expression); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(source); visitor.reference(targetExpression); visitor.reference(delta); }
    }

    private record Inputs(List<SearchExecution.Step<State, Move, Verification>> witness,
            List<SearchExecution.Event<State, Move, Verification>> events, Set<State> reached,
            Map<State, Assessment> assessments, List<Object> receipts) {
        SearchExecution.Result<State, Move, Verification, Assessment> result() {
            return new SearchExecution.Result<>(MoveSearch.Outcome.TARGET_REACHED, witness, events, reached,
                new ArrayList<>(), new MoveSearch.Metrics(1, 1, 0, 0, 0, 0, 1, 1, 3, 7, 11, 1, 1, Map.of("family", 1L)),
                false, assessments, receipts, new ArrayList<>(), new ArrayList<>(assessments.keySet()), new ArrayList<>(reached));
        }
    }
    private static Inputs inputs() {
        var source = new State("source", 0, 0, "", List.of(), Set.of(), 0);
        var target = new State("goal", 1, 1, "move", List.of(), Set.of("child-capability"), 0);
        var move = new Move("source", "goal", Set.of("child-capability"));
        var verification = new Verification(true, 11);
        var step = new SearchExecution.Step<>(source, target, move, verification);
        var event = new SearchExecution.Event<>(source, target, move, MoveSearch.Decision.ENQUEUED, verification);
        return new Inputs(new ArrayList<>(List.of(step)), new ArrayList<>(List.of(event)),
            new LinkedHashSet<>(List.of(source, target)), new LinkedHashMap<>(Map.of(source, new Assessment(Map.of()),
                target, new Assessment(Map.of("child-capability", new Capability("goal"))))), new ArrayList<>(List.of("receipt")));
    }
    private static boolean copiedList(Set<Object> live, List<?> source) {
        return live.contains(source) && live.stream().anyMatch(value -> value instanceof List<?> list
            && list != source && list.size() == source.size() && !list.isEmpty() && list.getFirst() == source.getFirst());
    }

    @Test void earlierResultCopiesRemainOwnedWhileLaterCopiesHashPaidStateKeys() {
        var input = inputs();
        var probe = new Probe();
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            var result = input.result();
            assertTrue(probe.observations.stream().anyMatch(live -> copiedList(live, input.witness())
                && copiedList(live, input.events())), "witness/event copies must already be owned at later key checkpoints");
            assertTrue(probe.observations.stream().anyMatch(live -> live.contains(result) && live.contains(input.assessments())
                && live.contains(result.stateAssessments()) && live.contains(input.receipts()) && live.contains(result.pickerReceipts())));
            assertEquals(input.witness(), result.witness());
            assertNotSame(input.witness(), result.witness());
            assertThrows(UnsupportedOperationException.class, () -> result.witness().clear());
        }
        probe.assertReleased();
    }

    @Test void completedResultCopyIsObservedEvenWhenItsDebitAborts() {
        var input = inputs();
        var probe = new Probe();
        probe.failDebit = live -> copiedList(live, input.witness());
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            assertSame(probe.primary, assertThrows(Limit.class, input::result));
            assertTrue(probe.observations.stream().anyMatch(live -> copiedList(live, input.witness())));
        }
        probe.assertReleased();
    }

    @Test void resultCopyFailureKeepsItsPrimaryAcrossRepeatedOrDistinctCleanupFailure() {
        for (boolean distinct : List.of(false, true)) {
            var input = inputs();
            var probe = new Probe();
            probe.failObservation = live -> copiedList(live, input.events());
            probe.repeatOnClose = true;
            if (distinct) probe.cleanup = new IllegalStateException("assembly cleanup failed");
            try (var scope = RetainedOperation.open(probe)) {
                probe.scope = scope;
                assertSame(probe.primary, assertThrows(Limit.class, input::result));
                assertEquals(distinct ? List.of(probe.cleanup) : List.of(), List.of(probe.primary.getSuppressed()));
            }
            probe.assertReleased();
        }
    }

    @Test void nativeAssumptionCheckOwnsTheActualAvailableSetAndPaysRejectedLookups() {
        var context = TypedMoveSearch.Context.sourceOnly(List.of("initial != 0"), MoveContext.Phase.FROZEN_EVALUATION);
        var state = new TypedMoveSearch.State(new VariableExpr("x"), 0, 0, "", List.of("path != 0"), Set.of(), 0);
        var probe = new Probe();
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            assertTrue(NativeMoveProvider.carries(List.of("initial != 0", "path != 0"), state, context));
            assertFalse(NativeMoveProvider.carries(List.of("missing != 0"), state, context));
            assertTrue(probe.observations.stream().anyMatch(live -> live.stream().anyMatch(value ->
                value instanceof HashSet<?> set && set.containsAll(List.of("initial != 0", "path != 0")))));
            assertTrue(probe.work > 1);
        }
        probe.assertReleased();
    }

    @Test void nativeAvailableSetAllocationFailureStillObservesItsInitialContentsAndPrimary() {
        for (boolean distinct : List.of(false, true)) {
            var context = TypedMoveSearch.Context.sourceOnly(List.of("initial != 0"), MoveContext.Phase.FROZEN_EVALUATION);
            var state = new TypedMoveSearch.State(new VariableExpr("x"), 0, 0, "", List.of("path != 0"), Set.of(), 0);
            var probe = new Probe();
            Predicate<Set<Object>> initial = live -> live.stream().anyMatch(value -> value instanceof HashSet<?> set
                && set.contains("initial != 0") && !set.contains("path != 0"));
            probe.failDebit = initial;
            probe.repeatOnClose = true;
            if (distinct) probe.cleanup = new IllegalStateException("assumption cleanup failed");
            try (var scope = RetainedOperation.open(probe)) {
                probe.scope = scope;
                assertSame(probe.primary, assertThrows(Limit.class,
                    () -> NativeMoveProvider.carries(List.of("path != 0"), state, context)));
                assertTrue(probe.observations.stream().anyMatch(initial));
                assertEquals(distinct ? List.of(probe.cleanup) : List.of(), List.of(probe.primary.getSuppressed()));
            }
            probe.assertReleased();
        }
    }

    private static final TransformationWorkMetrics GENERATED = TransformationWorkMetrics.ZERO
        .withDelegatedMechanicalWork(7).withCandidateWork(new ExecutionWork(3, 0, 0));
    private static final class Environment implements SearchExecution.Environment<String, State, Move, Assessment, Verification>, RetainedGraph.View {
        final Probe probe;
        final boolean abortPull, targetIsSource;
        Environment(Probe probe, boolean abortPull, boolean targetIsSource) {
            this.probe = probe; this.abortPull = abortPull; this.targetIsSource = targetIsSource;
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(probe); }
        @Override public String source() { return "source"; }
        @Override public String goal() { return targetIsSource ? "source" : "goal"; }
        @Override public List<String> initialAssumptions() { return List.of(); }
        @Override public MoveSearch.Budget budget() { return new MoveSearch.Budget(2, 2, 0, 10, 1_000_000); }
        @Override public MoveSearch.Mode mode() { return MoveSearch.Mode.FAST; }
        @Override public MoveSearch.Scheduling scheduling() { return MoveSearch.Scheduling.STAGED_INCREMENTAL; }
        @Override public State state(String expression, int depth, int primitive, String previous, List<String> assumptions, Set<String> capabilities, int debt) {
            return new State(expression, depth, primitive, previous, assumptions, Set.copyOf(capabilities), debt);
        }
        @Override public Assessment inspect(State state) {
            return new Assessment(state.searchDepth() == 0 ? Map.of() : Map.of("child-capability", new Capability(state.expression(), probe)));
        }
        @Override public double score(State state) { return 0; }
        @Override public Verification verify(State state, Move move) { return new Verification(true, 11); }
        @Override public boolean carries(List<String> assumptions, State state) { return true; }
        @Override public void ownership(RetainedGraph.View root) { probe.kernel = root; }
        @Override public void checkpoint() { probe.checkpoint(); }
        @Override public long additionalWork() { return probe.work; }
        @Override public boolean ownershipComplete() { return !probe.resourceFailed; }
        // The narrow Environment contract brackets only finite result assembly.
        @Override public void beginResultAssembly() { probe.assembling = true; }
        @Override public void endResultAssembly() { probe.assembling = false; }
        @Override public SearchExecution.Picker<Move> picker(State state) { return new Picker(this); }
    }
    private static final class Picker implements SearchExecution.Picker<Move>, RetainedGraph.View {
        private final Environment environment;
        private final Move move = new Move("source", "goal", Set.of());
        private boolean emitted, closed;
        Picker(Environment environment) { this.environment = environment; }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(environment); visitor.reference(move); }
        @Override public Optional<Move> next() {
            if (environment.abortPull) {
                environment.probe.resourceFailed = true;
                throw new SearchExecution.ResourceLimit().paidGeneration(GENERATED);
            }
            if (emitted) return Optional.empty();
            emitted = true; return Optional.of(move);
        }
        @Override public TransformationWorkMetrics workMetrics() { return emitted ? GENERATED : TransformationWorkMetrics.ZERO; }
        @Override public List<Move> generatedMoves() { return emitted ? List.of(move) : List.of(); }
        @Override public boolean complete() { return true; }
        @Override public boolean incremental() { return true; }
        @Override public Object executionReceipt() { assertTrue(closed); return new SearchExecution.Expansion<>("receipt-source", true, List.of()); }
        @Override public void close() { closed = true; }
    }
    private static SearchExecution.Result<State, Move, Verification, Assessment> search(Environment environment) {
        return new MoveSearchKernel<String, State, Move, Assessment, Verification>()
            .search(environment, SearchContinuationContract.PATH_SENSITIVE, null);
    }

    @Test void repeatedResourceFailureDuringPaidKeyCopiesStillReturnsTheAttemptAndItsCompletedWork() {
        var probe = new Probe();
        var environment = new Environment(probe, true, false);
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            var result = assertDoesNotThrow(() -> search(environment));
            assertEquals(MoveSearch.Outcome.INCONCLUSIVE, result.outcome());
            assertFalse(result.completeBoundedRelation());
            assertTrue(result.witness().isEmpty());
            assertEquals(5, result.metrics().primitiveWork(), "root assessment plus completed aborted generation, once each");
            assertEquals(13, result.metrics().searchWork());
            assertEquals(1, result.pickerReceipts().size());
            assertEquals(-1, result.metrics().firstHitDepth());
            assertFalse(probe.assembling);
        }
        probe.assertReleased();
    }

    @Test void finalResultObservationFailureCannotLeaveSuccessfulKernelFlags() {
        var probe = new Probe();
        probe.failAtResult = true;
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            var result = assertDoesNotThrow(() -> search(new Environment(probe, false, true)));
            assertEquals(MoveSearch.Outcome.INCONCLUSIVE, result.outcome());
            assertFalse(result.completeBoundedRelation());
            assertEquals(-1, result.metrics().firstHitDepth());
        }
        probe.assertReleased();
    }

    @Test void workFirstChargedAtResultHandoffCannotLeaveATargetSuccess() {
        var probe = new Probe();
        probe.finalObservationWork = 1_000_001;
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            var result = search(new Environment(probe, false, true));
            assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, result.outcome());
            assertFalse(result.completeBoundedRelation());
            assertEquals(-1, result.metrics().firstHitDepth());
        }
        probe.assertReleased();
    }

    @Test void admissionDeltaAndIncrementalReceiptCopiesKeepActualOwners() {
        var probe = new Probe();
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            var result = search(new Environment(probe, false, false));
            assertEquals(MoveSearch.Outcome.TARGET_REACHED, result.outcome());
            assertEquals(1, result.witness().size());
            assertEquals(Set.of("child-capability"), result.witness().getFirst().move().delta());
            assertTrue(probe.assessmentOwnedAtSourceCheck, "the completed assessment must be owned before its source validation can checkpoint");
            assertTrue(probe.observations.stream().anyMatch(live -> live.stream().anyMatch(value ->
                value.getClass().getSimpleName().equals("Admission")) && live.stream().anyMatch(value ->
                value instanceof HashSet<?> delta && delta.contains("child-capability"))),
                "admission and the actual mutable capability delta overlap move rewrapping");
            assertTrue(probe.observations.stream().anyMatch(live -> live.stream().anyMatch(value ->
                value instanceof ArrayList<?> receipts && receipts.size() == 1 && receipts.getFirst() instanceof SearchExecution.Expansion<?>)
                && live.contains(result.pickerReceipts())), "mutable receipts overlap their immutable result copy");
        }
        probe.assertReleased();
    }
}
