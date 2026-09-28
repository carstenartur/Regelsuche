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
        assertEquals(19,both.work());assertEquals(new RetainedGraph.Usage(2,1,23),both.peak());
        roots.clear();roots.add(leaf);
        var released=RetainedGraph.measure(roots);
        assertEquals(new RetainedGraph.Usage(1,1,5),released.retained());
        assertEquals(3,released.objects());assertEquals(11,released.work(),"every new traversal remains paid");
        assertEquals(new RetainedGraph.Usage(1,1,16),released.peak());
        roots.clear();var empty=RetainedGraph.measure(roots);
        assertEquals(new RetainedGraph.Usage(0,0,2),empty.retained());assertEquals(1,empty.objects());
        assertEquals(4,empty.work());assertEquals(new RetainedGraph.Usage(0,0,9),empty.peak());
    }
    @Test void explicitCyclicViewsTerminateAndKeepTheirReferenceSlot() {
        class Link implements RetainedGraph.View {
            final Object next=this;
            @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(next);}
        }
        var cycle=RetainedGraph.measure(new Link());
        assertEquals(new RetainedGraph.Usage(0,0,2),cycle.retained());
        assertEquals(1,cycle.objects());assertEquals(5,cycle.work());
    }
    @Test void undescribedPayloadCannotPretendToHaveZeroRetention() {
        var rejected=assertThrows(RetainedGraph.Unmeasured.class,()->RetainedGraph.measure(new Object()));
        assertEquals(3,rejected.attempted().work(),"failed admission retains performed audit work");
    }
}
