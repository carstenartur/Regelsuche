package de.regelsuche.persistence.relational;

import java.time.Instant;
import java.util.List;

public record DiscoveryExperimentEntity(
    String id,
    String name,
    String description,
    String status,
    List<String> searchRunIds,
    Instant createdAt,
    Instant updatedAt
) {
    public DiscoveryExperimentEntity {
        id = SearchRunEntity.requireId(id, "id");
        name = name == null ? id : name;
        description = description == null ? "" : description;
        status = status == null ? "DRAFT" : status;
        searchRunIds = searchRunIds == null ? List.of() : List.copyOf(searchRunIds);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }
}
