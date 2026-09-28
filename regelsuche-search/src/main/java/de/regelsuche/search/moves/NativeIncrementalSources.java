package de.regelsuche.search.moves;

import static de.regelsuche.search.moves.IncrementalProviderContract.*;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.transform.ExecutionWork;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Representation adapters only; all lane selection and managed lifecycle code is shared. */
final class NativeIncrementalSources {
    private NativeIncrementalSources() {}
    static Definition definition(NativeMoveProvider provider) {
        if(provider instanceof ExprIncrementalProvider incremental)return incremental.contractDefinition();
        return new Definition(NATIVE_REVISION,provider.descriptor().id(),Kind.NATIVE_BATCH,provider.descriptor().provenanceId(),
            NativeMoveSearch.REVISION,Transport.NATIVE_EXPR_V1,provider.mathematicalKind(),null);
    }
    static StagedIncrementalLanes.Source<NativeSearchMove> lane(NativeMoveProvider provider,TypedMoveSearch.State state,TypedMoveSearch.Context context) {
        return new Lane(provider,state,context);
    }
    private record Lane(NativeMoveProvider provider,TypedMoveSearch.State state,TypedMoveSearch.Context context)
            implements StagedIncrementalLanes.Source<NativeSearchMove> {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(provider);v.reference(state);v.reference(context);}
        @Override public MoveProvider.Descriptor descriptor(){return provider.descriptor();}
        @Override public boolean batch(){return !(provider instanceof ExprIncrementalProvider);}
        @Override public boolean nativeTransport(){return true;}
        @Override public ObjectCursor<NativeSearchMove> open(Consumer<List<NativeSearchMove>> generated,LongSupplier totalWork) {
            if(provider instanceof ExprIncrementalProvider incremental)
                return new ProofCursor(incremental.openSession(state,context),descriptor(),totalWork);
            return new ManagedProviderCursor<>(definition(provider),new BatchBinding(provider,state,context,generated));
        }
    }
    private record ProofCursor(ObjectCursor<NativeMoveProof> cursor,MoveProvider.Descriptor descriptor,LongSupplier totalWork)
            implements ObjectCursor<NativeSearchMove>,RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(cursor);v.reference(descriptor);v.reference(totalWork);}
        @Override public Optional<NativeSearchMove> next(long allowance){
            return cursor.next(allowance).map(proof->new NativeSearchMove(proof,descriptor,totalWork.getAsLong(),Set.of()));
        }
        @Override public Snapshot snapshot(){return cursor.snapshot();}
        @Override public void close(){cursor.close();}
    }
    private record BatchBinding(NativeMoveProvider provider,TypedMoveSearch.State state,TypedMoveSearch.Context context,
            Consumer<List<NativeSearchMove>> generated) implements ManagedProviderCursor.Binding<NativeSearchMove> {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(provider);v.reference(state);v.reference(context);v.reference(generated);}
        @Override public boolean carries(){return NativeMoveProvider.carries(provider.descriptor().requiredAssumptions(),state,context);}
        @Override public void requireSource(NativeSearchMove move){move.requireSource(state.expression());}
        @Override public ExecutionWork work(NativeSearchMove move){return move.executionWork();}
        @Override public ObjectSource<NativeSearchMove> open(Meter meter){
            var batch=provider.candidates(state,context);
            meter.charge(Operation.LOAD,batch.work().totalWorkUnits());meter.charge(batch.work().candidateWork());
            for(var move:batch.moves()){meter.charge(Operation.ADMISSION,1);move.requireSource(state.expression());}
            generated.accept(batch.moves());
            return new BatchSource(batch);
        }
    }
    private static final class BatchSource implements ObjectSource<NativeSearchMove>,RetainedGraph.View {
        private final NativeMoveProvider.Batch batch;private int index;
        private BatchSource(NativeMoveProvider.Batch batch){this.batch=batch;}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(batch);}
        @Override public Optional<NativeSearchMove> next(long allowance){return index<batch.moves().size()?Optional.of(batch.moves().get(index++)):Optional.empty();}
        @Override public Status status(){return index<batch.moves().size()?Status.READY:batch.complete()?Status.EXHAUSTED:Status.INCONCLUSIVE;}
    }
}
