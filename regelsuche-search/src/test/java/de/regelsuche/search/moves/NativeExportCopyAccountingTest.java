package de.regelsuche.search.moves;

import de.regelsuche.ast.*;
import de.regelsuche.retention.*;
import de.regelsuche.transform.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Constructor copy costs belong to the native export caller, not the historical constructors. */
class NativeExportCopyAccountingTest {
    private static final Expr X=new VariableExpr("x");
    private static final Expr SOURCE=new BinaryExpr(X,BinaryOperator.ADD,new NumberExpr(0));
    private enum Value implements NativeStateValue,RetainedGraph.View {
        INSTANCE;
        @Override public Assessment evaluate(TypedMoveSearch.State state,TypedMoveSearch.Context context){
            return state.expression().equals(SOURCE)?new Assessment(3,1,1,0,
                Map.of("zero",new Capability("zero",SOURCE,"",SOURCE,X))):Assessment.EMPTY;
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){}
    }
    private static NativeMoveSearch.Result source(MoveSearch.Scheduling scheduling){
        var a=PatternExpr.var("A");
        var rule=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,a,PatternExpr.num(0)),a);
        var descriptor=new MoveProvider.Descriptor("zero","zero",SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,"copy-export/v1");
        var provider=new NativeMoveSearch.Primitive(descriptor,new AstRewriteTransport(List.of(rule),32,32));
        var result=new NativeMoveSearch().search(new NativeMoveSearch.Problem(SOURCE,TypedMoveSearch.Context.frozen(X),
            List.of(provider),MoveSearch.Mode.FAST,scheduling,new MoveSearch.Budget(2,2,0,10,10_000_000),
            NativeMovePriorityPolicy.INVENTORY_ORDER,NativeMoveSearch.ZeroScore.INSTANCE,Value.INSTANCE),SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(MoveSearch.Outcome.TARGET_REACHED,result.observedOutcome());return result;
    }
    private static List<Object> refs(RetainedGraph.View owner){
        var values=new ArrayList<Object>();
        owner.retainedReferences(new RetainedGraph.Visitor(){
            @Override public void reference(Object value){values.add(value);}
            @Override public void requireExact(Object value,Class<?> type){assertEquals(type,value.getClass());}
        });return values;
    }
    private static List<Object[]> frames(RetainedOperation scope){
        var result=new ArrayList<Object[]>();if(scope==null)return result;
        Object current=refs(scope).get(2);
        while(current instanceof RetainedOperation.Frame frame){
            var owned=refs(frame);result.add((Object[])owned.get(2));current=owned.get(1);
        }return result;
    }
    private static boolean at(String method){return StackWalker.getInstance().walk(stack->stack.anyMatch(frame->
        frame.getClassName().equals(SearchExecution.class.getName()) && frame.getMethodName().equals(method)));}
    private enum Kind { RESULT,STAGED,LANES,STATE,ASSESSMENT }
    private enum Stop { DEBIT,CHECKPOINT }
    private record Copy(Object source,Object target,int containers){
        long expected(){return source==target?0:containers+(long)(source instanceof Map<?,?> map?map.size():((Collection<?>)source).size());}
    }
    private static final class Block {
        final Kind kind;final Object target;final List<Copy> copies;final List<Long> debits=new ArrayList<>();
        Block(Kind kind,Object target,List<Copy> copies){this.kind=kind;this.target=target;this.copies=copies;}
        long expected(){return 1+copies.stream().mapToLong(Copy::expected).sum();}
    }
    private static final class Abort extends RuntimeException{}
    private static final class Probe implements RetainedOperation.Sink {
        NativeMoveSearch.Result source;RetainedOperation scope;RetainedJson.Scope json;
        final List<Block> blocks=new ArrayList<>();
        final Abort primary=new Abort();
        Kind abortKind;Stop abortAt;Block pending,stopped;
        boolean inputsAndOutputOwned;long acceptedAtStop,work;
        // Diagnostic captures deliberately cannot make constructor inputs or products owned.
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(source);visitor.reference(scope);visitor.reference(json);}
        private Block block(Kind kind,Object target,List<Copy> copies){
            return blocks.stream().filter(item->item.target==target).findFirst().orElseGet(()->{
                var item=new Block(kind,target,copies);blocks.add(item);return item;});
        }
        private Block completedBlock(){
            var frames=frames(scope);
            for(var values:frames)if(values.length==3 && values[0] instanceof NativeStateValue.Assessment
                && values[1] instanceof Map<?,?> input && values[2] instanceof Object[] pending
                && pending[3] instanceof StateValue.Assessment result)
                return block(Kind.ASSESSMENT,result,List.of(new Copy(input,result.capabilities(),2)));
            for(var values:frames)if(values.length==2 && values[0] instanceof TypedMoveSearch.State original
                && values[1] instanceof Object[] pending && pending[1] instanceof MoveState result)
                return block(Kind.STATE,result,List.of(new Copy(original.capabilities(),result.capabilities(),1)));
            for(var values:frames)if(values.length>=7 && values[0] instanceof Map<?,?> && values[6] instanceof Object[] pending){
                if(pending[1] instanceof MoveSearch.Result result)
                    return block(Kind.RESULT,result,List.of(new Copy(values[1],result.witness(),1),
                        new Copy(values[2],result.events(),1),new Copy(values[3],result.reachedStates(),1),
                        new Copy(values[4],result.deadEndStates(),1),new Copy(values[0],result.stateAssessments(),1)));
                if(pending[0] instanceof StagedIncrementalMoveExecution staged)
                    return block(Kind.STAGED,staged,List.of(new Copy(refs(source).get(2),staged.providers(),1),
                        new Copy(values[5],staged.expansions(),1)));
                if(pending[1] instanceof StagedIncrementalMoveExecution.Expansion expansion){
                    // An already appended expansion is at the separate mutable-insertion debit.
                    if(((List<?>)values[5]).stream().anyMatch(item->item==expansion))return null;
                    var receipts=(List<?>)pending[3];assertEquals(1,receipts.size(),"single root expansion fixture");
                    var receipt=(SearchExecution.Expansion<?>)receipts.getFirst();
                    return block(Kind.LANES,expansion,List.of(new Copy(receipt.lanes(),expansion.lanes(),1)));
                }
            }
            return null;
        }
        @Override public void executionWork(long units){
            work=Math.addExact(work,units);
            if(!at("completed"))return;
            var block=completedBlock();if(block==null)return;
            block.debits.add(units);
            if(stopped==null && block.kind==abortKind){
                pending=block;
                if(abortAt==Stop.DEBIT)stop();
            }
        }
        private void stop(){
            stopped=pending;pending=null;
            acceptedAtStop=stopped.debits.stream().mapToLong(Long::longValue).sum();
            var owned=graph(scope);
            inputsAndOutputOwned=owned.contains(stopped.target) && stopped.copies.stream()
                .allMatch(copy->owned.contains(copy.source()) && owned.contains(copy.target()));
            throw primary;
        }
        @Override public void validationWork(long units){fail("export is not new mathematical authorization");}
        @Override public void checkpoint(){
            work=Math.addExact(work,RetainedGraph.measure(this).work());
            if(pending!=null && abortAt==Stop.CHECKPOINT)stop();
        }
    }
    private static Set<Object> graph(Object root){
        var seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());var todo=new ArrayDeque<Object>();
        if(root!=null)todo.add(root);
        while(!todo.isEmpty()){
            var value=todo.removeFirst();if(!seen.add(value))continue;
            Collection<?> children=value instanceof RetainedGraph.View view?refs(view)
                :value instanceof Map<?,?> map?map.entrySet().stream().flatMap(entry->java.util.stream.Stream.of(entry.getKey(),entry.getValue())).toList()
                :value instanceof Collection<?> collection?collection:value instanceof Object[] array?Arrays.asList(array):List.of();
            for(var child:children)if(child!=null)todo.add(child);
        }return seen;
    }
    private static MoveSearch.Result export(Probe probe){
        try(var scope=RetainedOperation.open(probe);var json=RetainedJson.open()){
            probe.scope=scope;probe.json=json;return probe.source.projectLegacy();
        }
    }
    private static List<Block> blocks(Probe probe,Kind kind){return probe.blocks.stream().filter(block->block.kind==kind).toList();}
    private static void exact(List<Block> blocks){
        for(var block:blocks)assertEquals(List.of(block.expected()),block.debits,
            block.kind+" must settle the entire completed constructor block before its first checkpoint");
    }
    @Test void finalResultPaysAllFiveActualContainerCopiesInItsFirstDebit(){
        var probe=new Probe();probe.source=source(MoveSearch.Scheduling.STAGED);
        var output=export(probe);var blocks=blocks(probe,Kind.RESULT);
        assertEquals(1,blocks.size());var block=blocks.getFirst();assertSame(output,block.target);
        assertEquals(5,block.copies.size());exact(blocks);
        assertEquals(output.witness().size()+output.events().size()+output.reachedStates().size()
            +output.deadEndStates().size()+output.stateAssessments().size()+6L,block.debits.getFirst());
        assertTrue(block.copies.stream().allMatch(copy->copy.source()!=copy.target()),"these inputs are the actual mutable aggregate builders");
    }
    @Test void stagedCopiesUseActualIdentityAndReuseTheFrozenLaneLists(){
        var probe=new Probe();probe.source=source(MoveSearch.Scheduling.STAGED_INCREMENTAL);export(probe);
        var staged=blocks(probe,Kind.STAGED);assertEquals(1,staged.size());assertEquals(2,staged.getFirst().copies.size());exact(staged);
        var providers=staged.getFirst().copies.getFirst();assertEquals(providers.source(),providers.target());
        var expansions=staged.getFirst().copies.getLast();assertNotSame(expansions.source(),expansions.target());
        var lanes=blocks(probe,Kind.LANES);assertEquals(1,lanes.size());exact(lanes);
        var copy=lanes.getFirst().copies.getFirst();assertSame(copy.source(),copy.target());assertEquals(0,copy.expected());
    }
    @Test void immutableStateCapabilitiesAreObservedAsReuseWithoutCopyCharge(){
        var probe=new Probe();probe.source=source(MoveSearch.Scheduling.STAGED);export(probe);
        var blocks=blocks(probe,Kind.STATE);assertFalse(blocks.isEmpty());exact(blocks);
        for(var block:blocks){var copy=block.copies.getFirst();assertSame(copy.source(),copy.target());assertEquals(0,copy.expected());}
    }
    @Test void assessmentFirstDebitIncludesTheActualImmutableWrapperBackingMapAndEntries(){
        var probe=new Probe();probe.source=source(MoveSearch.Scheduling.STAGED);export(probe);
        var blocks=blocks(probe,Kind.ASSESSMENT);assertEquals(2,blocks.size());exact(blocks);
        for(var block:blocks){
            var copy=block.copies.getFirst();assertInstanceOf(RetainedSortedMap.class,copy.target());
            Object backing=refs((RetainedGraph.View)copy.target()).getFirst();
            assertInstanceOf(TreeMap.class,backing);assertNotSame(copy.source(),backing);assertEquals(copy.source(),backing);
        }
    }
    private static void firstCompletedBlockAbort(Kind kind,Stop at){
        var probe=new Probe();probe.source=source(kind==Kind.STAGED?MoveSearch.Scheduling.STAGED_INCREMENTAL:MoveSearch.Scheduling.STAGED);
        probe.abortKind=kind;probe.abortAt=at;
        assertSame(probe.primary,assertThrows(Abort.class,()->export(probe)));
        assertNotNull(probe.stopped);assertEquals(kind,probe.stopped.kind);
        assertEquals(List.of(probe.stopped.expected()),probe.stopped.debits,"earliest post-constructor debit must cover every finished copy");
        assertEquals(probe.stopped.expected(),probe.acceptedAtStop,"the sink accepted all completed work before throwing");
        assertTrue(probe.inputsAndOutputOwned,"all original containers and the complete product must overlap at the abort");
        assertEquals(0,RetainedGraph.measure(probe.scope).retained().nodes());assertEquals(0,RetainedGraph.measure(probe.json).retained().characters());
        assertFalse(RetainedOperation.isObserved());assertFalse(RetainedJson.active());
    }
    @Test void firstResultDebitAbortIncludesAllFiveFinishedCopies(){firstCompletedBlockAbort(Kind.RESULT,Stop.DEBIT);}
    @Test void firstResultCheckpointAbortIncludesAllFiveFinishedCopies(){firstCompletedBlockAbort(Kind.RESULT,Stop.CHECKPOINT);}
    @Test void firstStagedDebitAbortIncludesEveryFinishedCopy(){firstCompletedBlockAbort(Kind.STAGED,Stop.DEBIT);}
    @Test void firstStagedCheckpointAbortIncludesEveryFinishedCopy(){firstCompletedBlockAbort(Kind.STAGED,Stop.CHECKPOINT);}
    @Test void firstAssessmentDebitAbortIncludesWrapperMapAndEntries(){firstCompletedBlockAbort(Kind.ASSESSMENT,Stop.DEBIT);}
    @Test void firstAssessmentCheckpointAbortIncludesWrapperMapAndEntries(){firstCompletedBlockAbort(Kind.ASSESSMENT,Stop.CHECKPOINT);}
    @Test void historicalConstructorsKeepTheirWorkContractAndImmutableInputReuse(){
        var output=source(MoveSearch.Scheduling.STAGED).projectLegacy();var probe=new Probe();
        try(var scope=RetainedOperation.open(probe)){
            probe.scope=scope;long before=probe.work;
            var copy=new MoveSearch.Result(output.outcome(),output.witness(),output.events(),output.reachedStates(),output.deadEndStates(),
                output.metrics(),output.completeBoundedRelation(),output.stateAssessments(),null,null);
            var providers=List.<StagedIncrementalMoveExecution.Provider>of();var expansions=List.<StagedIncrementalMoveExecution.Expansion>of();
            var staged=new StagedIncrementalMoveExecution("work","order",providers,expansions);
            assertEquals(before,probe.work);assertSame(output.witness(),copy.witness());assertSame(output.events(),copy.events());
            assertSame(output.reachedStates(),copy.reachedStates());assertSame(output.deadEndStates(),copy.deadEndStates());
            assertSame(output.stateAssessments(),copy.stateAssessments());assertSame(providers,staged.providers());assertSame(expansions,staged.expansions());
        }
    }
}
