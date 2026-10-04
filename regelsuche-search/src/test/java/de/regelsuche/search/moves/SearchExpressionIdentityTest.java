package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.symbol.*;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class SearchExpressionIdentityTest {
    @Test void anEqualIndexHitPaysForItsStructuralTraversal() {
        try (var store = new SearchExpressionStore(SearchExpressionStore.Limits.DEFAULT)) {
            var expected = store.intern(chain(128));
            long before = store.work();
            assertSame(expected, store.intern(chain(128)));
            assertTrue(store.work() - before >= 257, "a hit must pay for the 257 expression nodes it inspects");
        }
    }

    @Test void deepIndependentTreesDoNotUseRecursiveHashingOrEquality() {
        try (var store = new SearchExpressionStore(SearchExpressionStore.Limits.DEFAULT)) {
            var expected = assertDoesNotThrow(() -> store.intern(chain(12_000)));
            assertSame(expected, assertDoesNotThrow(() -> store.intern(chain(12_000))));
        }
    }

    @Test void sharedDagsAreComparedWithoutExpandingAllOccurrences() {
        try (var store = new SearchExpressionStore(SearchExpressionStore.Limits.DEFAULT)) {
            var expected = store.intern(dag(16));
            long before = store.work();
            assertSame(expected, store.intern(dag(16)));
            long work = store.work() - before;
            assertTrue(work >= 17, "distinct DAG nodes are real work");
            assertTrue(work < 2000, "memoized identity must not expand the 131071 occurrences");
        }
    }

    @Test void nameAndExactScalarSizeContributeToIndexWork() {
        assertTrue(hitWork(new VariableExpr("x".repeat(1000)), new VariableExpr("x".repeat(1000)))
            > hitWork(new VariableExpr("x"), new VariableExpr("x")) + 1000);
        var large = BigInteger.ONE.shiftLeft(8192).add(BigInteger.ONE);
        assertTrue(hitWork(new NumberExpr(new ExactRational(large, BigInteger.ONE)),
            new NumberExpr(new ExactRational(new BigInteger(large.toByteArray()), BigInteger.ONE)))
            > hitWork(NumberExpr.exact("123"), NumberExpr.exact("123")) + 1000);
    }

    @Test void disabledIndexDoesNotHashDeepExpressionsOrRetainIndexEntries() {
        try (var store = new SearchExpressionStore(new SearchExpressionStore.Limits(100_000, 100_000, 1_000_000, 0))) {
            var expression = chain(12_000);
            var first = assertDoesNotThrow(() -> store.intern(expression));
            long before = store.work();
            assertNotSame(first, store.intern(expression), "disabled index does not deduplicate roots");
            assertTrue(store.work() - before < 20, "already owned nodes need no structural index traversal");
            assertEquals(0, store.statistics().indexEntries());
        }
    }

    @Test void collidingBucketEvictionIsFifoAndKeepsLiveReferences() {
        try (var store = new SearchExpressionStore(new SearchExpressionStore.Limits(100, 1000, 1000, 2))) {
            var a = store.intern(new VariableExpr("AaAa"));
            var b = store.intern(new VariableExpr("BBBB"));
            assertSame(a, store.intern(new VariableExpr("AaAa")), "a hit must not reorder FIFO");
            var c = store.intern(new VariableExpr("AaBB"));
            assertSame(b, store.intern(new VariableExpr("BBBB")));
            assertSame(c, store.intern(new VariableExpr("AaBB")));
            assertNotSame(a, store.intern(new VariableExpr("AaAa")), "oldest colliding entry was evicted");
            assertEquals(new VariableExpr("AaAa"), store.dereference(a));
            assertEquals(4, store.statistics().liveNodes());
            assertEquals(2, store.statistics().evictions());
        }
    }

    @Test void rejectedFinalObservationRestoresOwnershipAndFifoIndex() {
        for (var names : List.of(List.of("AaAa", "BBBB", "AaBB"), List.of("a", "b", "c"))) {
            for (int capacity : List.of(0, 1, 2, 3)) {
                try (var store = new SearchExpressionStore(new SearchExpressionStore.Limits(100, 1000, 1000, capacity))) {
                    var first = store.intern(new VariableExpr(names.get(0)));
                    var second = store.intern(new VariableExpr(names.get(1)));
                    var before = store.statistics();
                    long workBefore = store.work();
                    var sink = new IndexSink(store);
                    try (var operation = RetainedOperation.open(sink)) {
                        sink.operation = operation;
                        sink.abortAtLiveNodes = before.liveNodes() + 1;
                        assertSame(sink.failure, assertThrows(IndexAbort.class,
                            () -> store.intern(new VariableExpr(names.get(2)))));
                        assertEquals(before, store.statistics(), "failed observation must restore all statistics");
                        assertTrue(store.work() > workBefore, "failed work remains paid");
                        assertEquals(before.liveNodes(), RetainedGraph.measure(sink).retained().nodes(),
                            "failed ownership and scratch must be released");
                    } finally { sink.operation = null; }
                    assertEquals(new VariableExpr(names.get(0)), store.dereference(first));
                    if (capacity >= 2) assertSame(first, store.intern(new VariableExpr(names.get(0))));
                    if (capacity >= 1) assertSame(second, store.intern(new VariableExpr(names.get(1))));
                    var third = store.intern(new VariableExpr(names.get(2)));
                    assertEquals(before.liveNodes() + 1, store.statistics().liveNodes(),
                        "retry must acquire the rejected expression's nodes");
                    assertEquals(before.liveCharacters() + names.get(2).length(), store.statistics().liveCharacters(),
                        "retry must acquire the rejected expression's text");
                    assertEquals(before.evictions() + (capacity == 1 || capacity == 2 ? 1 : 0),
                        store.statistics().evictions());
                    if (capacity > 0) assertSame(third, store.intern(new VariableExpr(names.get(2))));
                    if (capacity == 2) {
                        assertSame(second, store.intern(new VariableExpr(names.get(1))));
                        assertNotSame(first, store.intern(new VariableExpr(names.get(0))),
                            "retry must evict the original oldest entry");
                    }
                }
            }
        }
    }

    @Test void sharedLeftNodeMustStillCheckEachDifferentRightNodeAfterAHashCollision() {
        try (var store = new SearchExpressionStore(SearchExpressionStore.Limits.DEFAULT)) {
            var shared = new VariableExpr("Aa");
            var first = new FunctionExpr("f", List.of(shared, shared));
            var equal = new FunctionExpr("f", List.of(new VariableExpr("Aa"), new VariableExpr("Aa")));
            var unequal = new FunctionExpr("f", List.of(new VariableExpr("Aa"), new VariableExpr("BB")));
            assertEquals(SearchExpressionIdentity.hash(store, first), SearchExpressionIdentity.hash(store, unequal));
            assertSame(store.intern(first), store.intern(equal));
            assertNotSame(store.intern(first), store.intern(unequal));
        }
    }

    @Test void scalarEncodingBuffersAreObservedAndReleased() {
        var large = BigInteger.ONE.shiftLeft(8192).add(BigInteger.ONE);
        var first = new NumberExpr(new ExactRational(large, BigInteger.ONE));
        var second = new NumberExpr(new ExactRational(new BigInteger(large.toByteArray()), BigInteger.ONE));
        long scalarCharacters = RetainedGraph.measure(List.of(first, second)).retained().characters();
        try (var store = new SearchExpressionStore(SearchExpressionStore.Limits.DEFAULT)) {
            var sink = new IndexSink(store);
            try (var operation = RetainedOperation.open(sink)) {
                sink.operation = operation;
                assertTrue(SearchExpressionIdentity.same(store, first, second));
                assertTrue(sink.peak.characters() >= scalarCharacters + 2L * large.toByteArray().length,
                    "both actual encoding buffers overlap the compared scalars");
                assertEquals(0, RetainedGraph.measure(sink).retained().characters(), "completed scratch is released");
                assertEquals(0, store.statistics().liveNodes(), "comparison alone acquires no store ownership");
            } finally { sink.operation = null; }
        }
    }

    @Test void cancelledIndexWorkIsObservedPaidAndDoesNotMutateOwnership() {
        try (var store = new SearchExpressionStore(SearchExpressionStore.Limits.DEFAULT)) {
            var expected = store.intern(chain(20));
            var before = store.statistics();
            long workBefore = store.work();
            var sink = new IndexSink(store);
            try (var operation = RetainedOperation.open(sink)) {
                sink.operation = operation;
                sink.abort = true;
                var failure = assertThrows(IndexAbort.class, () -> store.intern(chain(20)));
                assertSame(sink.failure, failure, "cleanup must not replace the original failure by self-suppression");
                sink.abort = false;
                assertTrue(sink.peak.nodes() > before.liveNodes(), "scratch owns the independent lookup tree");
                assertTrue(store.work() > workBefore, "failed lookup work remains paid");
                assertEquals(before, store.statistics(), "no ownership mutation before lookup succeeds");
                assertSame(expected, store.intern(chain(20)), "the session remains usable after cancellation");
            } finally { sink.operation = null; }
        }
    }

    @Test void repeatedScalarIdentitiesDoNotMultiplyEncodingStorageOrHashWork() {
        var integer = BigInteger.ONE.shiftLeft(8192).add(BigInteger.ONE);
        var rational = new ExactRational(integer, BigInteger.ONE);
        var arguments = new ArrayList<Expr>();
        for (int i = 0; i < 256; i++) arguments.add(new NumberExpr(rational));
        var expression = new FunctionExpr("f", arguments);
        long sourceCharacters = RetainedGraph.measure(expression).retained().characters();
        try (var store = new SearchExpressionStore(SearchExpressionStore.Limits.DEFAULT)) {
            var sink = new IndexSink(store);
            try (var operation = RetainedOperation.open(sink)) {
                sink.operation = operation;
                SearchExpressionIdentity.hash(store, expression);
                assertTrue(sink.peak.characters() <= sourceCharacters + integer.toByteArray().length + 1,
                    "only one encoding per scalar identity may be retained");
                assertTrue(store.work() < 20_000, "the shared scalar hash must also be reused");
                assertEquals(0, RetainedGraph.measure(sink).retained().characters());
            } finally { sink.operation = null; }
        }
    }

    @Test void differentlySharedDagsEnforceScratchBoundsBeforeExpandingAllPairs() {
        var expressions = differentlySharedDags(5);
        var limits = new SearchExpressionStore.Limits(10_000, 100_000, 6_000, 1);
        long sourceReferences = RetainedGraph.measure(expressions).retained().references();
        assertTrue(sourceReferences < limits.references());
        try (var store = new SearchExpressionStore(limits)) {
            var sink = new IndexSink(store);
            try (var operation = RetainedOperation.open(sink)) {
                sink.operation = operation;
                assertThrows(SearchExpressionStore.LimitExceeded.class,
                    () -> SearchExpressionIdentity.same(store, expressions.get(0), expressions.get(1)));
                assertTrue(sink.peak.references() >= limits.references(), "rejected scratch must be observed before release");
                assertTrue(sink.peak.references() < limits.references() + sourceReferences + 100,
                    "scratch must stop at its local limit, not after the whole pair graph");
                assertTrue(store.work() > 0);
                assertEquals(0, store.statistics().liveNodes());
                assertEquals(0, RetainedGraph.measure(sink).retained().nodes());
            } finally { sink.operation = null; }
            assertThrows(SearchExpressionStore.LimitExceeded.class,
                () -> SearchExpressionIdentity.same(store, expressions.get(0), expressions.get(1)),
                "the local bound must also work without an observer");
        }
    }

    @Test void cancellationAfterPopulatingHashOrComparisonScratchPreservesFailureAndOwnership() {
        assertPopulatedCancellation(false);
        assertPopulatedCancellation(true);
    }

    private static void assertPopulatedCancellation(boolean comparison) {
        try (var store = new SearchExpressionStore(SearchExpressionStore.Limits.DEFAULT)) {
            var expected = store.intern(chain(20));
            var before = store.statistics();
            long workBefore = store.work();
            var sink = new IndexSink(store);
            try (var operation = RetainedOperation.open(sink)) {
                sink.operation = operation;
                sink.abortAtCheckpoint = 2;
                var failure = assertThrows(IndexAbort.class, () -> {
                    if (comparison) SearchExpressionIdentity.same(store, chain(20), chain(20));
                    else store.intern(chain(20));
                });
                assertSame(sink.failure, failure);
                assertTrue(sink.peak.references() > sink.firstReferences + 100, "cancellation observes populated scratch");
                assertTrue(store.work() > workBefore + 100, "completed traversal and cleanup are paid");
                assertEquals(before, store.statistics());
                assertEquals(before.liveNodes(), RetainedGraph.measure(sink).retained().nodes(), "temporary trees are released");
                sink.abortAtCheckpoint = Integer.MAX_VALUE;
                assertSame(expected, store.intern(chain(20)));
            } finally { sink.operation = null; }
        }
    }

    private static List<Expr> differentlySharedDags(int depth) {
        var leaves = new ArrayList<Expr>();
        for (int i = 0; i < 1 << depth; i++) leaves.add(dag(depth));
        Expr left = balancedTree(leaves);
        leaves.clear();
        for (int i = 0; i < 1 << depth; i++) leaves.add(new VariableExpr("x"));
        Expr right = balancedTree(leaves);
        for (int i = 0; i < depth; i++) right = new BinaryExpr(right, BinaryOperator.ADD, right);
        return List.of(left, right);
    }

    private static Expr balancedTree(List<Expr> leaves) {
        while (leaves.size() > 1) {
            var next = new ArrayList<Expr>();
            for (int i = 0; i < leaves.size(); i += 2)
                next.add(new BinaryExpr(leaves.get(i), BinaryOperator.ADD, leaves.get(i + 1)));
            leaves = next;
        }
        return leaves.getFirst();
    }

    private static long hitWork(Expr first, Expr equal) {
        try (var store = new SearchExpressionStore(SearchExpressionStore.Limits.DEFAULT)) {
            var expected = store.intern(first);
            long before = store.work();
            assertSame(expected, store.intern(equal));
            return store.work() - before;
        }
    }
    private static Expr chain(int depth) {
        Expr expression = new VariableExpr("x");
        for (int i = 0; i < depth; i++) expression = new BinaryExpr(expression, BinaryOperator.ADD, new NumberExpr(i));
        return expression;
    }
    private static Expr dag(int depth) {
        Expr expression = new VariableExpr("x");
        for (int i = 0; i < depth; i++) expression = new BinaryExpr(expression, BinaryOperator.ADD, expression);
        return expression;
    }
    private static final class IndexAbort extends RuntimeException {}
    private static final class IndexSink implements RetainedOperation.Sink {
        final SearchExpressionStore store;
        final IndexAbort failure = new IndexAbort();
        RetainedOperation operation;
        RetainedGraph.Usage peak = new RetainedGraph.Usage(0, 0, 0);
        boolean abort, checkpointFailed;
        int checkpoints, abortAtCheckpoint = Integer.MAX_VALUE;
        long firstReferences;
        long abortAtLiveNodes = Long.MAX_VALUE;
        IndexSink(SearchExpressionStore store) { this.store = store; }
        @Override public void retainedReferences(RetainedGraph.Visitor v) { v.reference(store); v.reference(operation); }
        @Override public void executionWork(long units) { if ((abort || checkpoints >= abortAtCheckpoint) && checkpointFailed) throw failure; }
        @Override public void validationWork(long units) {}
        @Override public void checkpoint() {
            var observed = RetainedGraph.measure(this).retained();
            if (++checkpoints == 1) firstReferences = observed.references();
            peak = new RetainedGraph.Usage(Math.max(peak.nodes(), observed.nodes()),
                Math.max(peak.characters(), observed.characters()), Math.max(peak.references(), observed.references()));
            if (store.statistics().liveNodes() >= abortAtLiveNodes) {
                abortAtLiveNodes = Long.MAX_VALUE;
                throw failure;
            }
            if (abort || checkpoints >= abortAtCheckpoint) { checkpointFailed = true; throw failure; }
        }
    }

    @Test void sharedTextAndScalarIdentitiesHaveTheSameFootprintInTheStoreAndWholeGraph() {
        String shared="x".repeat(100);
        var expression=new FunctionExpr("f",List.of(new VariableExpr(shared),new VariableExpr(shared)));
        assertEquals(101,de.regelsuche.retention.RetainedGraph.measure(expression).retained().characters());
        try(var store=new SearchExpressionStore(new SearchExpressionStore.Limits(10,150,10000,1))) {
            assertDoesNotThrow(()->store.intern(expression));
            assertEquals(101,store.statistics().liveCharacters());
            var rational=NumberExpr.exact("1/7").value();
            store.intern(new FunctionExpr("f",List.of(new NumberExpr(rational),new NumberExpr(rational))));
            assertEquals(103,store.statistics().liveCharacters(),"shared numerator and denominator are retained once each");
        }
    }

    @Test void collisionDoesNotConflateUnequalExpressionsAndIndependentEqualTreesShareTheIndex() {
        try (var store = new SearchExpressionStore(SearchExpressionStore.Limits.DEFAULT)) {
            var a = new VariableExpr("Aa"); var b = new VariableExpr("BB");
            assertEquals(a.hashCode(), b.hashCode(), "deliberate Java string hash collision");
            var left = assertDoesNotThrow(() -> store.intern(a));
            var right = store.intern(b);
            assertNotSame(left, right);
            assertEquals(a, store.dereference(left)); assertEquals(b, store.dereference(right));
            assertSame(left, store.intern(new VariableExpr("Aa")));
            var tree = new BinaryExpr(a, BinaryOperator.ADD, new NumberExpr(0));
            assertSame(store.intern(tree), store.intern(new BinaryExpr(new VariableExpr("Aa"), BinaryOperator.ADD, new NumberExpr(0))));
        }
    }
    @Test void scopesGroupingAndExactValuesRemainStructural() {
        try (var store = new SearchExpressionStore(SearchExpressionStore.Limits.DEFAULT)) {
            var outer = new SymbolScope(new UUID(0,1)); var inner = outer.child(new UUID(0,2));
            var a = VariableExpr.scoped(outer.declare("x")); var b = VariableExpr.scoped(inner.declare("x"));
            outer.alias("renamed_x", a.symbol().orElseThrow());
            assertNotSame(store.intern(a),store.intern(b));
            assertSame(store.intern(a),store.intern(VariableExpr.scoped(outer.resolve("renamed_x"))));
            assertSame(store.intern(NumberExpr.exact("0.125")),store.intern(NumberExpr.exact("1/8")));
            assertNotSame(store.intern(new BinaryExpr(a,BinaryOperator.MUL,b)),store.intern(new BinaryExpr(b,BinaryOperator.MUL,a)));
            assertNotSame(store.intern(new FunctionExpr("orderedProduct",List.of(a,b))),store.intern(new FunctionExpr("orderedProduct",List.of(b,a))));
        }
    }
    @Test void evictingAnIndexEntryDoesNotLoseALiveReferenceAndForeignOrClosedReferencesFail() {
        var limits = new SearchExpressionStore.Limits(100,1000,1000,1);
        var first = new SearchExpressionStore(limits); var second = new SearchExpressionStore(limits);
        var a = assertDoesNotThrow(() -> first.intern(new VariableExpr("a")));
        first.intern(new VariableExpr("b"));
        assertEquals(new VariableExpr("a"),first.dereference(a));
        assertEquals(1,first.statistics().indexEntries()); assertEquals(1,first.statistics().evictions());
        assertEquals(2,first.statistics().liveNodes(),"live roots remain owned after optional index eviction");
        assertThrows(IllegalArgumentException.class,()->second.dereference(a));
        first.close(); assertThrows(IllegalStateException.class,()->first.dereference(a));
        assertEquals(0,first.statistics().liveNodes());
        var independent=second.intern(new VariableExpr("a"));assertEquals(new VariableExpr("a"),second.dereference(independent));second.close();
    }
    @Test void totalNodeLimitIncludesOwnedRootsAfterIndexEviction() {
        try(var store=new SearchExpressionStore(new SearchExpressionStore.Limits(2,1000,1000,1))) {
            assertDoesNotThrow(()->store.intern(new VariableExpr("a")));store.intern(new VariableExpr("b"));
            assertThrows(SearchExpressionStore.LimitExceeded.class,()->store.intern(new VariableExpr("c")));
            assertEquals(2,store.statistics().liveNodes(),"failed acquisition must not partly mutate ownership");
        }
    }
}
