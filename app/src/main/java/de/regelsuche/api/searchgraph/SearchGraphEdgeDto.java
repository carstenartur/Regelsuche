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
        ruleKind = ruleKind == null ? RewriteKind.NORMALIZE : ruleKind;
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        pathIds = pathIds == null ? List.of() : List.copyOf(pathIds);
    }
}
