package de.regelsuche.search.moves;

import de.regelsuche.ast.Expr;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.search.program.AstExpressionValidation;

/** One run's paid ownership observations; no strong registry survives a scan or session close. */
final class NativeRetentionSession implements de.regelsuche.retention.RetainedOperation.Sink {
    private final NativeMoveSearch.Problem problem;
    private final SearchExpressionStore store;
    private final SearchExpressionStore.Limits limits;
    private RetainedGraph.View kernel;
    private de.regelsuche.retention.RetainedOperation operation;
    private long executionWork;
    private long validationWork,retentionWork,peakNodes,peakCharacters,peakReferences;
    private boolean complete=true;
    private String detail="";
    NativeRetentionSession(NativeMoveSearch.Problem problem,SearchExpressionStore store,SearchExpressionStore.Limits limits){
        this.problem=problem;this.store=store;this.limits=limits;
    }
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(problem);v.reference(store);v.reference(limits);v.reference(kernel);v.reference(detail);v.reference(operation);}
    void ownership(RetainedGraph.View root){kernel=root;}
    void operation(de.regelsuche.retention.RetainedOperation scope){operation=scope;}
    @Override public void executionWork(long units){if(units<0)throw new IllegalArgumentException("negative native work");executionWork=Math.addExact(executionWork,units);}
    @Override public void validationWork(long units){if(units<0)throw new IllegalArgumentException("negative validation work");validationWork=Math.addExact(validationWork,units);}
    void validate(Expr expression){AstExpressionValidation.inspect(expression);}
    long work(){return Math.addExact(Math.addExact(Math.addExact(validationWork,executionWork),retentionWork),store.work());}
    boolean complete(){return complete;}
    @Override public void checkpoint(){observe(this,true);}
    void fail(String reason){complete=false;if(detail.isEmpty())detail=reason;throw new SearchExecution.ResourceLimit();}
    private RetainedGraph.Observation observe(Object root,boolean enforce){
        RetainedGraph.Observation measured;
        try { measured=RetainedGraph.measure(root); }
        catch(RetainedGraph.Unmeasured unknown) {
            measured=unknown.attempted();complete=false;if(detail.isEmpty())detail="NATIVE_RETENTION_UNSUPPORTED:"+unknown.getMessage();
        }
        retentionWork=Math.addExact(retentionWork,measured.work());
        peakNodes=Math.max(peakNodes,measured.peak().nodes());peakCharacters=Math.max(peakCharacters,measured.peak().characters());
        peakReferences=Math.max(peakReferences,measured.peak().references());
        if(peakNodes>limits.nodes() || peakCharacters>limits.characters() || peakReferences>limits.references()) {
            complete=false;if(detail.isEmpty())detail="NATIVE_RETENTION_EXHAUSTED";
        }
        if(enforce && !complete)throw new SearchExecution.ResourceLimit();
        return measured;
    }
    void finish(NativeMoveSearch.Result result){
        store.close();kernel=null;operation=null;
        var receipt=new NativeMoveSearch.Accounting();result.accounting=receipt;
        receipt.update(validationWork,executionWork,store.work(),retentionWork,peakNodes,peakCharacters,peakReferences,0,0,0,complete,detail);
        var retained=observe(result,false).retained();
        receipt.update(validationWork,executionWork,store.work(),retentionWork,peakNodes,peakCharacters,peakReferences,
            retained.nodes(),retained.characters(),retained.references(),complete,detail);
    }
}
