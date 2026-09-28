package de.regelsuche.retention;

import de.regelsuche.ast.*;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetainedGraphTest {
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
    @Test void undescribedPayloadCannotPretendToHaveZeroRetention() {
        var rejected=assertThrows(RetainedGraph.Unmeasured.class,()->RetainedGraph.measure(new Object()));
        assertEquals(5,rejected.attempted().work(),"failed admission retains performed audit work");
    }
}
