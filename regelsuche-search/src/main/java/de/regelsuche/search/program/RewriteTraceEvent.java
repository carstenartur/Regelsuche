package de.regelsuche.search.program;

import de.regelsuche.search.program.RewriteProgram.SourceLocation;
import java.util.List;
import java.util.Objects;

/**
 * One deterministic interpreter event. The shape is deliberately generic so
 * the Web Workbench, an IDE debugger and an eventual textual DSL can consume
 * the same trace without depending on interpreter implementation classes.
 */
public record RewriteTraceEvent(
    long sequence,
    RewriteTraceEventType type,
    String nodeId,
    String nodeKind,
    SourceLocation sourceLocation,
    String inputExpression,
    String outputExpression,
    List<String> ruleIds,
    int candidateCount,
    boolean complete,
    String detail
) {
    public RewriteTraceEvent {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Objects.requireNonNull(type, "type");
        nodeId = requireText(nodeId, "nodeId");
        nodeKind = requireText(nodeKind, "nodeKind");
        sourceLocation = sourceLocation == null
            ? SourceLocation.unknown()
            : sourceLocation;
        inputExpression = inputExpression == null ? "" : inputExpression;
        outputExpression = outputExpression == null ? "" : outputExpression;
        ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
        if (candidateCount < 0) {
            throw new IllegalArgumentException("candidateCount must not be negative");
        }
        detail = detail == null ? "" : detail;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
