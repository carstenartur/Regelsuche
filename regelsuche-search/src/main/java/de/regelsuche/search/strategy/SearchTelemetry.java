package de.regelsuche.search.strategy;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.telemetry.NoOpSearchObserver;
import de.regelsuche.search.telemetry.SearchEvent;
import de.regelsuche.search.telemetry.SearchEventType;
import de.regelsuche.search.telemetry.SearchObserver;
import de.regelsuche.transform.Transformation;

/**
 * Emits runtime search telemetry for strategy implementations.
 *
 * <p>The no-op path returns before constructing events, canonical hashes or scores so callers that do
 * not opt into telemetry keep the original search cost profile.</p>
 */
final class SearchTelemetry {
    private final SearchObserver observer;
    private final ExpressionCanonicalizer canonicalizer;
    private final ExpressionScorer scorer;
    private long sequence;

    private SearchTelemetry(SearchProblem problem) {
        this.observer = problem.observer();
        this.canonicalizer = problem.canonicalizer();
        this.scorer = problem.scorer();
    }

    static SearchTelemetry forProblem(SearchProblem problem) {
        return new SearchTelemetry(problem);
    }

    void searchStarted(SearchState state, int frontierSize, int visitedCount) {
        emit(SearchEventType.SEARCH_STARTED, state, frontierSize, visitedCount, 0, "");
    }

    void stateDequeued(SearchState state, int frontierSize, int visitedCount) {
        emit(SearchEventType.STATE_DEQUEUED, state, frontierSize, visitedCount, 0, "");
    }

    void stateVisited(SearchState state, int frontierSize, int visitedCount) {
        emit(SearchEventType.STATE_VISITED, state, frontierSize, visitedCount, 0, "");
    }

    void stateExpanded(SearchState state, int frontierSize, int visitedCount, int transformationCount) {
        emit(SearchEventType.STATE_EXPANDED, state, frontierSize, visitedCount, transformationCount, "");
    }

    void stateEnqueued(SearchState state, int frontierSize, int visitedCount, int generatedCount) {
        emit(SearchEventType.STATE_ENQUEUED, state, frontierSize, visitedCount, generatedCount, "");
    }

    void statePrunedDuplicate(SearchState state, int frontierSize, int visitedCount, int generatedCount) {
        emit(SearchEventType.STATE_PRUNED_DUPLICATE, state, frontierSize, visitedCount, generatedCount, "visited-state-key");
    }

    void statePrunedTransposition(SearchState state, int frontierSize, int visitedCount) {
        emit(SearchEventType.STATE_PRUNED_TRANSPOSITION, state, frontierSize, visitedCount, 0, "transposition-gate");
    }

    void statePrunedDepth(SearchState state, int frontierSize, int visitedCount) {
        emit(SearchEventType.STATE_PRUNED_DEPTH, state, frontierSize, visitedCount, 0, "max-depth");
    }

    void statePrunedBudget(SearchState state, int frontierSize, int visitedCount, int generatedCount) {
        emit(SearchEventType.STATE_PRUNED_BUDGET, state, frontierSize, visitedCount, generatedCount, "max-candidates-per-state");
    }

    void transformationGenerated(
        SearchState state,
        Transformation transformation,
        int frontierSize,
        int visitedCount,
        int generatedCount
    ) {
        emitTransformation(state, transformation, frontierSize, visitedCount, generatedCount, "");
    }

    void transformationSkipped(
        SearchState state,
        Transformation transformation,
        int frontierSize,
        int visitedCount,
        int generatedCount,
        String reason
    ) {
        emitTransformation(state, transformation, frontierSize, visitedCount, generatedCount, reason);
    }

    void searchFinished(SearchState rootState, int frontierSize, int visitedCount, int exploredCount) {
        emit(SearchEventType.SEARCH_FINISHED, rootState, frontierSize, visitedCount, exploredCount, "");
    }

    private void emit(
        SearchEventType type,
        SearchState state,
        int frontierSize,
        int visitedCount,
        int generatedCount,
        String pruningReason
    ) {
        if (isNoOp()) {
            return;
        }
        observer.onEvent(new SearchEvent(
            sequence++,
            type,
            state.expression(),
            state.canonicalHash(),
            state.depth(),
            state.score().weightedTotal(),
            parentCanonicalHash(state),
            state.parentExpression(),
            state.appliedRuleId(),
            state.appliedRuleKind(),
            state.assumptions(),
            frontierSize,
            visitedCount,
            generatedCount,
            pruningReason
        ));
    }

    private void emitTransformation(
        SearchState state,
        Transformation transformation,
        int frontierSize,
        int visitedCount,
        int generatedCount,
        String pruningReason
    ) {
        if (isNoOp()) {
            return;
        }
        String expression = transformation.transformedExpression();
        observer.onEvent(new SearchEvent(
            sequence++,
            SearchEventType.TRANSFORMATION_GENERATED,
            expression,
            canonicalizer.stableHash(expression),
            state.depth() + 1,
            scorer.score(expression).weightedTotal(),
            state.canonicalHash(),
            state.expression(),
            transformation.rule(),
            transformation.kind(),
            transformation.assumptions(),
            frontierSize,
            visitedCount,
            generatedCount,
            pruningReason
        ));
    }

    private boolean isNoOp() {
        return observer == NoOpSearchObserver.INSTANCE;
    }

    private String parentCanonicalHash(SearchState state) {
        return state.parentExpression() == null ? "" : canonicalizer.stableHash(state.parentExpression());
    }
}
