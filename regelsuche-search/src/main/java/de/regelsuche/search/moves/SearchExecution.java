package de.regelsuche.search.moves;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Representation boundary of the one frontier. Neither scheduling nor proof authority lives here. */
public final class SearchExecution {
    private SearchExecution() {}
    /** Internal abort transport. Only completed, not-yet-transferred work; never mathematical authority. */
    public static final class ResourceLimit extends RuntimeException {
        private long mechanical,verification;
        private ExecutionWork mathematics=ExecutionWork.ZERO;
        ResourceLimit() {}
        public ResourceLimit paidGeneration(TransformationWorkMetrics work) {
            long updated=Math.addExact(mechanical,work.totalWorkUnits());
            var updatedMath=mathematics.plus(work.candidateWork());
            mechanical=updated;mathematics=updatedMath;return this;
        }
        ResourceLimit paidVerification(long units) {
            if(units<0)throw new IllegalArgumentException("negative aborted verification work");
            verification=Math.addExact(verification,units);return this;
        }
        ResourceLimit verificationPhase() {
            long updated=Math.addExact(verification,Math.addExact(mechanical,mathematics.canonicalWorkUnits()));
            verification=updated;mechanical=0;mathematics=ExecutionWork.ZERO;return this;
        }
        AbortedWork takeWork() {
            var result=new AbortedWork(mechanical,mathematics,verification);
            mechanical=0;mathematics=ExecutionWork.ZERO;verification=0;return result;
        }
    }
    record AbortedWork(long mechanical,ExecutionWork mathematics,long verification) {
        long total(){return Math.addExact(Math.addExact(mechanical,mathematics.canonicalWorkUnits()),verification);}
    }
    public interface Position<E> {
        E expression(); int searchDepth(); int primitiveDepth(); String previousRule();
        List<String> assumptions(); Set<String> capabilities(); int complexityDebt();
    }
    public interface Edge<E, M> {
        E targetExpression(); int primitiveStepCount(); ExecutionWork executionWork();
        String ruleId(); String ruleFamily(); List<String> assumptions();
        M withCapabilityDelta(Set<String> delta);
        void requireSource(E expression);
    }
    public interface Verification { boolean accepted(); long work(); }
    public interface Capability<E> { E sourceExpression(); }
    public interface Assessment<E> {
        int complexity(); double value(); long searchWork(); long primitiveWork();
        Map<String, ? extends Capability<E>> capabilities();
    }
    public interface Picker<M> extends AutoCloseable {
        default void initialize() {}
        default List<IncrementalProviderContract.Snapshot> batchCursorReceipts(){return List.of();}
        Optional<M> next(); TransformationWorkMetrics workMetrics(); List<M> generatedMoves(); boolean complete();
        default Optional<M> next(long allowance) { return next(); }
        default boolean accountingComplete() { return true; }
        default boolean incremental() { return false; }
        default boolean workExhausted() { return false; }
        default int nextStage() { return 0; }
        default Object executionReceipt() { return null; }
        @Override default void close() {}
    }
    interface Environment<E,S extends Position<E>,M extends Edge<E,M>,A extends Assessment<E>,V extends Verification> {
        E source(); E goal(); List<String> initialAssumptions(); MoveSearch.Budget budget();
        MoveSearch.Mode mode(); MoveSearch.Scheduling scheduling();
        S state(E expression,int depth,int primitive,String previous,List<String> assumptions,Set<String> capabilities,int debt);
        A inspect(S state); double score(S state); V verify(S state,M move); boolean carries(List<String> assumptions,S state);
        Picker<M> picker(S state);
        default void ownership(RetainedGraph.View root) {}
        default void checkpoint() {}
        default long additionalWork(){return 0;}
        default boolean ownershipComplete(){return true;}
        /** Finite result metadata only: no search, provider generation or proof application. */
        default void beginResultAssembly() {}
        default void endResultAssembly() {}
    }
    public record Expansion<S>(S source,boolean closed,List<StagedIncrementalMoveExecution.Lane> lanes) implements RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(source);v.reference(lanes);}

        public Expansion(S source,boolean closed,List<StagedIncrementalMoveExecution.Lane> lanes) {
            Object[] pending = new Object[2];
            var retained = RetainedOperation.retainCompleted(1, source, lanes, pending);
            Throwable primary = null;
            try {
                this.source = source;
                this.closed = closed;
                this.lanes = copied(pending, 0, List.copyOf(lanes), lanes, lanes.size());
                pending[1] = this;
                completed(1);
            } catch (RuntimeException | Error failure) {
                primary = failure;
                observeFailure(failure);
                throw failure;
            } finally { close(retained, primary); }
        }
    }
    public record Step<S,M,V>(S source,S target,M move,V verification) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(source);v.reference(target);v.reference(move);v.reference(verification);}
    }
    public record Event<S,M,V>(S source,S target,M move,MoveSearch.Decision decision,V verification) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(source);v.reference(target);v.reference(move);v.reference(decision);v.reference(verification);}
    }
    record Result<S,M,V,A>(MoveSearch.Outcome outcome,List<Step<S,M,V>> witness,List<Event<S,M,V>> events,
            Set<S> reachedStates,List<S> deadEndStates,MoveSearch.Metrics metrics,boolean completeBoundedRelation,
            Map<S,A> stateAssessments,List<Object> pickerReceipts,List<IncrementalProviderContract.Snapshot> batchCursorReceipts,
            List<S> assessmentOrder,List<S> reachedOrder) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(outcome);v.reference(witness);v.reference(events);v.reference(reachedStates);v.reference(deadEndStates);v.reference(metrics);v.reference(stateAssessments);v.reference(pickerReceipts);v.reference(batchCursorReceipts);v.reference(assessmentOrder);v.reference(reachedOrder);}
        Result(MoveSearch.Outcome outcome,List<Step<S,M,V>> witness,List<Event<S,M,V>> events,
                Set<S> reachedStates,List<S> deadEndStates,MoveSearch.Metrics metrics,boolean completeBoundedRelation,
                Map<S,A> stateAssessments,List<Object> pickerReceipts,List<IncrementalProviderContract.Snapshot> batchCursorReceipts,
                List<S> assessmentOrder,List<S> reachedOrder) {
            Object[] completed = new Object[10];
            var retained = RetainedOperation.retainCompleted(1, outcome, witness, events, reachedStates,
                deadEndStates, metrics, stateAssessments, pickerReceipts, batchCursorReceipts, assessmentOrder, reachedOrder, completed);
            Throwable primary = null;
            try {
                this.outcome = outcome;
                this.metrics = metrics;
                this.completeBoundedRelation = completeBoundedRelation;
                this.witness = copied(completed, 0, List.copyOf(witness), witness, witness.size());
                this.events = copied(completed, 1, List.copyOf(events), events, events.size());
                this.reachedStates = copied(completed, 2, Set.copyOf(reachedStates), reachedStates, reachedStates.size());
                this.deadEndStates = copied(completed, 3, List.copyOf(deadEndStates), deadEndStates, deadEndStates.size());
                this.stateAssessments = copied(completed, 4, Map.copyOf(stateAssessments), stateAssessments, stateAssessments.size());
                this.pickerReceipts = copied(completed, 5, List.copyOf(pickerReceipts), pickerReceipts, pickerReceipts.size());
                this.batchCursorReceipts = copied(completed, 6, List.copyOf(batchCursorReceipts), batchCursorReceipts, batchCursorReceipts.size());
                this.assessmentOrder = copied(completed, 7, List.copyOf(assessmentOrder), assessmentOrder, assessmentOrder.size());
                this.reachedOrder = copied(completed, 8, List.copyOf(reachedOrder), reachedOrder, reachedOrder.size());
                completed[9] = this;
                completed(1);
            } catch (RuntimeException | Error failure) {
                primary = failure;
                observeFailure(failure);
                throw failure;
            } finally { close(retained, primary); }
        }
    }

    /** Observe a completed copy before its debit; immutable collection reuse allocates no new copy. */
    static <T> T copied(Object[] owner,int slot,T value,Object original,int size) {
        owner[slot] = value;
        completed(value == original ? 0 : size + 1L);
        return value;
    }
    static void completed(long units) {
        RetainedOperation.work(units);
        RetainedOperation.checkpoint();
    }
    static void observeFailure(Throwable failure) {
        try { RetainedOperation.checkpoint(); }
        catch (RuntimeException | Error observation) {
            if (observation != failure) failure.addSuppressed(observation);
        }
    }
    static void close(RetainedOperation.Frame retained,Throwable primary) {
        try { if (retained != null) retained.close(); }
        catch (RuntimeException | Error cleanup) {
            if (primary == null) throw cleanup;
            if (cleanup != primary) primary.addSuppressed(cleanup);
        }
    }
}
