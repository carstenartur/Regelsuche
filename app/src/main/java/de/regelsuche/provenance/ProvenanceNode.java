package de.regelsuche.provenance;

import java.util.Map;

public record ProvenanceNode(
    String id,
    ProvenanceNodeType type,
    String label,
    Map<String, String> properties
) {
    public ProvenanceNode {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
        label = label == null ? id : label;
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
