package de.regelsuche.search.moves;

import de.regelsuche.retention.RetainedGraph;
import static de.regelsuche.search.moves.IncrementalProviderContract.*;
import de.regelsuche.transform.ExecutionWork;
import java.util.ArrayList;
import java.util.Set;

/** Explicit native expression continuation, using the same managed P02/P03 contract. */
public interface ExprIncrementalProvider extends NativeMoveProvider {
    Definition contractDefinition();
    @Override default Mathematics mathematicalKind(){return contractDefinition().mathematics();}
    ObjectSource<NativeMoveProof> openSource(TypedMoveSearch.State state,TypedMoveSearch.Context context,Meter meter);
    default ObjectCursor<NativeMoveProof> openSession(TypedMoveSearch.State state,TypedMoveSearch.Context context) {
        if(contractDefinition().transport()!=Transport.NATIVE_EXPR_V1)throw new IllegalArgumentException("native cursor requires native transport");
        return new ManagedProviderCursor<>(contractDefinition(),new ManagedProviderCursor.Binding<>() {
            @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(ExprIncrementalProvider.this);v.reference(state);v.reference(context);}
            @Override public boolean carries(){return NativeMoveProvider.carries(descriptor().requiredAssumptions(),state,context);}
            @Override public ObjectSource<NativeMoveProof> open(Meter meter){return openSource(state,context,meter);}
            @Override public void requireSource(NativeMoveProof candidate){
                if(!candidate.source().equals(state.expression()))throw new IllegalArgumentException("native cursor source differs");
            }
            @Override public ExecutionWork work(NativeMoveProof candidate){return candidate.work();}
        });
    }
    @Override default Batch candidates(TypedMoveSearch.State state,TypedMoveSearch.Context context) {
        var proofs=new ArrayList<NativeMoveProof>();var cursor=openSession(state,context);
        try {
            while(true) {
                cursor.next(Long.MAX_VALUE).ifPresent(proofs::add);
                var status=cursor.snapshot().status();
                if(status!=Status.READY && status!=Status.LIMIT && status!=Status.OPEN)break;
            }
        } finally {cursor.close();}
        var receipt=cursor.snapshot();var work=receipt.work().metrics();
        return new Batch(proofs.stream().map(proof->new NativeSearchMove(proof,descriptor(),work.totalWorkUnits(),Set.of())).toList(),work,receipt.complete());
    }
}
