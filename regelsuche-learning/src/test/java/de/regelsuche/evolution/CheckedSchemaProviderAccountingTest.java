package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CheckedSchemaProviderAccountingTest {
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static final String SINGLE = "(x+y)*(x-y)+y*y";
    private static final String MULTIPLE = "((a+b)*(a-b)+b*b)+((c+d)*(c-d)+d*d)";
    private static final TypedMoveSearch.Context CONTEXT = TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION);
    private static CheckedLearnedSchemaModel model;
    private static String selected;

    @BeforeAll static void learnAndRestore() {
        var formation = new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
        var learned = CheckedLearnedSchemaModel.learn(formation);
        model = CheckedLearnedSchemaModel.load(learned.toCanonicalJson(), learned.inventoryHash());
        selected = model.nativeProviders().getFirst().candidates(state(parse(SINGLE)), CONTEXT).moves().stream()
            .filter(move -> move.targetExpression().equals(parse("x^2"))).findFirst().orElseThrow().exportLegacy().transformation().rule();
    }

    @Test void normalReceiptsAndDeclaredOrderPreserveTheHistoricalProviderContract() throws Exception {
        var observations = new ArrayList<Observation>();
        for (String source : List.of(SINGLE, MULTIPLE, "x+17", "x/0"))
            observations.add(observe(source, model, source, model.bounds().maximumSchemas(), schemaIds()));
        observations.add(observe("missing-prerequisite", model.requiring(List.of("x > 0")), SINGLE,
            model.bounds().maximumSchemas(), schemaIds()));
        observations.add(observe("selected-schema", model, MULTIPLE, 1, Set.of(selected)));
        observations.add(observe("one-schema-per-site", model, MULTIPLE, 1, schemaIds()));
        var json = new ObjectMapper();
        try (var golden = getClass().getResourceAsStream("checked-schema-provider-v1-gold.json")) {
            assertNotNull(golden);
            assertEquals(json.readTree(golden), json.readTree(json.writeValueAsString(observations)),
                "frozen on production 6d76d790 before changing the provider ledger");
        }
    }

    @Test void sourceObserverArgumentFailureRemainsTechnical() { assertAbort(Abort.DOMAIN_ARGUMENT, false, false); }
    @Test void sourceObserverRuntimeFailureRetainsItsWork() { assertAbort(Abort.DOMAIN_RUNTIME, false, false); }
    @Test void sourceObserverErrorRetainsItsWork() { assertAbort(Abort.DOMAIN_ERROR, false, false); }
    @Test void astObserverArgumentFailureIsNotAnUnsupportedExpression() { assertAbort(Abort.AST_ARGUMENT, false, false); }
    @Test void astObserverErrorRetainsInvocationWork() { assertAbort(Abort.AST_ERROR, false, false); }
    @Test void lostPublishedBatchPaysItsEntireReceipt() { assertAbort(Abort.BATCH, false, false); }
    @Test void lostBatchOnFinalClosePaysItsEntireReceipt() { assertAbort(Abort.CLOSE, false, false); }
    @Test void repeatedCloseFailurePreservesPrimaryIdentity() { assertAbort(Abort.DOMAIN_ARGUMENT, true, false); }
    @Test void distinctCloseFailureRemainsSuppressed() { assertAbort(Abort.DOMAIN_ARGUMENT, true, true); }
    @Test void domainRejectionCannotHideALaterObserverFailure() { assertAbort(Abort.REJECT_OBSERVATION, false, false); }
    @Test void domainRejectionCannotHideALaterCloseFailure() { assertAbort(Abort.REJECT_CLOSE, false, false); }
    @Test void constructorPublishesTheActualPartialApplicationBeforeItsCopyDebit() { assertAbort(Abort.CONSTRUCTOR, false, false); }
    @Test void laterApplicationAbortRetainsCompletedMathematicsAndTheCandidateEvent() { assertAbort(Abort.SECOND_APPLICATION, false, false); }

    private static void assertAbort(Abort abort, boolean repeat, boolean distinct) {
        boolean invalid = abort == Abort.REJECT_OBSERVATION || abort == Abort.REJECT_CLOSE;
        Expr source = parse(invalid ? "x/0" : MULTIPLE);
        var received = state(source);
        var meter = new ProviderMeter(abort, received); meter.repeatClose = repeat;
        if (distinct) meter.closeFailure = new IllegalStateException("additional provider close failure");
        var provider = model.nativeProviders(1, Map.of(), Set.of(selected)).getFirst();
        try (var scope = de.regelsuche.retention.RetainedOperation.open(meter)) {
            meter.scope = scope;
            var failure = assertThrows(Throwable.class, () -> provider.candidates(received, CONTEXT));
            assertSame(meter.failure, failure);
            assertNotNull(meter.rootWork, "the actual caller ledger must be held before work begins");
            assertEquals(meter.rootWork.units, meter.afterFailure.getLast());
            assertEquals(List.of(4L, meter.rootWork.units), meter.afterRootRelease,
                "the actual root release is followed by exactly one settlement, even if their values coincide");
            if (distinct) assertTrue(List.of(failure.getSuppressed()).contains(meter.closeFailure));
            else assertEquals(0, failure.getSuppressed().length);
            if (abort == Abort.BATCH || abort == Abort.CLOSE)
                assertEquals(meter.finished.work().totalWorkUnitsV2(), meter.afterFailure.getLast(), "includes the actual 2+n event units");
            if (abort == Abort.CONSTRUCTOR) {
                assertTrue(meter.partialOwned, "the actual constructor object owns its copied substitution list before the debit");
                assertNotNull(meter.firstApplication); assertFalse(meter.firstApplication.done());
                assertThrows(IllegalStateException.class, meter.firstApplication::nativeResult);
            }
            if (abort == Abort.SECOND_APPLICATION) {
                assertTrue(meter.completedPrefix > 0); assertTrue(meter.partialWork.units > 0);
                assertEquals(meter.firstStart + meter.completedPrefix + 1, meter.firstPublished,
                    "first completed mathematical work plus its actual candidate event");
                assertEquals(meter.secondStart + meter.partialWork.units, meter.rootWork.units,
                    "the interrupted second attempt is included by the caller exactly once");
            }
        }
        assertEquals(0, de.regelsuche.retention.RetainedGraph.measure(meter.scope).retained().characters());
    }

    @Test void normalReturnedBatchDelegatesWithoutAlsoSettlingItsReceiptLocally() {
        for (String text : List.of(MULTIPLE, "x+17", "x/0")) {
            var received = state(parse(text)); var meter = new ProviderMeter(Abort.NONE, received);
            try (var scope = de.regelsuche.retention.RetainedOperation.open(meter)) {
                meter.scope = scope;
                var batch = model.nativeProviders(1, Map.of(), Set.of(selected)).getFirst().candidates(received, CONTEXT);
                assertSame(batch, meter.finished); assertEquals(batch.work().totalWorkUnitsV2(), meter.rootWork.units);
                assertEquals(List.of(4L), meter.afterBatch, "only the final owner close follows receipt observation");
            }
        }
    }

    enum Abort { NONE, AST_ARGUMENT, AST_ERROR, DOMAIN_ARGUMENT, DOMAIN_RUNTIME, DOMAIN_ERROR,
        BATCH, CLOSE, REJECT_OBSERVATION, REJECT_CLOSE, CONSTRUCTOR, SECOND_APPLICATION }
    private static final class ProviderMeter implements de.regelsuche.retention.RetainedOperation.Sink {
        final Abort abort; final TypedMoveSearch.State received; final Throwable failure;
        de.regelsuche.retention.RetainedOperation scope;
        CheckedSchemaSupport.Work rootWork, partialWork;
        CheckedSchemaMatcherPlan.ApplicationSteps firstApplication;
        NativeMoveProvider.Batch finished;
        boolean tripped, repeatClose, batchObserved, sawMatch, partialOwned, sawRejection, rootSeen;
        RuntimeException closeFailure;
        long firstStart, secondStart, completedPrefix, firstPublished;
        final List<Long> afterFailure = new ArrayList<>(), afterBatch = new ArrayList<>(), afterRootRelease = new ArrayList<>();
        ProviderMeter(Abort abort, TypedMoveSearch.State received) {
            this.abort = abort; this.received = received;
            failure = abort == Abort.AST_ERROR || abort == Abort.DOMAIN_ERROR ? new AssertionError("provider observer error")
                : abort == Abort.DOMAIN_RUNTIME ? new IllegalStateException("provider observer runtime failure")
                : new IllegalArgumentException("provider observer " + abort);
        }
        @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v) { v.reference(scope); }
        @Override public void validationWork(long units) {
            snapshot();
            if (!tripped && (abort == Abort.AST_ARGUMENT || abort == Abort.AST_ERROR)) trip();
            else executionWork(units);
        }
        @Override public void executionWork(long units) {
            if (rootSeen && !rootHeld()) afterRootRelease.add(units);
            if (tripped) {
                afterFailure.add(units);
                if (repeatClose && units == 4) { if (closeFailure != null) throw closeFailure; fail(); }
                return;
            }
            var current = snapshot();
            if (batchObserved) afterBatch.add(units);
            boolean domain = abort == Abort.DOMAIN_ARGUMENT || abort == Abort.DOMAIN_RUNTIME || abort == Abort.DOMAIN_ERROR;
            if ((domain && current.sourceGrowth) || (abort == Abort.BATCH && finished != null)
                    || (abort == Abort.CLOSE && batchObserved && units == 4)
                    || (abort == Abort.REJECT_CLOSE && sawRejection && units == 4)
                    || (abort == Abort.CONSTRUCTOR && sawMatch && units == 3 && (partialOwned || !current.outcome))
                    || (abort == Abort.SECOND_APPLICATION && current.second && partialWork.units > 0)) trip();
        }
        @Override public void checkpoint() {
            de.regelsuche.retention.RetainedGraph.measure(scope); snapshot();
            if (!tripped && abort == Abort.REJECT_OBSERVATION && sawRejection) trip();
            batchObserved |= finished != null;
        }
        private void trip() { tripped = true; fail(); }
        private void fail() { if (failure instanceof RuntimeException runtime) throw runtime; throw (Error) failure; }
        private Snapshot snapshot() {
            var seen = graph(scope); boolean sourceGrowth = false, outcome = false, second = false;
            for (Object owner : seen) if (owner instanceof de.regelsuche.retention.RetainedOperation.Frame frame) {
                for (Object ref : refs(frame)) if (ref instanceof Object[] values) {
                    var items = java.util.Arrays.asList(values);
                    var ledger = items.stream().filter(CheckedSchemaSupport.Work.class::isInstance).map(CheckedSchemaSupport.Work.class::cast).findFirst().orElse(null);
                    if (items.contains(received)) { rootWork = ledger; rootSeen = true; }
                    boolean domain = items.stream().anyMatch(java.util.ArrayDeque.class::isInstance);
                    if (domain && items.contains(received.expression())) {
                        if (rootWork == null) rootWork = ledger; // Observe the unfixed source ledger for RED.
                        sourceGrowth |= items.stream().anyMatch(item -> item instanceof java.util.ArrayDeque<?> queue && !queue.isEmpty())
                            && items.stream().anyMatch(this::currentAtSource);
                    }
                }
            }
            for (Object value : seen) {
                if (value instanceof de.regelsuche.transform.ExprMatcher.MatchOutcome match) { outcome = true; sawMatch |= match.matched(); }
                if (value instanceof NativeMoveProvider.Batch batch) finished = batch;
                if (value instanceof String[] reason && reason.length == 1 && reason[0] != null) sawRejection = true;
                if (value instanceof CheckedSchemaMatcherPlan.ApplicationSteps application) {
                    if (firstApplication == null) { firstApplication = application; firstStart = rootWork.units; }
                    partialOwned |= application.phase() == null;
                    if (application != firstApplication) {
                        second = true;
                        if (partialWork == null) {
                            partialWork = refs((de.regelsuche.retention.RetainedGraph.View) application).stream()
                                .filter(CheckedSchemaSupport.Work.class::isInstance).map(CheckedSchemaSupport.Work.class::cast).findFirst().orElseThrow();
                            secondStart = rootWork.units;
                        }
                    }
                }
                if (value instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof CheckedLearnedSchemaModel.VerifiedApplication verified
                        && firstPublished == 0) {
                    var data = (CheckedLearnedSchemaModel.ApplicationData) refs(verified).getFirst();
                    completedPrefix = data.applicationWork(); firstPublished = rootWork.units;
                }
            }
            return new Snapshot(sourceGrowth, outcome, second);
        }
        private boolean rootHeld() {
            for (Object owner : graph(scope)) if (owner instanceof de.regelsuche.retention.RetainedOperation.Frame frame)
                for (Object ref : refs(frame)) if (ref instanceof Object[] values)
                    for (Object value : values) if (value == received) return true;
            return false;
        }
        private boolean currentAtSource(Object value) {
            return value instanceof Object[] slot && slot.length == 1
                && slot[0] instanceof de.regelsuche.retention.RetainedGraph.View node
                && slot[0].getClass().getEnclosingClass() == CheckedSchemaSupport.class
                && slot[0].getClass().getSimpleName().equals("Node") && refs(node).getFirst() == received.expression();
        }
        private record Snapshot(boolean sourceGrowth, boolean outcome, boolean second) {}
    }
    private static List<Object> refs(de.regelsuche.retention.RetainedGraph.View view) {
        var values = new ArrayList<Object>();
        view.retainedReferences(new de.regelsuche.retention.RetainedGraph.Visitor() {
            @Override public void reference(Object value) { if (value != null) values.add(value); }
            @Override public void requireExact(Object value, Class<?> type) { assertEquals(type, value.getClass()); }
        });
        return values;
    }
    private static Set<Object> graph(Object root) {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
        var queue = new java.util.ArrayDeque<Object>(); if (root != null) queue.add(root);
        while (!queue.isEmpty()) {
            Object value = queue.remove(); if (!seen.add(value)) continue;
            if (value instanceof de.regelsuche.retention.RetainedGraph.View view) queue.addAll(refs(view));
            else if (value instanceof Object[] items) { for (Object item : items) if (item != null) queue.add(item); }
            else if (value instanceof java.util.Collection<?> items) { for (Object item : items) if (item != null) queue.add(item); }
            else if (value instanceof Map<?, ?> items) items.forEach((key, item) -> { if (key != null) queue.add(key); if (item != null) queue.add(item); });
        }
        return seen;
    }

    private static Observation observe(String id, CheckedLearnedSchemaModel checked, String text, int maximum, Set<String> included) {
        Expr source = parse(text);
        var legacy = checked.providers(maximum, Map.of(), included).getFirst()
            .candidates(MoveState.root(CODEC.encodeExpression(source)), MoveContext.frozen("unused"));
        var nativeBatch = checked.nativeProviders(maximum, Map.of(), included).getFirst().candidates(state(source), CONTEXT);
        assertEquals(legacy.work(), nativeBatch.work(), id);
        assertEquals(legacy.complete(), nativeBatch.complete(), id);
        assertEquals(legacy.moves(), nativeBatch.moves().stream().map(NativeSearchMove::exportLegacy).toList(), id);
        return new Observation(id, nativeBatch.work(), nativeBatch.complete(), nativeBatch.moves().stream().map(move ->
            new Candidate(move.exportLegacy().transformation().rule(), CODEC.encodeExpression(move.targetExpression()),
                move.generationCost(), move.proof().work())).toList());
    }
    private static Set<String> schemaIds() { return model.schemas().stream().map(CheckedLearnedSchemaModel.Schema::id).collect(java.util.stream.Collectors.toSet()); }
    private static Expr parse(String text) { return new ExpressionParser().parseExactTerm(text).expression(); }
    private static TypedMoveSearch.State state(Expr source) { return new TypedMoveSearch.State(source, 0, 0, "", List.of(), Set.of(), 0); }
    record Observation(String id, TransformationWorkMetrics work, boolean complete, List<Candidate> candidates) {}
    record Candidate(String rule, String target, long generationCost, ExecutionWork work) {}
}
