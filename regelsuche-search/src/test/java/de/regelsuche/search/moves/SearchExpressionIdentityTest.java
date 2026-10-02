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
        IndexSink(SearchExpressionStore store) { this.store = store; }
        @Override public void retainedReferences(RetainedGraph.Visitor v) { v.reference(store); v.reference(operation); }
        @Override public void executionWork(long units) { if (abort && checkpointFailed) throw failure; }
        @Override public void validationWork(long units) {}
        @Override public void checkpoint() {
            var observed = RetainedGraph.measure(this).retained();
            peak = new RetainedGraph.Usage(Math.max(peak.nodes(), observed.nodes()),
                Math.max(peak.characters(), observed.characters()), Math.max(peak.references(), observed.references()));
            if (abort) { checkpointFailed = true; throw failure; }
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
