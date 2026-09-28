package de.regelsuche.retention;

import de.regelsuche.ast.*;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetainedGraphTest {
    private enum DescribedEnum implements RetainedGraph.View {
        VALUE;
        final ArrayList<Expr> values=new ArrayList<>();
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(values);}
    }
    private enum UnknownEnum { VALUE }
    @Test void enumViewsAreTraversedAndUnknownEnumCapturesAreNotAssumedEmpty() {
        try {
            DescribedEnum.VALUE.values.add(new VariableExpr("x"));
            assertEquals(1,RetainedGraph.measure(DescribedEnum.VALUE).retained().nodes());
            assertThrows(RetainedGraph.Unmeasured.class,()->RetainedGraph.measure(UnknownEnum.VALUE));
        } finally {DescribedEnum.VALUE.values.clear();}
    }

    @Test void nativeFeatureTextBuildersAndScalarCountersExposeTheirLogicalStorage() {
        var text=new StringBuilder("abc");
        var first=RetainedGraph.measure(text);
        assertEquals(new RetainedGraph.Usage(0,3,2),first.retained());assertEquals(6,first.work());
        text.append("def");assertEquals(new RetainedGraph.Usage(0,6,2),RetainedGraph.measure(text).retained());
        var counters=RetainedGraph.measure(new int[3]);
        assertEquals(new RetainedGraph.Usage(0,0,4),counters.retained());assertEquals(8,counters.work());
    }

    @Test void sharedObjectsCountOnceWhileEveryRetainedReferenceCountsAndReleasedGraphsDisappear() {
        var leaf=new VariableExpr("x");var binary=new BinaryExpr(leaf,BinaryOperator.ADD,leaf);
        var roots=new ArrayList<Object>();roots.add(binary);roots.add(leaf);
        var both=RetainedGraph.measure(roots);
        // Owner root slot + list backing slot + two entries + three binary fields + two variable fields.
        assertEquals(new RetainedGraph.Usage(2,1,9),both.retained());
        assertEquals(4,both.objects(),"list, binary, leaf and name; the global enum is borrowed");
        assertTrue(both.peak().references()>both.retained().references(),"the audit's identity table and traversal also occupy slots");
        assertEquals(27,both.work());assertEquals(new RetainedGraph.Usage(2,1,23),both.peak());
        roots.clear();roots.add(leaf);
        var released=RetainedGraph.measure(roots);
        assertEquals(new RetainedGraph.Usage(1,1,5),released.retained());
        assertEquals(3,released.objects());assertEquals(17,released.work(),"every new traversal remains paid");
        assertEquals(new RetainedGraph.Usage(1,1,16),released.peak());
        roots.clear();var empty=RetainedGraph.measure(roots);
        assertEquals(new RetainedGraph.Usage(0,0,2),empty.retained());assertEquals(1,empty.objects());
        assertEquals(6,empty.work());assertEquals(new RetainedGraph.Usage(0,0,9),empty.peak());
    }
    @Test void explicitCyclicViewsTerminateAndKeepTheirReferenceSlot() {
        class Link implements RetainedGraph.View {
            final Object next=this;
            @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(next);}
        }
        var cycle=RetainedGraph.measure(new Link());
        assertEquals(new RetainedGraph.Usage(0,0,2),cycle.retained());
        assertEquals(1,cycle.objects());assertEquals(7,cycle.work());
    }
    @Test void containerViewsAndComparatorsCannotHideTheirBackingOwners() {
        var backing=new ArrayList<Object>();backing.add(new VariableExpr("retained outside visible range"));
        assertThrows(RetainedGraph.Unmeasured.class,()->RetainedGraph.measure(backing.subList(0,0)));
        var map=new java.util.HashMap<String,Object>();map.put("key",backing);
        assertThrows(RetainedGraph.Unmeasured.class,()->RetainedGraph.measure(map.keySet()));
        assertThrows(RetainedGraph.Unmeasured.class,()->RetainedGraph.measure(java.util.Collections.unmodifiableMap(map)));
        var sorted=new java.util.TreeMap<String,Object>((a,b)->Integer.compare(a.length()+backing.size(),b.length()));
        assertThrows(RetainedGraph.Unmeasured.class,()->RetainedGraph.measure(sorted));
    }
    @Test void numericConversionPaysAndObservesItsTemporaryText() {
        var integer=new java.math.BigInteger("123456789012345678901234567890");
        var measured=RetainedGraph.measure(integer);
        assertEquals(30,measured.retained().characters());
        assertEquals(60,measured.peak().characters(),"retained scalar and live decimal conversion overlap");
        assertTrue(measured.work()>=30);
    }
    @Test void immutableSortedOwnerKeepsOrderAndExposesItsActualBackingGraph() {
        var map=RetainedSortedMap.copyOf(java.util.Map.of("b",new VariableExpr("b"),"a",new VariableExpr("a")));
        assertEquals(java.util.List.of("a","b"),new ArrayList<>(map.keySet()));
        assertThrows(UnsupportedOperationException.class,()->map.keySet().clear());
        assertThrows(UnsupportedOperationException.class,()->map.entrySet().iterator().next().setValue(new VariableExpr("changed")));
        assertEquals(2,RetainedGraph.measure(map).retained().nodes());
    }
    @Test void opaqueBigIntegerSubclassCannotHideAnOwnedExpressionGraph() {
        class CapturingInteger extends java.math.BigInteger {
            final Expr retained;
            CapturingInteger(Expr retained){super("1");this.retained=retained;}
        }
        var leaf=new VariableExpr("captured");
        var scalar=new CapturingInteger(new BinaryExpr(leaf,BinaryOperator.ADD,leaf));
        var rejected=assertThrows(RetainedGraph.Unmeasured.class,()->RetainedGraph.measure(scalar));
        assertTrue(rejected.attempted().work()>0);
    }
    @Test void undescribedPayloadCannotPretendToHaveZeroRetention() {
        var rejected=assertThrows(RetainedGraph.Unmeasured.class,()->RetainedGraph.measure(new Object()));
        assertEquals(5,rejected.attempted().work(),"failed admission retains performed audit work");
    }
}
