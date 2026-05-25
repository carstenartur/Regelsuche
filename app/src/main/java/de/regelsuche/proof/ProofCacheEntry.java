package de.regelsuche.proof;

import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.Objects;

/**
 * Rich value stored in a {@link ProofCache}.
 *
 * <p>Beyond the bare {@link CandidateProofStatus} this entry carries the
 * artifact reference (e.g. {@code "<jobId>.lean"}), the prover stdout/stderr
 * digest (truncated to keep cache files small), the duration of the original
 * attempt and the creation timestamp. The combination is useful for replaying
 * a previous proof attempt without re-running the prover.</p>
 */
public record ProofCacheEntry(
    CandidateProofStatus status,
    String artifactId,
    Instant createdAt,
    String outputDigest,
    long durationMillis
) {
    public ProofCacheEntry {
        Objects.requireNonNull(status, "status");
        artifactId = artifactId == null ? "" : artifactId;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        outputDigest = outputDigest == null ? "" : outputDigest;
        if (durationMillis < 0L) {
            durationMillis = 0L;
        }
    }

    /** Convenience constructor for callers that only have a status. */
    public static ProofCacheEntry ofStatus(CandidateProofStatus status) {
        return new ProofCacheEntry(status, "", Instant.now(), "", 0L);
    }
}
