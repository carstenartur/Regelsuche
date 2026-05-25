package de.regelsuche.example;

import java.util.List;

/** Deterministic seed expression descriptor for reproducible discovery runs. */
public record SeedExpression(
    String id,
    String expression,
    String source,
    String category,
    List<String> tags,
    List<String> assumptions
) {
    public SeedExpression {
        id = id == null ? "" : id.trim();
        expression = expression == null ? "" : expression.trim();
        source = source == null ? "local" : source.trim();
        category = category == null ? "general" : category.trim();
        tags = tags == null ? List.of() : List.copyOf(tags);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    }

    public String stableKey() {
        if (!id.isBlank()) {
            return id;
        }
        return expression;
    }
}
