package de.regelsuche.persistence.relational;

import java.time.Instant;
import java.util.List;

public record SearchRunEntity(
    String id,
    String sourceExpression,
    String targetExpression,
    String strategy,
    String status,
    int visitedStates,
    int frontierSize,
    List<String> bestPathIds,
    Instant startedAt,
    Instant finishedAt
) {
    public SearchRunEntity {
        id = requireId(id, "id");
        sourceExpression = sourceExpression == null ? "" : sourceExpression;
        targetExpression = targetExpression == null ? "" : targetExpression;
        strategy = strategy == null ? "" : strategy;
        status = status == null ? "CREATED" : status;
        if (visitedStates < 0 || frontierSize < 0) {
            throw new IllegalArgumentException("search counters must not be negative");
        }
        bestPathIds = bestPathIds == null ? List.of() : List.copyOf(bestPathIds);
        startedAt = startedAt == null ? Instant.now() : startedAt;
    }

    static String requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
