package de.regelsuche.search.moves;

import static de.regelsuche.search.moves.IncrementalProviderContract.*;
import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.retention.*;
import de.regelsuche.transform.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativePullBudgetTest {
    private static final class Paid implements RetainedOperation.Sink {
        long work;
        @Override public void executionWork(long units){work+=units;}
        @Override public void validationWork(long units){work+=units;}
        @Override public long observedWork(){return work;}
        @Override public void checkpoint(){}
        @Override public void retainedReferences(RetainedGraph.Visitor v){}
    }
    private static final class Binding implements ManagedProviderCursor.Binding<NativeMoveProof>,ObjectSource<NativeMoveProof> {
        private final NativeMoveProof proof;
        private Meter meter;
        private int opens,pulls;
        private long received;
        Binding(){
            var a=PatternExpr.var("A");var rule=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,a,PatternExpr.num(0)),a);
            var source=new BinaryExpr(new VariableExpr("x"),BinaryOperator.ADD,new NumberExpr(0));
            proof=new NativeMoveProof.Primitive(new AstRewriteTransport(List.of(rule),32,32).generate(source).getFirst());
        }
        @Override public boolean carries(){return true;}
        @Override public ObjectSource<NativeMoveProof> open(Meter meter){this.meter=meter;opens++;RetainedOperation.work(7);return this;}
        @Override public void requireSource(NativeMoveProof candidate){assertEquals(proof.source(),candidate.source());}
        @Override public ExecutionWork work(NativeMoveProof candidate){return candidate.work();}
        @Override public Optional<NativeMoveProof> next(long allowance){received=allowance;pulls++;meter.charge(Operation.MATCH,1);meter.charge(proof.work());return Optional.of(proof);}
        @Override public Status status(){return pulls==0?Status.READY:Status.EXHAUSTED;}
        @Override public void close(){RetainedOperation.work(3);}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(proof);v.reference(meter);}
    }
    private static ManagedProviderCursor<NativeMoveProof> cursor(Binding binding){
        return new ManagedProviderCursor<>(new Definition(NATIVE_REVISION,"zero",Kind.REGISTERED_SCHEMA,"model","semantics",Transport.NATIVE_EXPR_V1,Mathematics.PRIMITIVE,null),binding);
    }
    @Test void openingObservationWorkConsumesTheSamePullAndResumptionDoesNotCountItTwice() {
        var binding=new Binding();var paid=new Paid();
        try(var operation=RetainedOperation.open(paid)) {
            long before=paid.work;var cursor=cursor(binding);
            assertTrue(cursor.next(5).isEmpty(),"admission/open2 plus atomic native observation7 exhaust this pull");
            assertEquals(1,binding.opens);assertEquals(0,binding.pulls);assertEquals(7,paid.work-before);
            assertEquals(Status.LIMIT,cursor.snapshot().status());assertEquals(0,cursor.snapshot().work().units(Operation.PULL));
            assertEquals(2,cursor.snapshot().work().units(Operation.ADMISSION)+cursor.snapshot().work().units(Operation.OPEN));
            assertTrue(cursor.next(2).isPresent());assertEquals(1,binding.received,"one new PULL unit leaves exactly one delegated unit; old observation is not paid twice");
            assertEquals(7,paid.work-before);assertEquals(1,cursor.snapshot().work().mathematics().primitiveRewrites());
            long metered=cursor.snapshot().work().metrics().totalWorkUnitsV2();cursor.close();
            assertEquals(10,paid.work-before);assertEquals(metered+1,cursor.snapshot().work().metrics().totalWorkUnitsV2());
            cursor.close();assertEquals(10,paid.work-before);assertTrue(cursor.snapshot().accountingComplete());
        }
    }
    @Test void withoutANativeObservationScopeTheExistingMeterAllowanceIsUnchanged() {
        var binding=new Binding();try(var cursor=cursor(binding)) {
            assertTrue(cursor.next(5).isPresent());assertEquals(2,binding.received);
            assertEquals(1,binding.opens);assertEquals(1,binding.pulls);
        }
    }
}
