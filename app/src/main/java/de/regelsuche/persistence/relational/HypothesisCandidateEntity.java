package de.regelsuche.persistence.relational;

import java.time.Instant;
import java.util.List;

public record HypothesisCandidateEntity(
    String id,
    String experimentId,
    String leftPattern,
    String rightPattern,
    List<String> assumptions,
    String proofStatus,
    Boolean counterexampleFound,
    double noveltyScore,
    Instant createdAt
) {
    public HypothesisCandidateEntity {
        id = SearchRunEntity.requireId(id, "id");
        experimentId = experimentId == null ? "" : experimentId;
        if (leftPattern == null || rightPattern == null) {
            throw new IllegalArgumentException("patterns must not be null");
        }
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        proofStatus = proofStatus == null ? "OBSERVED" : proofStatus;
        noveltyScore = Math.max(0.0, Math.min(1.0, noveltyScore));
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
