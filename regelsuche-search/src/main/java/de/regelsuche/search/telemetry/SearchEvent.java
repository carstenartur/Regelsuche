package de.regelsuche.search.telemetry;

import de.regelsuche.transform.RewriteKind;
import java.util.List;

/** A deterministic, replayable telemetry event emitted during search. */
public record SearchEvent(
    long sequence,
    SearchEventType type,
    String expression,
    String canonicalHash,
    int depth,
    int score,
    String parentCanonicalHash,
    String ruleId,
    RewriteKind rewriteKind,
    List<String> assumptions,
    int frontierSize,
    int visitedCount,
    int generatedCount,
    String pruningReason
) {
    public SearchEvent {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        expression = expression == null ? "" : expression;
        canonicalHash = canonicalHash == null ? "" : canonicalHash;
        parentCanonicalHash = parentCanonicalHash == null ? "" : parentCanonicalHash;
        ruleId = ruleId == null ? "" : ruleId;
        rewriteKind = rewriteKind == null ? RewriteKind.NORMALIZE : rewriteKind;
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        pruningReason = pruningReason == null ? "" : pruningReason;
    }
}
