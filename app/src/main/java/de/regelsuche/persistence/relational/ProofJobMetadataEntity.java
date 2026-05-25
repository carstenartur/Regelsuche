package de.regelsuche.persistence.relational;

import java.time.Instant;

public record ProofJobMetadataEntity(
    String id,
    String hypothesisId,
    String prover,
    String status,
    String artifactUri,
    Instant submittedAt,
    Instant completedAt
) {
    public ProofJobMetadataEntity {
        id = SearchRunEntity.requireId(id, "id");
        hypothesisId = hypothesisId == null ? "" : hypothesisId;
        prover = prover == null ? "unknown" : prover;
        status = status == null ? "QUEUED" : status;
        artifactUri = artifactUri == null ? "" : artifactUri;
        submittedAt = submittedAt == null ? Instant.now() : submittedAt;
    }
}
