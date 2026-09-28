package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.symbol.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SearchExpressionIdentityTest {
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
