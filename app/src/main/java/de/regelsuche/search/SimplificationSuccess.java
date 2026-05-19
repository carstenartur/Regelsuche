package de.regelsuche.search;

import java.time.Instant;

public record SimplificationSuccess(
    String originalExpression,
    String simplifiedExpression,
    String transformationRule,
    int depth,
    int improvement,
    Instant timestamp
) {
}
