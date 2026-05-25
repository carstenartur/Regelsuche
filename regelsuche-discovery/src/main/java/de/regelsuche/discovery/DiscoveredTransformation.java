package de.regelsuche.discovery;

import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.scoring.ExpressionScore;
import java.time.Instant;
import java.util.List;

public record DiscoveredTransformation(
    String id,
    String originalExpression,
    String improvedExpression,
    List<TransformationStep> steps,
    ExpressionScore originalScore,
    ExpressionScore improvedScore,
    int totalImprovement,
    CandidateProofStatus validationStatus,
    Instant discoveredAt,
    String canonicalHash
) {
    public DiscoveredTransformation {
        if (id == null || id.isBlank() || originalExpression == null || improvedExpression == null) {
            throw new IllegalArgumentException("id and expressions are required");
        }
        steps = List.copyOf(steps);
        discoveredAt = discoveredAt == null ? Instant.now() : discoveredAt;
        validationStatus = validationStatus == null ? CandidateProofStatus.OBSERVED : validationStatus;
        canonicalHash = canonicalHash == null ? "" : canonicalHash;
    }
}
