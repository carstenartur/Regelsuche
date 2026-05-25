package de.regelsuche.graph;

import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.transform.RewriteKind;

public record GraphEdge(
    String fromExpression,
    String toExpression,
    String transformationRule,
    int depth,
    int improvement,
    String pathId,
    String canonicalHash,
    int scoreBefore,
    int scoreAfter,
    RewriteKind rewriteKind,
    boolean mayIncreaseComplexity,
    int estimatedCostDelta,
    boolean equivalencePreservingByConstruction,
    CandidateProofStatus validationStatus
) {
    public GraphEdge(String fromExpression, String toExpression, String transformationRule, int depth, int improvement) {
        this(
            fromExpression,
            toExpression,
            transformationRule,
            depth,
            improvement,
            "",
            "",
            0,
            0,
            RewriteKind.NORMALIZE,
            false,
            0,
            true,
            CandidateProofStatus.OBSERVED
        );
    }
}
