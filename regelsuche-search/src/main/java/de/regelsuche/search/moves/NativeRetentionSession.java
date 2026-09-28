package de.regelsuche.search.moves;

import de.regelsuche.ast.Expr;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.search.program.AstExpressionValidation;

/** One run's paid ownership observations; the uneconomic inventory prototype remains opt-in. */
final class NativeRetentionSession implements de.regelsuche.retention.RetainedOperation.Sink {
    private final NativeMoveSearch.Problem problem;
    private final SearchExpressionStore store;
    private final SearchExpressionStore.Limits limits;
    private RetainedGraph.Inventory inventory;
    private final RetainedGraph.Usage inventoryLimits;
    private RetainedGraph.View kernel;
    private RetainedGraph.View finalSelection;
    private TypedSourceOnlySearch.Objective externalObjective;
    private boolean lastObservationComplete;
    private de.regelsuche.retention.RetainedOperation operation;
    private long executionWork;
    private long validationWork,retentionWork,peakNodes,peakCharacters,peakReferences;
    private boolean complete=true;
    private String detail="";
    NativeRetentionSession(NativeMoveSearch.Problem problem,SearchExpressionStore store,SearchExpressionStore.Limits limits){
        this(problem,store,limits,false);
    }
    /** Package-local prototype control; ordinary native searches use the paid fresh scanner. */
    NativeRetentionSession(NativeMoveSearch.Problem problem,SearchExpressionStore store,SearchExpressionStore.Limits limits,boolean useInventory){
        this.problem=problem;this.store=store;this.limits=limits;
        inventory=useInventory?new RetainedGraph.Inventory():null;
        inventoryLimits=useInventory?new RetainedGraph.Usage(limits.nodes(),limits.characters(),limits.references()):null;
        retentionWork=useInventory?6:0; // actual inventory/backend/containers and limit value only
    }
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(problem);v.reference(store);v.reference(limits);v.reference(kernel);v.reference(finalSelection);v.reference(detail);v.reference(operation);v.reference(externalObjective);v.reference(inventory);v.reference(inventoryLimits);}
    void externalObjective(TypedSourceOnlySearch.Objective objective){executionWork(1);externalObjective=objective;}
    void ownership(RetainedGraph.View root){kernel=root;executionWork(1);}
    /** The completed output replaces closed frontier scratch; source-only replay still owns its selection. */
    void completed(RetainedGraph.View result,RetainedGraph.View selection){
        ownership(result);finalSelection=selection;executionWork(1);
    }
    void operation(de.regelsuche.retention.RetainedOperation scope){operation=scope;}
    @Override public void executionWork(long units){if(units<0)throw new IllegalArgumentException("negative native work");executionWork=Math.addExact(executionWork,units);}
    @Override public void validationWork(long units){if(units<0)throw new IllegalArgumentException("negative validation work");validationWork=Math.addExact(validationWork,units);}
    void validate(Expr expression){AstExpressionValidation.inspect(expression);}
    long work(){return Math.addExact(Math.addExact(Math.addExact(validationWork,executionWork),retentionWork),store.work());}
    @Override public long observedWork(){return work();}
    boolean complete(){return complete;}
    @Override public void checkpoint(){observe(this,true);}
    void incomplete(String reason){complete=false;if(detail.isEmpty())detail=reason;}
    void fail(String reason){incomplete(reason);throw new SearchExecution.ResourceLimit();}
    private RetainedGraph.Observation observe(Object root,boolean enforce){
        RetainedGraph.Observation measured;lastObservationComplete=true;
        try { measured=inventory==null?RetainedGraph.measure(root):inventory.measure(root,inventoryLimits); }
        catch(RetainedGraph.Unmeasured unknown) {
            measured=unknown.attempted();lastObservationComplete=false;complete=false;if(detail.isEmpty())detail="NATIVE_RETENTION_UNSUPPORTED:"+unknown.getMessage();
        }
        catch(RetainedGraph.InventoryFailure missingOwner) {
            measured=missingOwner.attempted();lastObservationComplete=false;complete=false;
            if(detail.isEmpty())detail="NATIVE_RETENTION_UNSUPPORTED:"+missingOwner.getMessage();
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
    private record Handoff(NativeRetentionSession session,RetainedGraph.View output) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(session);v.reference(output);}
    }
    private record ExternalInputs(NativeMoveSearch.Problem problem,TypedSourceOnlySearch.Objective objective) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(problem);v.reference(objective);}
    }
    void finish(NativeMoveSearch.Result result){finish(result,result);}
    void finish(NativeMoveSearch.Result result,RetainedGraph.View output){
        executionWork(5);
        var receipt=new NativeMoveSearch.Accounting();result.accounting=receipt;
        update(receipt,0,0,0);
        observe(new Handoff(this,output),false);
        if(inventory!=null){
            retentionWork=Math.addExact(retentionWork,inventory.close());inventory=null;
            retentionWork=Math.addExact(retentionWork,1);
        }
        store.close();kernel=null;finalSelection=null;
        if(operation!=null)operation.close();operation=null;executionWork(3);
        executionWork(7);
        var external=observe(new ExternalInputs(problem,externalObjective),false);
        receipt.external(external.retained(),lastObservationComplete);externalObjective=null;
        update(receipt,0,0,0);
        String measuredDetail=detail;
        var retained=observe(output,false).retained();
        update(receipt,retained.nodes(),retained.characters(),retained.references());
        if(!measuredDetail.equals(detail)) {
            retained=observe(output,false).retained();
            update(receipt,retained.nodes(),retained.characters(),retained.references());
        }
    }
    private void update(NativeMoveSearch.Accounting receipt,long nodes,long characters,long references){
        executionWork(13);
        receipt.update(validationWork,executionWork,store.work(),retentionWork,peakNodes,peakCharacters,peakReferences,
            nodes,characters,references,complete,detail);
    }
}
