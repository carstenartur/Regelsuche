package de.regelsuche.ast;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExpressionIdentityAccountingTest {
    @Test void nativeDeeplyNestedValuesKeepValueEqualityWithoutRecursiveJavaCalls() {
        Expr left = chain(12_000), right = chain(12_000);
        paid(() -> {
            assertTrue(assertDoesNotThrow(() -> left.equals(right)));
            assertEquals(assertDoesNotThrow(left::hashCode), assertDoesNotThrow(right::hashCode));
        });
    }

    @Test void hashesPreserveThePreviousJavaValueContract() {
        var scoped = VariableExpr.scoped(new SymbolId(UUID.fromString("12345678-1234-5678-9abc-123456789abc"), 7));
        List<Expr> values = List.of(new VariableExpr("Aa"), scoped, new NumberExpr(-17),
            new NumberExpr(new ExactRational(new BigInteger("-91823749182736491283746"), BigInteger.valueOf(29))),
            new FunctionExpr("f", List.of()), new FunctionExpr("f", List.of(scoped, chain(5))), chain(9));
        for (Expr value : values) {
            assertEquals(previousHash(value), value.hashCode());
            var sink = new Sink();
            try (var scope = RetainedOperation.open(sink)) {
                sink.scope = scope;
                assertEquals(previousHash(value), value.hashCode(), "active accounting cannot change key hashes");
            } finally { sink.scope = null; }
        }
    }

    @Test void sharedDagsPayForDistinctStructureWithoutExpandingOccurrences() {
        Expr left = dag(18), right = dag(18);
        long compared = paid(() -> assertEquals(left, right));
        long hashed = paid(left::hashCode);
        assertTrue(compared >= 18, "a record equality traversal must be paid");
        assertTrue(hashed >= 18, "a record hash traversal must be paid");
        assertTrue(compared < 2_000 && hashed < 2_000, "shared DAGs must remain shared during identity work");
    }

    @Test void nameAndScalarInspectionContributesToWork() {
        long shortName = paid(() -> new VariableExpr("xx").hashCode());
        long longName = paid(() -> new VariableExpr("x".repeat(1000)).hashCode());
        assertTrue(longName > shortName + 900);
        Expr small = new NumberExpr(42);
        Expr large = new NumberExpr(new ExactRational(BigInteger.ONE.shiftLeft(8192).add(BigInteger.ONE), BigInteger.ONE));
        assertTrue(paid(large::hashCode) > paid(small::hashCode) + 200);
    }

    @Test void collisionsNeverEstablishEqualityOrForgetScopesAndGrouping() {
        Expr a = new FunctionExpr("f", List.of(new VariableExpr("Aa")));
        Expr b = new FunctionExpr("f", List.of(new VariableExpr("BB")));
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, b);
        var namespace = UUID.fromString("12345678-1234-5678-9abc-123456789abc");
        assertNotEquals(VariableExpr.scoped(new SymbolId(namespace, 1)), VariableExpr.scoped(new SymbolId(namespace, 2)));
        var x = new VariableExpr("x"); var y = new VariableExpr("y"); var z = new VariableExpr("z");
        assertNotEquals(new BinaryExpr(new BinaryExpr(x, BinaryOperator.ADD, y), BinaryOperator.ADD, z),
            new BinaryExpr(x, BinaryOperator.ADD, new BinaryExpr(y, BinaryOperator.ADD, z)));
        assertNotEquals(new BinaryExpr(x, BinaryOperator.SUB, y), new BinaryExpr(y, BinaryOperator.SUB, x));
    }

    @Test void hashMapKeysUseThePaidComparerAndKeepValueLookup() {
        Expr source = chain(40), equal = chain(40);
        var entries = new HashMap<Expr, String>();
        long work = paid(() -> { entries.put(source, "known"); assertEquals("known", entries.get(equal)); });
        assertTrue(work > 160, "HashMap must not hide recursive unpaid hashes or equality");
        assertEquals(1, entries.size());
    }

    @Test void aFailedDebitStillObservesScratchAndPreservesThePrimaryFailure() {
        for (boolean comparison : List.of(false, true)) {
            Expr left = chain(40), right = chain(40);
            var sink = new Sink();
            try (var scope = RetainedOperation.open(sink)) {
                sink.scope = scope;
                sink.abortAt = 80;
                assertSame(sink.failure, assertThrows(Abort.class, () -> {
                    if (comparison) assertEquals(left, right); else left.hashCode();
                }));
                assertTrue(sink.peak.nodes() >= 81, "compared input and populated scratch remain visible on failure");
                assertTrue(sink.work >= 80, "failed work is not refunded");
                assertEquals(0, RetainedGraph.measure(sink).retained().nodes(), "scratch is released after failure");
                sink.abortAt = Long.MAX_VALUE;
                assertEquals(left, right);
            } finally { sink.scope = null; }
        }
    }

    private static int previousHash(Expr value) {
        return switch (value) {
            case VariableExpr v -> v.symbol().map(SymbolId::hashCode).orElseGet(() -> v.name().hashCode());
            case NumberExpr n -> 31 * n.value().numerator().hashCode() + n.value().denominator().hashCode();
            case BinaryExpr b -> 31 * (31 * previousHash(b.left()) + b.operator().hashCode()) + previousHash(b.right());
            case FunctionExpr f -> {
                int args = 1;
                for (Expr argument : f.arguments()) args = 31 * args + previousHash(argument);
                yield 31 * f.name().hashCode() + args;
            }
        };
    }
    private static long paid(Runnable action) {
        var sink = new Sink();
        try (var scope = RetainedOperation.open(sink)) {
            sink.scope = scope;
            long before = sink.work;
            action.run();
            return sink.work - before;
        } finally { sink.scope = null; }
    }
    private static Expr chain(int depth) {
        Expr result = new VariableExpr("x");
        for (int i = 0; i < depth; i++) result = new BinaryExpr(result, BinaryOperator.ADD, new NumberExpr(i));
        return result;
    }
    private static Expr dag(int depth) {
        Expr result = new VariableExpr("x");
        for (int i = 0; i < depth; i++) result = new BinaryExpr(result, BinaryOperator.ADD, result);
        return result;
    }
    private static final class Abort extends RuntimeException {}
    private static final class Sink implements RetainedOperation.Sink {
        RetainedOperation scope;
        long work, abortAt = Long.MAX_VALUE;
        final Abort failure = new Abort();
        RetainedGraph.Usage peak = new RetainedGraph.Usage(0, 0, 0);
        @Override public void executionWork(long units) { work += units; if (work >= abortAt) throw failure; }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void checkpoint() { peak = peak.maximum(RetainedGraph.measure(this).peak()); }
        @Override public void retainedReferences(RetainedGraph.Visitor v) { v.reference(scope); }
    }
}
