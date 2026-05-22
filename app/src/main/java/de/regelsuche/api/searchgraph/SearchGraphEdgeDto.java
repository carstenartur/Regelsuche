package de.regelsuche.api.searchgraph;

import de.regelsuche.transform.RewriteKind;
import java.util.List;

/**
 * Edge DTO for the Visual Search Graph.
 *
 * <p>Represents a single rewrite step from a source expression node to a target
 * expression node. The {@code pathIds} list records which discovered paths
 * traverse this edge, enabling best-path highlighting and replay overlays.
 */
public record SearchGraphEdgeDto(
    String from,
    String to,
    String ruleId,
    String ruleLatex,
    RewriteKind ruleKind,
    int scoreDelta,
    List<String> assumptions,
    List<String> pathIds,
    boolean equivalencePreserving
) {
    public SearchGraphEdgeDto {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        ruleId = ruleId == null ? "" : ruleId;
        ruleLatex = ruleLatex == null ? "" : ruleLatex;
        ruleKind = ruleKind == null ? RewriteKind.NORMALIZE : ruleKind;
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        pathIds = pathIds == null ? List.of() : List.copyOf(pathIds);
    }

    /**
     * Backwards-compatible constructor for callers that pre-date the
     * {@link #ruleLatex()} field. The LaTeX rule label is derived from
     * {@code ruleId} via the central
     * {@link de.regelsuche.export.MathPresentation#ruleLatex(String)} helper.
     */
    public SearchGraphEdgeDto(
        String from,
        String to,
        String ruleId,
        RewriteKind ruleKind,
        int scoreDelta,
        List<String> assumptions,
        List<String> pathIds,
        boolean equivalencePreserving
    ) {
        this(from, to, ruleId,
            de.regelsuche.export.MathPresentation.DEFAULT.ruleLatex(ruleId),
            ruleKind, scoreDelta, assumptions, pathIds, equivalencePreserving);
    }

    /**
     * Stage 5 — structured {@link de.regelsuche.export.layout.MathLayout MathLayout}
     * for the edge's rule caption. Derived on demand from {@link #ruleLatex()}
     * via {@link de.regelsuche.export.MathPresentation#layout(String)} so the
     * record stays codec-compatible while still surfacing the layout pipeline
     * to layout-aware front-ends and exports.
     */
    public de.regelsuche.export.layout.MathLayout layout() {
        return de.regelsuche.export.layout.MathLayout.fromLatexFragment(
            ruleLatex,
            de.regelsuche.export.layout.AstAriaRenderer.ariaLabel(ruleId)
        );
    }
}
