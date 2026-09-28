package de.regelsuche.retention;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RetainedOperationTest {
    private static final class FailingCharge implements RetainedOperation.Sink {
        final int throwAt;
        int calls;
        FailingCharge(int throwAt){this.throwAt=throwAt;}
        @Override public void executionWork(long units){if(++calls==throwAt)throw new ArithmeticException("test charge overflow");}
        @Override public void validationWork(long units){}
        @Override public void checkpoint(){}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){}
    }
    private static final class GraphSink implements RetainedOperation.Sink {
        private final de.regelsuche.ast.Expr graph=new de.regelsuche.ast.VariableExpr("retained");
        private long work;
        @Override public void executionWork(long units){work+=units;}
        @Override public void validationWork(long units){}
        @Override public void checkpoint(){}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(graph);}
    }
    @Test void closedHandlesReleaseTheGraphsOfTheirFormerOwners() {
        var sink=new GraphSink();
        var scope=RetainedOperation.open(sink);
        var outer=RetainedOperation.retain(new de.regelsuche.ast.VariableExpr("outer"));
        var inner=RetainedOperation.retain(new de.regelsuche.ast.VariableExpr("inner"));
        assertEquals(3,RetainedGraph.measure(inner).retained().nodes());
        inner.close();outer.close();scope.close();
        assertEquals(10,sink.work);
        assertEquals(0,RetainedGraph.measure(inner).retained().nodes(),"closed frame must release prior frame and scope");
        assertEquals(0,RetainedGraph.measure(scope).retained().nodes(),"closed scope must release its sink");
    }
    private static void isolated(Runnable check) throws InterruptedException {
        var failure=new AtomicReference<Throwable>();
        var thread=new Thread(()->{try{check.run();}catch(Throwable thrown){failure.set(thrown);}});
        thread.start();thread.join();
        if(failure.get()!=null)throw new AssertionError("isolated native scope",failure.get());
    }
    @Test void failedOpeningChargeCannotLeakAnUnreturnedThreadLocalScope() throws InterruptedException {
        isolated(()->{
            var sink=new FailingCharge(1);
            assertThrows(ArithmeticException.class,()->RetainedOperation.open(sink));
            RetainedOperation.work(1);
            assertEquals(1,sink.calls,"failed acquisition must restore the previous observation");
        });
    }
    @Test void failedFrameChargeCannotLeaveAnUnreturnedFrameInItsOwner() throws InterruptedException {
        isolated(()->{
            var sink=new FailingCharge(2);
            var scope=RetainedOperation.open(sink);
            assertThrows(ArithmeticException.class,()->RetainedOperation.retain("temporary"));
            assertDoesNotThrow(scope::close,"owner must remain closable after failed frame acquisition");
            int finished=sink.calls;RetainedOperation.work(1);assertEquals(finished,sink.calls);
        });
    }
}
