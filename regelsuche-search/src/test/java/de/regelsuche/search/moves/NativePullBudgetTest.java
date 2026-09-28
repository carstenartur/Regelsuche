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
    @Test void closingAnObservationLimitedPullKeepsEveryPaidUnitWithoutIssuingMathematics() {
        var binding=new Binding();var paid=new Paid();
        try(var operation=RetainedOperation.open(paid)) {
            long before=paid.work;var cursor=cursor(binding);
            assertTrue(cursor.next(5).isEmpty());cursor.close();
            assertEquals(10,paid.work-before);assertEquals(0,binding.pulls);
            assertEquals(0,cursor.snapshot().work().mathematics().primitiveRewrites());
            assertEquals(3,cursor.snapshot().work().metrics().totalWorkUnitsV2());
            cursor.close();assertEquals(10,paid.work-before);
        }
    }
    @Test void withoutANativeObservationScopeTheExistingMeterAllowanceIsUnchanged() {
        var binding=new Binding();try(var cursor=cursor(binding)) {
            assertTrue(cursor.next(5).isPresent());assertEquals(2,binding.received);
            assertEquals(1,binding.opens);assertEquals(1,binding.pulls);
        }
    }
    private static final class EmptyLane implements StagedIncrementalLanes.Source<NativeMoveProof>,ManagedProviderCursor.Binding<NativeMoveProof>,ObjectSource<NativeMoveProof> {
        private final String id;private final long closingWork,openingWork;
        private int opens,pulls,closes,cursorOpens;
        EmptyLane(String id,long closingWork){this(id,closingWork,0);}
        EmptyLane(String id,long closingWork,long openingWork){this.id=id;this.closingWork=closingWork;this.openingWork=openingWork;}
        @Override public MoveProvider.Descriptor descriptor(){return new MoveProvider.Descriptor(id,id,SearchMove.SourceKind.PRIMITIVE,SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,id);}
        @Override public boolean batch(){return false;}
        @Override public boolean nativeTransport(){return true;}
        @Override public ObjectCursor<NativeMoveProof> open(java.util.function.Consumer<List<NativeMoveProof>> generated,java.util.function.LongSupplier totalWork){
            cursorOpens++;RetainedOperation.work(openingWork);
            return new ManagedProviderCursor<>(new Definition(NATIVE_REVISION,id,Kind.REGISTERED_SCHEMA,"model","semantics",Transport.NATIVE_EXPR_V1,Mathematics.PRIMITIVE,null),this);
        }
        @Override public boolean carries(){return true;}
        @Override public ObjectSource<NativeMoveProof> open(Meter meter){opens++;return this;}
        @Override public void requireSource(NativeMoveProof candidate){fail("empty lane emitted a candidate");}
        @Override public ExecutionWork work(NativeMoveProof candidate){return candidate.work();}
        @Override public Optional<NativeMoveProof> next(long allowance){pulls++;return Optional.empty();}
        @Override public Status status(){return pulls==0?Status.READY:Status.EXHAUSTED;}
        @Override public void close(){closes++;RetainedOperation.work(closingWork);}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(id);}
    }
    private static final class EmptyRanking implements SearchBatches.Ranking<NativeMoveProof> {
        @Override public double score(NativeMoveProof move){return 0;}
        @Override public int stage(MoveProvider.Descriptor provider){return 0;}
        @Override public double providerScore(MoveProvider.Descriptor provider){return 0;}
        @Override public long contextWork(){return 0;}
        @Override public void requireSource(NativeMoveProof move){}
        @Override public void retainedReferences(RetainedGraph.Visitor v){}
    }
    @Test void closingNativeLaneConsumesAllowanceBeforeAnotherLaneCanOpen(){
        var first=new EmptyLane("first",10);var second=new EmptyLane("second",0);var paid=new Paid();
        try(var operation=RetainedOperation.open(paid)){
            var lanes=new StagedIncrementalLanes<>(List.<StagedIncrementalLanes.Source<NativeMoveProof>>of(first,second),new EmptyRanking(),"state");
            long before=paid.work;
            assertTrue(lanes.next(10).isEmpty());
            assertEquals(1,first.opens);assertEquals(1,first.pulls);assertEquals(1,first.closes);
            assertEquals(0,second.opens,"first lane paid four meter units plus ten native close units; no allowance remains");
            assertTrue(lanes.workExhausted());assertEquals(10,paid.work-before);
            assertEquals(6,lanes.workMetrics().totalWorkUnitsV2(),"two ordering units plus four actual lifecycle units");
            assertTrue(lanes.next(4).isEmpty());assertEquals(1,second.opens);assertEquals(1,second.closes);
            assertFalse(lanes.workExhausted());assertEquals(10,paid.work-before,"old close work must not be charged again on resume");
            assertEquals(10,lanes.workMetrics().totalWorkUnitsV2());
            lanes.close();assertEquals(10,paid.work-before);
        }
    }
    @Test void laneSwitchWithoutObservationScopeKeepsTheExistingAllowance(){
        var first=new EmptyLane("first",10);var second=new EmptyLane("second",0);
        var lanes=new StagedIncrementalLanes<>(List.<StagedIncrementalLanes.Source<NativeMoveProof>>of(first,second),new EmptyRanking(),"state");
        assertTrue(lanes.next(10).isEmpty());assertEquals(1,second.opens);assertFalse(lanes.workExhausted());
        assertEquals(10,lanes.workMetrics().totalWorkUnitsV2());lanes.close();
    }

    @Test void nativeLaneOpeningConsumesAllowanceBeforeTheFirstCursorPull(){
        var lane=new EmptyLane("opening",0,7);var paid=new Paid();
        try(var operation=RetainedOperation.open(paid)){
            var lanes=new StagedIncrementalLanes<>(List.<StagedIncrementalLanes.Source<NativeMoveProof>>of(lane),new EmptyRanking(),"state");
            long before=paid.work;
            assertTrue(lanes.next(5).isEmpty());assertEquals(1,lane.cursorOpens);
            assertEquals(0,lane.opens,"seven native opening units exhausted allowance five before managed admission/open");
            assertEquals(0,lane.pulls);assertTrue(lanes.workExhausted());assertEquals(7,paid.work-before);
            assertEquals(1,lanes.workMetrics().totalWorkUnitsV2(),"only the pre-existing provider ordering unit");
            assertTrue(lanes.next(4).isEmpty());assertEquals(1,lane.cursorOpens,"resume reuses the already created cursor");
            assertEquals(1,lane.opens);assertEquals(1,lane.pulls);assertEquals(1,lane.closes);
            assertEquals(7,paid.work-before);assertEquals(5,lanes.workMetrics().totalWorkUnitsV2());
            lanes.close();assertEquals(7,paid.work-before);
        }
    }

}
