package de.regelsuche.search.moves;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.ast.*;
import de.regelsuche.retention.*;
import de.regelsuche.search.program.AstTransportObservation;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual export objects must survive in an owner while a later component can fail. */
class NativeResultExportOwnershipTest {
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static final Expr X = new VariableExpr("x_é_𐐀");
    private static final Expr SOURCE = new BinaryExpr(X, BinaryOperator.ADD, new NumberExpr(0));
    private static final String SOURCE_JSON = CODEC.encodeExpression(SOURCE);
    private static final String X_JSON = CODEC.encodeExpression(X);

    private enum Value implements NativeStateValue, RetainedGraph.View {
        INSTANCE;
        @Override public Assessment evaluate(TypedMoveSearch.State state, TypedMoveSearch.Context context) {
            return state.expression().equals(SOURCE)
                ? new Assessment(3, 1, 1, 0, Map.of("zero", new Capability("zero", SOURCE, "", SOURCE, X)))
                : Assessment.EMPTY;
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }
    }

    private static NativeMoveSearch.Result source() {
        var a = PatternExpr.var("A");
        var rule = new PatternRewriteRule("zero", PatternExpr.op(BinaryOperator.ADD, a, PatternExpr.num(0)), a);
        var descriptor = new MoveProvider.Descriptor("zero", "zero", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "result-export/v1");
        var provider = new NativeMoveSearch.Primitive(descriptor, new AstRewriteTransport(List.of(rule), 32, 32));
        var problem = new NativeMoveSearch.Problem(SOURCE,
            new TypedMoveSearch.Context(X, List.of("x != 0"), MoveContext.Phase.FROZEN_EVALUATION), List.of(provider),
            MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(2, 2, 0, 10, 10_000_000),
            NativeMovePriorityPolicy.INVENTORY_ORDER, NativeMoveSearch.ZeroScore.INSTANCE, Value.INSTANCE);
        var result = new NativeMoveSearch().search(problem, SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(MoveSearch.Outcome.TARGET_REACHED, result.observedOutcome());
        assertEquals(1, result.witness().size());
        return result;
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
            else if (value instanceof Map<?, ?> map) map.forEach((key, item) -> { visitor.reference(key); visitor.reference(item); });
            else if (value instanceof Collection<?> collection) collection.forEach(visitor::reference);
            else if (value instanceof Object[] array) for (var item : array) visitor.reference(item);
        }
        return seen;
    }

    private static boolean at(String type, String method) {
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
            frame.getClassName().equals(type) && frame.getMethodName().equals(method)));
    }
    private static final String PACKAGE = "de.regelsuche.search.moves.";
    private enum Boundary {
        STATE_METADATA, ASSESSMENT_VALUE, WITNESS_MOVE, WITNESS_VERIFICATION, EVENT_MOVE, EVENT_VERIFICATION;
        boolean active(Set<Object> live) {
            return switch (this) {
                case STATE_METADATA -> at(PACKAGE + "MoveState", "<init>");
                case ASSESSMENT_VALUE -> StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames.anyMatch(frame ->
                    frame.getClassName().equals(PACKAGE + "NativeMoveSearch") && frame.getMethodName().equals("export")
                    && frame.getDescriptor().startsWith("(Lde/regelsuche/search/moves/NativeStateValue$Assessment;")));
                case WITNESS_MOVE, EVENT_MOVE -> at(PACKAGE + "NativeSearchMove", "exportLegacy")
                    && hasWitness(live) == (this == EVENT_MOVE);
                case WITNESS_VERIFICATION, EVENT_VERIFICATION -> at(PACKAGE + "NativeVerification", "exportLegacy")
                    && hasWitness(live) == (this == EVENT_VERIFICATION);
            };
        }
        private static boolean hasWitness(Set<Object> live) {
            return live.stream().anyMatch(MoveSearch.WitnessStep.class::isInstance);
        }
    }

    private static final class Abort extends RuntimeException { }
    private static final class Probe implements RetainedOperation.Sink {
        NativeMoveSearch.Result source;
        RetainedOperation scope;
        RetainedJson.Scope json;
        Boundary stop;
        final Abort primary = new Abort();
        boolean repeatPrimaryOnClose;
        Set<Object> failed;
        final EnumSet<Boundary> encountered = EnumSet.noneOf(Boundary.class);
        final Set<RetainedOperation.Frame> frames = Collections.newSetFromMap(new IdentityHashMap<>());
        long work;
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(source); visitor.reference(scope); visitor.reference(json);
        }
        @Override public void executionWork(long units) {
            work = Math.addExact(work, units);
            if (failed != null && repeatPrimaryOnClose && units == 4) throw primary;
            if (stop != null && failed == null) {
                var live = graph(scope);
                if (stop.active(live)) { failed = live; throw primary; }
            }
        }
        @Override public void validationWork(long units) { fail("output must not repeat mathematical authorization"); }
        @Override public void checkpoint() {
            work = Math.addExact(work, RetainedGraph.measure(this).work());
            var live = graph(scope);
            for (var object : live) if (object instanceof RetainedOperation.Frame frame) frames.add(frame);
            if (stop == null) for (var boundary : Boundary.values()) {
                if (boundary.active(live)) { assertOwned(boundary, live); encountered.add(boundary); }
            }
        }
        void released() {
            assertEquals(0, RetainedGraph.measure(scope).retained().nodes());
            assertEquals(0, RetainedGraph.measure(json).retained().characters());
            for (var frame : frames) assertEquals(new RetainedGraph.Usage(0, 0, 4), RetainedGraph.measure(frame).retained());
        }
    }

    /** Exclude objects already transferred into completed aggregates: equal old copies cannot fill a handoff gap. */
    private static Set<Object> pending(Set<Object> live) {
        var done = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (var value : live) {
            if (value instanceof Map<?, ?> map) map.forEach((key, item) -> {
                if (key instanceof MoveState) done.addAll(graph(key));
            });
            else if (value instanceof MoveSearch.WitnessStep || value instanceof MoveSearch.Event) done.addAll(graph(value));
            else if (value instanceof Collection<?> collection) for (var item : collection)
                if (item instanceof MoveState) done.addAll(graph(item));
        }
        var result = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        result.addAll(live); result.removeAll(done); return result;
    }
    private static void assertOwned(Boundary boundary, Set<Object> live) {
        var temporary = pending(live);
        if (boundary == Boundary.STATE_METADATA) {
            assertTrue(temporary.stream().anyMatch(value -> value instanceof String text &&
                (text.equals(SOURCE_JSON) || text.equals(X_JSON))),
                "the completed expression JSON must overlap MoveState's fallible metadata construction");
            return;
        }
        long states = temporary.stream().filter(MoveState.class::isInstance).count();
        assertEquals(boundary == Boundary.ASSESSMENT_VALUE ? 1 : 2, states,
            boundary + " must own the actual newly exported state objects, not equivalent earlier map keys");
        if (boundary == Boundary.WITNESS_VERIFICATION || boundary == Boundary.EVENT_VERIFICATION)
            assertEquals(1, temporary.stream().filter(SearchMove.class::isInstance).count(),
                "the newly exported move must survive independent receipt export");
    }

    private static void abort(Boundary boundary, boolean repeatedClose) {
        var result = source(); var probe = new Probe(); probe.source = result; probe.stop = boundary;
        probe.repeatPrimaryOnClose = repeatedClose;
        var thrown = assertThrows(Abort.class, () -> {
            try (var scope = RetainedOperation.open(probe); var json = RetainedJson.open()) {
                probe.scope = scope; probe.json = json; result.projectLegacy();
            }
        });
        assertSame(probe.primary, thrown); assertNotNull(probe.failed, "requested actual export boundary must run");
        assertOwned(boundary, probe.failed); probe.released();
        assertTrue(probe.work > 0);
        assertFalse(RetainedOperation.isObserved()); assertFalse(RetainedJson.active());
    }
    @Test void completedJsonOverlapsFallibleStateMetadata() { abort(Boundary.STATE_METADATA, false); }
    @Test void assessmentStateOverlapsValueConstruction() { abort(Boundary.ASSESSMENT_VALUE, false); }
    @Test void witnessStatesOverlapMoveExport() { abort(Boundary.WITNESS_MOVE, false); }
    @Test void witnessMoveOverlapsVerificationExport() { abort(Boundary.WITNESS_VERIFICATION, false); }
    @Test void eventStatesOverlapMoveExport() { abort(Boundary.EVENT_MOVE, false); }
    @Test void eventMoveOverlapsVerificationExport() { abort(Boundary.EVENT_VERIFICATION, false); }
    @Test void repeatedCleanupKeepsPrimaryAbort() { abort(Boundary.WITNESS_VERIFICATION, true); }

    private static byte[] bytes(MoveSearch.Result result) throws Exception {
        var values = new TreeMap<String, Object>();
        result.stateAssessments().forEach((state, value) -> values.put(state.toString(), value));
        var row = new TreeMap<String, Object>();
        row.put("outcome", result.outcome()); row.put("complete", result.completeBoundedRelation());
        row.put("witness", result.witness()); row.put("events", result.events()); row.put("metrics", result.metrics());
        row.put("reached", result.reachedStates().stream().sorted(Comparator.comparing(MoveState::toString)).toList());
        row.put("dead", result.deadEndStates()); row.put("assessments", values); row.put("staged", result.stagedIncrementalExecution());
        return new ObjectMapper().writeValueAsBytes(row);
    }
    @Test void completeProjectionPreservesBytesOrderAndPublishedSearchCharge() throws Exception {
        var result = source(); var original = result.projectLegacy(); var expected = bytes(original);
        assertEquals("22c7a8db6725066c8c50a76d3220de3f896341a5f35f2c6f9105235f20961071",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)), "frozen pre-fix projection bytes");
        var counters = new EnumMap<AstTransportObservation.Operation, Long>(AstTransportObservation.Operation.class);
        try (var codec = AstTransportObservation.open()) {
            result.projectLegacy();
            for (var kind : AstTransportObservation.Operation.values()) counters.put(kind, codec.count(kind));
        }
        long searchWork = result.totalWork(); var searchAccounting = result.accounting(); long previous = -1;
        for (int repeat = 0; repeat < 2; repeat++) {
            var probe = new Probe(); probe.source = result; MoveSearch.Result projection;
            try (var codec = AstTransportObservation.open(); var scope = RetainedOperation.open(probe); var json = RetainedJson.open()) {
                probe.scope = scope; probe.json = json; projection = result.projectLegacy();
                for (var kind : AstTransportObservation.Operation.values()) assertEquals(counters.get(kind), codec.count(kind), kind.name());
            }
            assertEquals(original, projection); assertArrayEquals(expected, bytes(projection));
            assertEquals(EnumSet.allOf(Boundary.class), probe.encountered);
            if (previous >= 0) assertEquals(previous, probe.work, "repeat export pays the same work exactly once");
            previous = probe.work; probe.released();
            assertEquals(searchWork, result.totalWork()); assertSame(searchAccounting, result.accounting());
            assertFalse(result.accountingComplete()); assertFalse(result.withinBudget());
        }
    }
}
