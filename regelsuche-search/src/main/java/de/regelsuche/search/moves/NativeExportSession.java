package de.regelsuche.search.moves;

import de.regelsuche.retention.*;

/** A separate paid output phase. Shares the same explicit graph visitor and lexical operation observer. */
final class NativeExportSession implements RetainedOperation.Sink {
    private NativeMoveSearch.Result source;
    private final long budget;
    private final SearchExpressionStore.Limits limits;
    private RetainedOperation operation;
    private RetainedJson.Scope json;
    private MoveSearch.Result projection;
    private long work,peakNodes,peakCharacters,peakReferences;
    private boolean complete=true,closing;
    private String detail="";
    private NativeExportSession(NativeMoveSearch.Result source,long budget,SearchExpressionStore.Limits limits){
        if(budget<0)throw new IllegalArgumentException("negative export work budget");
        this.source=java.util.Objects.requireNonNull(source);this.budget=budget;this.limits=java.util.Objects.requireNonNull(limits);work=3;
    }
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(source);v.reference(limits);v.reference(operation);v.reference(json);v.reference(projection);v.reference(detail);}
    @Override public void executionWork(long amount){if(amount<0)throw new IllegalArgumentException("negative export work");work=Math.addExact(work,amount);}
    @Override public void validationWork(long amount){executionWork(amount);}
    @Override public long observedWork(){return work;}
    @Override public void checkpoint(){observe(this,!closing);}
    private RetainedGraph.Observation observe(Object value,boolean enforce){
        RetainedGraph.Observation observation;
        try{observation=RetainedGraph.measure(value);}
        catch(RetainedGraph.Unmeasured unknown){observation=unknown.attempted();fail("NATIVE_EXPORT_RETENTION_UNSUPPORTED:"+unknown.getMessage());}
        executionWork(observation.work());peakNodes=Math.max(peakNodes,observation.peak().nodes());
        peakCharacters=Math.max(peakCharacters,observation.peak().characters());peakReferences=Math.max(peakReferences,observation.peak().references());
        if(peakNodes>limits.nodes() || peakCharacters>limits.characters() || peakReferences>limits.references())fail("NATIVE_EXPORT_RETENTION_EXHAUSTED");
        if(work>budget)fail("NATIVE_EXPORT_WORK_EXHAUSTED");
        if(enforce && !complete)throw new SearchExecution.ResourceLimit();return observation;
    }
    private void fail(String reason){complete=false;if(detail.isEmpty())detail=reason;}
    static NativeMoveSearch.ExportResult run(NativeMoveSearch.Result source,long budget,SearchExpressionStore.Limits limits){
        var session=new NativeExportSession(source,budget,limits);return session.run();
    }
    private NativeMoveSearch.ExportResult run(){
        operation=RetainedOperation.open(this);
        try{
            json=RetainedJson.open();checkpoint();projection=source.projectLegacy();checkpoint();
        }catch(SearchExecution.ResourceLimit exhausted){projection=null;}
        finally{
            closing=true;
            try{if(json!=null)json.close();}
            finally{json=null;operation.close();operation=null;source=null;executionWork(3);}
        }
        if(work>budget)fail("NATIVE_EXPORT_WORK_EXHAUSTED");if(!complete)projection=null;
        // Receipt scaffolding is observed too. Replacing scalar values does not alter its graph shape.
        var zero=new RetainedGraph.Usage(0,0,0);executionWork(8);
        var attempted=new NativeMoveSearch.ExportResult(projection,new NativeMoveSearch.ExportAccounting(work,budget,zero,zero,complete,detail));
        var retained=observe(attempted,false).retained();
        if(!complete && projection!=null){projection=null;executionWork(2);retained=observe(new NativeMoveSearch.ExportResult(null,attempted.accounting()),false).retained();}
        executionWork(3);if(work>budget){fail("NATIVE_EXPORT_WORK_EXHAUSTED");projection=null;}
        return new NativeMoveSearch.ExportResult(projection,new NativeMoveSearch.ExportAccounting(work,budget,
            new RetainedGraph.Usage(peakNodes,peakCharacters,peakReferences),retained,complete,detail));
    }
}
