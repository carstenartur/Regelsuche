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
