package de.regelsuche.api.searchgraph;

import java.util.List;

/**
 * Cluster DTO – groups nodes that share a structural / macro-rule context.
 *
 * <p>Supports multiple cluster types ({@link ClusterType#RULE_USAGE},
 * {@link ClusterType#MACRO_SEQUENCE}, {@link ClusterType#STRUCTURAL_PATTERN},
 * {@link ClusterType#SCORE_BASIN}, {@link ClusterType#PROOF_STATUS}) and
 * carries the supporting path ids plus a cohesion score in [0..1].</p>
 *
 * <p>The legacy three-argument constructor is retained so existing callers
 * keep compiling; it implies {@link ClusterType#RULE_USAGE} with empty
 * supporting paths and zero cohesion.</p>
 */
public record SearchGraphClusterDto(
    String id,
    String label,
    ClusterType type,
    List<String> nodeIds,
    List<String> supportingPathIds,
    double cohesionScore
) {
    public SearchGraphClusterDto {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("cluster id is required");
        }
        label = label == null ? "" : label;
        type = type == null ? ClusterType.RULE_USAGE : type;
        nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds);
        supportingPathIds = supportingPathIds == null ? List.of() : List.copyOf(supportingPathIds);
    }

    /** Backwards-compatible constructor used by the Step-1 rule-usage assembler. */
    public SearchGraphClusterDto(String id, String label, List<String> nodeIds) {
        this(id, label, ClusterType.RULE_USAGE, nodeIds, List.of(), 0.0);
    }
}

