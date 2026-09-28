package de.regelsuche.search.moves;

import static de.regelsuche.search.moves.IncrementalProviderContract.*;
import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.Transformation;
import java.util.Optional;

/** Historical representation adapter to the single managed lifecycle owner. */
final class ManagedIncrementalCursor implements Cursor {
    private final ManagedProviderCursor<Transformation> cursor;
    ManagedIncrementalCursor(MoveProvider.Descriptor descriptor,Definition definition,Factory factory,MoveState state,MoveContext context) {
        cursor=new ManagedProviderCursor<>(definition,new ManagedProviderCursor.Binding<>() {
            @Override public boolean carries(){return context.carries(descriptor.requiredAssumptions(),state);}
            @Override public ObjectSource<Transformation> open(Meter meter){return factory.open(state,context,meter);}
            @Override public void requireSource(Transformation candidate){candidate.provenance().requireSource(state.expression());}
            @Override public ExecutionWork work(Transformation candidate){return candidate.executionWork();}
        });
    }
    @Override public Optional<Transformation> next(long allowance){return cursor.next(allowance);}
    @Override public Snapshot snapshot(){return cursor.snapshot();}
    @Override public void close(){cursor.close();}
}
