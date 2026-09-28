package de.regelsuche.search.moves;

import static de.regelsuche.search.moves.IncrementalProviderContract.*;

import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationCursor;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Explicit adapters for native text and existing typed primitive batches. No learning dependency. */
final class StagedIncrementalSources {
    private StagedIncrementalSources() {}
    static StagedIncrementalLanes.Source<SearchMove> lane(MoveProvider provider,MoveState state,MoveContext context) {
        if(!supported(provider))throw new IllegalArgumentException("unsupported staged incremental provider");
        return new StagedIncrementalLanes.Source<>() {
            @Override public MoveProvider.Descriptor descriptor(){return provider.descriptor();}
            @Override public boolean batch(){return typedBatch(provider);}
            @Override public ObjectCursor<SearchMove> open(Consumer<List<SearchMove>> generated,java.util.function.LongSupplier totalWork) {
                return new ObjectCursor<>() {
                    private List<SearchMove> batch=List.of();private int index;
                    private final Cursor cursor=StagedIncrementalSources.open(provider,state,context,moves->{batch=moves;generated.accept(moves);});
                    @Override public Optional<SearchMove> next(long allowance){
                        return cursor.next(allowance).map(candidate->typedBatch(provider)?batch.get(index++):SearchMove.from(candidate,provider.descriptor(),totalWork.getAsLong()));
                    }
                    @Override public Snapshot snapshot(){return cursor.snapshot();}
                    @Override public void close(){cursor.close();}
                };
            }
        };
    }
    static boolean supported(MoveProvider provider) {
        return provider instanceof IncrementalMoveProvider || typedBatch(provider);
    }
    static boolean typedBatch(MoveProvider provider) {
        return provider instanceof TypedMoveSearch.TypedProvider && provider.descriptor().sourceKind() == SearchMove.SourceKind.PRIMITIVE;
    }
    static Definition definition(MoveProvider provider) {
        if (provider instanceof RegisteredIncrementalMoveProvider registered) return registered.contractDefinition();
        if (provider instanceof NativeIncrementalMoveProvider nativeProvider)
            return new Definition(REVISION, provider.descriptor().id(), Kind.NATIVE_RULES, provider.descriptor().provenanceId(),
                TransformationCursor.ORDER_REVISION, Transport.PARSER_TEXT, Mathematics.PRIMITIVE, nativeProvider.definition());
        if (typedBatch(provider)) return new Definition(REVISION, provider.descriptor().id(), Kind.TYPED_PRIMITIVE_BATCH,
            provider.descriptor().provenanceId(), TypedMoveSearch.REVISION, Transport.TYPED_AST_JSON, Mathematics.PRIMITIVE, null);
        throw new IllegalArgumentException("unsupported staged incremental provider");
    }
    static Cursor open(MoveProvider provider, MoveState state, MoveContext context, Consumer<List<SearchMove>> generatedBatch) {
        if (provider instanceof RegisteredIncrementalMoveProvider registered) return registered.openSession(state, context);
        Factory factory = provider instanceof NativeIncrementalMoveProvider nativeProvider
            ? (s, c, meter) -> new NativeSource(nativeProvider.openResumableCursor(s.expression()), meter)
            : (s, c, meter) -> new BatchSource(provider.candidates(s, c), s.expression(), meter, generatedBatch);
        return new ManagedIncrementalCursor(provider.descriptor(), definition(provider), factory, state, context);
    }
    private static final class NativeSource implements Source {
        private final TransformationCursor cursor;
        private final Meter meter;
        private long mechanics, primitives;
        NativeSource(TransformationCursor cursor, Meter meter) { this.cursor = cursor; this.meter = meter; collect(); }
        @Override public Optional<Transformation> next(long allowance) {
            try { return cursor.next(allowance); } finally { collect(); }
        }
        private void collect() {
            var work = cursor.work();
            meter.charge(Operation.MATCH, work.mechanicalUnits() - mechanics);
            meter.charge(new ExecutionWork(work.primitiveRewrites() - primitives, 0, 0));
            mechanics = work.mechanicalUnits(); primitives = work.primitiveRewrites();
        }
        @Override public Status status() {
            return switch (cursor.status()) {
                case READY -> Status.READY;
                case WORK_EXHAUSTED -> Status.LIMIT;
                case EXHAUSTED -> cursor.snapshot().complete() ? Status.EXHAUSTED : Status.INCONCLUSIVE;
                case FAILED -> Status.FAILED;
                default -> Status.INCONCLUSIVE;
            };
        }
        @Override public void close() { try { cursor.close(); } finally { collect(); } }
    }
    private static final class BatchSource implements Source {
        private final MoveProvider.Batch batch;
        private int index;
        BatchSource(MoveProvider.Batch batch, String source, Meter meter, Consumer<List<SearchMove>> generated) {
            this.batch = batch;
            meter.charge(Operation.LOAD, batch.work().totalWorkUnits());
            meter.charge(batch.work().candidateWork());
            for (var move : batch.moves()) {
                meter.charge(Operation.ADMISSION, 1);
                move.provenance().requireSource(source);
            }
            generated.accept(batch.moves());
        }
        @Override public Optional<Transformation> next(long allowance) {
            return index < batch.moves().size() ? Optional.of(batch.moves().get(index++).transformation()) : Optional.empty();
        }
        @Override public Status status() {
            if (index < batch.moves().size()) return Status.READY;
            return batch.complete() ? Status.EXHAUSTED : Status.INCONCLUSIVE;
        }
    }
}
