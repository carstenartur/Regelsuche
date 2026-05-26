package de.regelsuche.provenance;

import java.util.Map;

public record ProvenanceEdge(
    String fromId,
    String toId,
    ProvenanceEdgeType type,
    Map<String, String> properties
) {
    public ProvenanceEdge {
        if (fromId == null || fromId.isBlank() || toId == null || toId.isBlank()) {
            throw new IllegalArgumentException("fromId and toId are required");
        }
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
