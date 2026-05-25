package de.regelsuche.graph;

import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.mining.MacroMoveExpansion;
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
    CandidateProofStatus validationStatus,
    MacroMoveExpansion macroMoveExpansion
) {
    public GraphEdge(
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
        this(
            fromExpression,
            toExpression,
            transformationRule,
            depth,
            improvement,
            pathId,
            canonicalHash,
            scoreBefore,
            scoreAfter,
            rewriteKind,
            mayIncreaseComplexity,
            estimatedCostDelta,
            equivalencePreservingByConstruction,
            validationStatus,
            null
        );
    }

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
            CandidateProofStatus.OBSERVED,
            null
        );
    }
}
