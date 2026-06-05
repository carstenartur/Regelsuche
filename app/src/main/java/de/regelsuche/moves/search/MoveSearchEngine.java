package de.regelsuche.moves.search;

public interface MoveSearchEngine {
    CountableMoveSearchEngine.CountableMoveSearchResult search(
        String inputExpression,
        String targetExpression,
        SearchConfiguration searchConfiguration
    );

    default CountableMoveSearchEngine.CountableMoveSearchResult search(
        String inputExpression,
        String targetExpression,
        int maxDepth,
        int maxStates
    ) {
        return search(inputExpression, targetExpression, SearchConfiguration.fromLegacyBounds(maxDepth, maxStates));
    }
}
