package de.regelsuche.api.searchgraph.semantic;

import de.regelsuche.export.layout.MathLayout;
import java.util.List;

public record SemanticGraphNodeDto(
    String id,
    String canonicalExpression,
    String representativeExpression,
    String representativeLatex,
    MathLayout layout,
    List<String> variants,
    int variantCount,
    int minDepth,
    int bestScore,
    boolean onMainPath,
    boolean collapsed,
    String clusterId,
    SemanticNodeKind kind,
    boolean explicitEndpoint
) {
    public SemanticGraphNodeDto {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        canonicalExpression = canonicalExpression == null ? "" : canonicalExpression;
        representativeExpression = representativeExpression == null ? "" : representativeExpression;
        representativeLatex = representativeLatex == null ? "" : representativeLatex;
        variants = variants == null ? List.of() : List.copyOf(variants);
        clusterId = clusterId == null ? "" : clusterId;
        kind = kind == null ? SemanticNodeKind.INTERMEDIATE : kind;
    }

    public SemanticGraphNodeDto(
        String id,
        String canonicalExpression,
        String representativeExpression,
        String representativeLatex,
        MathLayout layout,
        List<String> variants,
        int variantCount,
        int minDepth,
        int bestScore,
        boolean onMainPath,
        boolean collapsed,
        String clusterId,
        SemanticNodeKind kind
    ) {
        this(id, canonicalExpression, representativeExpression, representativeLatex, layout,
            variants, variantCount, minDepth, bestScore, onMainPath, collapsed, clusterId, kind, false);
    }
}
