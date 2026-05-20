package de.regelsuche.api.searchgraph;

import java.util.List;

/**
 * Cluster DTO – groups nodes that share a structural / macro-rule context.
 *
 * <p>In Step 1 clusters are derived from incoming-edge rule-ids: nodes reached
 * via the same macro-rule sequence share a cluster. Later steps may add
 * canonical-hash based structural clustering.
 */
public record SearchGraphClusterDto(
    String id,
    String label,
    List<String> nodeIds
) {
    public SearchGraphClusterDto {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("cluster id is required");
        }
        label = label == null ? "" : label;
        nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds);
    }
}
