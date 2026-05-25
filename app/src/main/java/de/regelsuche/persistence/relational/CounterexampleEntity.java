package de.regelsuche.persistence.relational;

import java.time.Instant;
import java.util.List;

public record CounterexampleEntity(
    String id,
    String hypothesisId,
    String inputExpression,
    String expectedExpression,
    String actualExpression,
    List<String> assumptions,
    Instant foundAt
) {
    public CounterexampleEntity {
        id = SearchRunEntity.requireId(id, "id");
        hypothesisId = SearchRunEntity.requireId(hypothesisId, "hypothesisId");
        inputExpression = inputExpression == null ? "" : inputExpression;
        expectedExpression = expectedExpression == null ? "" : expectedExpression;
        actualExpression = actualExpression == null ? "" : actualExpression;
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        foundAt = foundAt == null ? Instant.now() : foundAt;
    }
}
