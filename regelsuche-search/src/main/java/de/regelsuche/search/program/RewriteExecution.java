package de.regelsuche.search.program;

import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.List;

/** Result of interpreting one rewrite program for one input expression. */
public record RewriteExecution(
    List<RewriteCandidate> candidates,
    boolean complete,
    TransformationWorkMetrics workMetrics
) {
    public RewriteExecution(
        List<RewriteCandidate> candidates,
        boolean complete
    ) {
        this(candidates, complete, TransformationWorkMetrics.ZERO);
    }

    public RewriteExecution {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        workMetrics = workMetrics == null
            ? TransformationWorkMetrics.ZERO
            : workMetrics;
    }

    public List<Transformation> transformations() {
        return candidates.stream()
            .map(RewriteCandidate::toTransformation)
            .toList();
    }
}
