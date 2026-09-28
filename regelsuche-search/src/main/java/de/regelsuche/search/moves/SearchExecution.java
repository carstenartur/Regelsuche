package de.regelsuche.search.moves;

import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Representation boundary of the one frontier. Neither scheduling nor proof authority lives here. */
public final class SearchExecution {
    private SearchExecution() {}
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
    }
    public record Expansion<S>(S source,boolean closed,List<StagedIncrementalMoveExecution.Lane> lanes) {
        public Expansion { lanes=List.copyOf(lanes); }
    }
    public record Step<S,M,V>(S source,S target,M move,V verification) {}
    public record Event<S,M,V>(S source,S target,M move,MoveSearch.Decision decision,V verification) {}
    record Result<S,M,V,A>(MoveSearch.Outcome outcome,List<Step<S,M,V>> witness,List<Event<S,M,V>> events,
            Set<S> reachedStates,List<S> deadEndStates,MoveSearch.Metrics metrics,boolean completeBoundedRelation,
            Map<S,A> stateAssessments,List<Object> pickerReceipts) {
        Result {
            witness=List.copyOf(witness);events=List.copyOf(events);reachedStates=Set.copyOf(reachedStates);
            deadEndStates=List.copyOf(deadEndStates);stateAssessments=Map.copyOf(stateAssessments);pickerReceipts=List.copyOf(pickerReceipts);
        }
    }
}
