package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.ast.*;
import de.regelsuche.retention.*;
import de.regelsuche.search.program.AstTransportObservation;
import de.regelsuche.transform.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeExportOrderTest {
    @Test void admissionAndOpeningHaveSeparateChronologiesInTheSharedKernel(){
        var descriptor=new MoveProvider.Descriptor("graph","graph",SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,"order-fixture");
        var provider=new EngineMoveProvider(descriptor,source->source.equals("a")
            ?List.of(new Transformation("edge","b"),new Transformation("edge","c"),new Transformation("edge","b")):List.of(),true);
        // Scheduling fixture only; actual mathematical replay is tested below.
        MoveVerifier verifier=(source,move,context)->new MoveVerifier.Verification(true,1,List.of("graph edge"),"TEST_FIXTURE");
        var problem=new MoveSearch.Problem("a",MoveContext.frozen("absent"),List.of(provider),
            MovePriorityPolicy.INVENTORY_ORDER,verifier,state->state.expression().equals("b")?10:0,
            MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(2,2,0,20,1000));
        var result=new MoveSearchKernel<String,MoveState,SearchMove,StateValue.Assessment,MoveVerifier.Verification>()
            .search(new LegacySearchExecution(problem),SearchContinuationContract.PATH_SENSITIVE,null);
        assertEquals(List.of("a","b","c"),result.assessmentOrder().stream().map(MoveState::expression).toList());
        assertEquals(List.of("a","c","b"),result.reachedOrder().stream().map(MoveState::expression).toList());
        assertEquals(1,result.metrics().duplicates(),"repeated candidates must not append another order entry");
        assertEquals(result.stateAssessments().keySet(),new HashSet<>(result.assessmentOrder()));
        assertEquals(result.reachedStates(),new HashSet<>(result.reachedOrder()));
    }

    @Test void frozenOrdersSurviveOppositeBackingIterationAndCallerMutation(){
        var forward=new LinkedHashMap<String,String>();forward.put("source","one");forward.put("child","two");
        var reverse=new LinkedHashMap<String,String>();reverse.put("child","two");reverse.put("source","one");
        var order=new ArrayList<>(List.of("source","child"));
        var metrics=new MoveSearch.Metrics(0,0,0,0,0,0,0,0,0,0,0,-1,-1,Map.of());
        var first=new SearchExecution.Result<String,String,String,String>(MoveSearch.Outcome.BOUNDED_EXHAUSTED,
            List.of(),List.of(),new LinkedHashSet<>(forward.keySet()),List.of(),metrics,true,forward,List.of(),List.of(),order,order);
        var second=new SearchExecution.Result<String,String,String,String>(MoveSearch.Outcome.BOUNDED_EXHAUSTED,
            List.of(),List.of(),new LinkedHashSet<>(reverse.keySet()),List.of(),metrics,true,reverse,List.of(),List.of(),order,order);
        forward.clear();reverse.clear();order.clear();
        assertEquals(first,second);
        assertEquals(List.of("source","child"),first.assessmentOrder());
        assertEquals(List.of("source","child"),first.reachedOrder());
        assertThrows(UnsupportedOperationException.class,()->first.assessmentOrder().clear());
        assertThrows(UnsupportedOperationException.class,()->first.reachedOrder().clear());
        var references=references(first);
        assertTrue(references.stream().anyMatch(value->value==first.assessmentOrder()));
        assertTrue(references.stream().anyMatch(value->value==first.reachedOrder()));
    }

    private enum Objective implements TypedSourceOnlySearch.Objective,RetainedGraph.View {
        INSTANCE;
        @Override public TypedSourceOnlySearch.Score evaluate(TypedMoveSearch.State state){
            return new TypedSourceOnlySearch.Score(state.expression() instanceof BinaryExpr?3:1,1);
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){}
    }
    private static List<Object> references(RetainedGraph.View view){
        var references=new ArrayList<Object>();
        view.retainedReferences(new RetainedGraph.Visitor(){
            @Override public void reference(Object value){references.add(value);}
            @Override public void requireExact(Object value,Class<?> type){assertEquals(type,value.getClass());}
        });
        return references;
    }
    /** Diagnostic deltas at the real outer projection checkpoints; no production counters are changed. */
    private static final class ExportTrace implements RetainedOperation.Sink {
        NativeMoveSearch.Result source;RetainedOperation operation;RetainedJson.Scope json;
        long work,previousAssessmentWork;int assessments;
        final List<Long> deltas=new ArrayList<>();
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(source);visitor.reference(operation);visitor.reference(json);}
        @Override public void executionWork(long units){work=Math.addExact(work,units);}
        @Override public void validationWork(long units){executionWork(units);}
        @Override public void checkpoint(){
            executionWork(RetainedGraph.measure(this).work());
            Object current=references(operation).get(2);
            // Component handoff frames can be nested inside the aggregate owner.
            // Find that owner instead of depending on its being the topmost frame.
            while(current instanceof RetainedOperation.Frame frame){
                var references=references(frame);var values=(Object[])references.get(2);
                if(values.length>=6 && values[0] instanceof Map<?,?> map){
                    if(map.isEmpty())previousAssessmentWork=work;
                    else if(map.size()>assessments){
                        assertEquals(assessments+1,map.size());deltas.add(work-previousAssessmentWork);
                        previousAssessmentWork=work;assessments=map.size();
                    }
                    return;
                }
                current=references.get(1);
            }
        }
    }
    @Test void actualNativeQualityExportRecordsStableCostsAndTheFullCheckedProjection()throws Exception{
        for(var scheduling:List.of(MoveSearch.Scheduling.EAGER_CONTROL,MoveSearch.Scheduling.STAGED_INCREMENTAL)){
            var x=new VariableExpr("x");var source=new BinaryExpr(x,BinaryOperator.ADD,new NumberExpr(0));
            var a=PatternExpr.var("A");var zero=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,a,PatternExpr.num(0)),a);
            var descriptor=new MoveProvider.Descriptor("zero","zero",SearchMove.SourceKind.PRIMITIVE,
                SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,"export-order/v1");
            var primitive=new NativeMoveSearch.Primitive(descriptor,new AstRewriteTransport(List.of(zero),32,32));
            var problem=new NativeMoveSearch.Problem(source,TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION),
                List.of(primitive),MoveSearch.Mode.FAST,scheduling,new MoveSearch.Budget(2,2,0,10,1_000_000));
            NativeMoveSearch.QualityResult quality;
            try(var transport=AstTransportObservation.open()){
                quality=new NativeMoveSearch().searchUntil(problem,Objective.INSTANCE,1,SearchContinuationContract.PATH_SENSITIVE);
                assertEquals(0,transport.total(),"search and independent replay do not encode expressions");
            }
            var result=quality.search();
            assertEquals(MoveSearch.Outcome.QUALITY_REACHED,result.observedOutcome());assertEquals(1,quality.witness().size());
            assertTrue(quality.replayWork()>0);assertEquals(x,quality.incumbent().expression());
            var output=result.exportLegacy(10_000_000,SearchExpressionStore.Limits.DEFAULT);
            assertTrue(output.artifactAvailable(),output.accounting().detail());
            var trace=new ExportTrace();trace.source=result;MoveSearch.Result projected;
            try(var operation=RetainedOperation.open(trace);var json=RetainedJson.open()){
                trace.operation=operation;trace.json=json;projected=result.projectLegacy();
            }
            assertEquals(output.projection(),projected);
            assertEquals(2,trace.deltas.size());
            assertEquals(0,RetainedGraph.measure(trace.operation).retained().nodes());
            assertEquals(0,RetainedGraph.measure(trace.json).retained().characters());
            var row=new TreeMap<String,Object>();
            row.put("mode",scheduling);row.put("searchWork",result.totalWork());row.put("exportWork",output.accounting().work());
            row.put("assessmentCheckpointDeltas",trace.deltas);row.put("peak",output.accounting().peak());
            row.put("retained",output.accounting().resultRetained());row.put("witness",projected.witness());row.put("events",projected.events());
            row.put("metrics",projected.metrics());row.put("outcome",projected.outcome());row.put("complete",projected.completeBoundedRelation());
            // Ordering here only serializes the test artefact; production projection uses recorded chronology.
            row.put("reached",projected.reachedStates().stream().sorted(Comparator.comparing(MoveState::expression)).toList());
            row.put("deadEnds",projected.deadEndStates());
            var values=new TreeMap<String,Object>();projected.stateAssessments().forEach((state,value)->values.put(state.toString(),value));
            row.put("assessments",values);row.put("stagedReceipts",projected.stagedIncrementalExecution());
            System.out.println("P04_EXPORT_ORDER "+new ObjectMapper().writeValueAsString(row));
        }
    }
}
