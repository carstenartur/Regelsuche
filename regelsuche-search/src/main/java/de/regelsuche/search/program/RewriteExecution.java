package de.regelsuche.search.program;

import de.regelsuche.transform.Transformation;
import java.util.List;

/** Result of interpreting one rewrite program for one input expression. */
public record RewriteExecution(
    List<RewriteCandidate> candidates,
    boolean complete
) {
    public RewriteExecution {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public List<Transformation> transformations() {
        return candidates.stream()
            .map(RewriteCandidate::toTransformation)
            .toList();
    }
}
