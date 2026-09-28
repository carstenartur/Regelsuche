package de.regelsuche.knowledge;

import de.regelsuche.retention.RetainedGraph;

public record ValidationExample(String from, String to) implements RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(from);v.reference(to);}

    public ValidationExample {
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("validation example from is required");
        }
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("validation example to is required");
        }
    }
}
