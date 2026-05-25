package de.regelsuche.persistence.relational;

import java.time.Instant;
import java.util.List;

public record SeedExpressionEntity(
    String id,
    String expression,
    String domain,
    String difficulty,
    List<String> tags,
    Instant createdAt
) {
    public SeedExpressionEntity {
        id = SearchRunEntity.requireId(id, "id");
        expression = SearchRunEntity.requireId(expression, "expression");
        domain = domain == null ? "general" : domain;
        difficulty = difficulty == null ? "unknown" : difficulty;
        tags = tags == null ? List.of() : List.copyOf(tags);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
