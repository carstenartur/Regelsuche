package de.regelsuche.evolution;

import de.regelsuche.search.moves.IncrementalProviderContract;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveProvider;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.transform.Transformation;
import java.util.Optional;

/** Loads a bounded typed batch atomically and retains the bill for every generated candidate. */
final class AtomicTypedBatchCursor implements IncrementalProviderContract.Source {
    private final TypedMoveSearch.TypedProvider provider;
    private final MoveState state;
    private final MoveContext context;
    private final IncrementalProviderContract.Meter meter;
    private MoveProvider.Batch batch;
    private int index;

    AtomicTypedBatchCursor(TypedMoveSearch.TypedProvider provider, MoveState state, MoveContext context,
            IncrementalProviderContract.Meter meter) {
        this.provider = provider; this.state = state; this.context = context; this.meter = meter;
    }

    @Override public Optional<Transformation> next(long allowance) {
        if (allowance < 0) throw new IllegalArgumentException("negative batch allowance");
        if (allowance == 0) return Optional.empty();
        if (batch == null) {
            try { batch = provider.candidates(state, context); }
            catch (CheckedSchemaTaskSolver.ProviderFailure failure) {
                meter.charge(IncrementalProviderContract.Operation.LOAD, failure.observedProviderWork());
                throw failure;
            }
            meter.charge(IncrementalProviderContract.Operation.LOAD, batch.work().totalWorkUnits());
            meter.charge(batch.work().candidateWork());
        }
        return index < batch.moves().size() ? Optional.of(batch.moves().get(index++).transformation()) : Optional.empty();
    }

    @Override public IncrementalProviderContract.Status status() {
        return batch == null || index < batch.moves().size()
            ? IncrementalProviderContract.Status.READY : IncrementalProviderContract.Status.INCONCLUSIVE;
    }
}
