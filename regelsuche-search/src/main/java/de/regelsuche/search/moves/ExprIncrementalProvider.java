package de.regelsuche.search.moves;

import static de.regelsuche.search.moves.IncrementalProviderContract.*;
import de.regelsuche.transform.ExecutionWork;
import java.util.ArrayList;
import java.util.Set;

/** Explicit native expression continuation, using the same managed P02/P03 contract. */
public interface ExprIncrementalProvider extends NativeMoveProvider {
    Definition contractDefinition();
    ObjectSource<NativeMoveProof> openSource(TypedMoveSearch.State state,TypedMoveSearch.Context context,Meter meter);
    default ObjectCursor<NativeMoveProof> openSession(TypedMoveSearch.State state,TypedMoveSearch.Context context) {
        return new ManagedProviderCursor<>(contractDefinition(),new ManagedProviderCursor.Binding<>() {
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
