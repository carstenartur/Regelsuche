package de.regelsuche.search.moves;

import de.regelsuche.retention.*;

/** A separate paid output phase. Shares the same explicit graph visitor and lexical operation observer. */
final class NativeExportSession implements RetainedOperation.Sink {
    private NativeMoveSearch.Result source;
    private final long budget;
    private final boolean qualificationRequested,executionQualified;
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
        qualificationRequested=source.qualificationRequested();executionQualified=source.accountingComplete();
        if(qualificationRequested)executionWork(2);
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
        var receipt=new NativeMoveSearch.ExportAccounting(budget,qualificationRequested,executionQualified);
        if(qualificationRequested)executionWork(2);
        var output=new NativeMoveSearch.ExportResult(projection,receipt);
        var retained=new RetainedGraph.Usage(0,0,0);
        boolean changed;
        do {
            settle(output,receipt,retained);
            String measuredDetail=detail;var measuredProjection=output.projection();
            retained=observe(output,false).retained();
            settle(output,receipt,retained);
            changed=!measuredDetail.equals(detail) || measuredProjection!=output.projection();
        }while(changed); // only the one-way complete -> incomplete transition can change this graph
        return output;
    }
    private void settle(NativeMoveSearch.ExportResult output,NativeMoveSearch.ExportAccounting receipt,RetainedGraph.Usage retained){
        executionWork(12);
        if(work>budget)fail("NATIVE_EXPORT_WORK_EXHAUSTED");
        if(!complete && output.projection()!=null){output.discard();projection=null;executionWork(2);}
        receipt.update(work,peakNodes,peakCharacters,peakReferences,retained,complete,detail);
    }
}
