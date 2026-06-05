package de.regelsuche.moves.search;

public interface MoveSearchEngine {
    CountableMoveSearchEngine.CountableMoveSearchResult search(
        String inputExpression,
        String targetExpression,
        int maxDepth,
        int maxStates
    );
}
