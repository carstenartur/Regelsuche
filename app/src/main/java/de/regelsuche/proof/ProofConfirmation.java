package de.regelsuche.proof;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable snapshot that binds an external prover confirmation to the exact
 * hypothesis revision and assumptions that were in effect at the time.
 *
 * <p>Confirmation is only meaningful for the specific {@code candidateRevisionHash}
 * and {@code assumptionsFingerprint} it was recorded for. Any modification to
 * the candidate statement or its assumptions renders the confirmation invalid
 * and a fresh proof attempt is required.</p>
 *
 * <p>Use {@link #isValidFor(String, String)} to check whether a cached
 * confirmation still applies.</p>
 */
public record ProofConfirmation(
    String proverName,
    String proverVersion,
    String invocationCommand,
    long timeoutMillis,
    String exitState,
    String artifactHash,
    String assumptionsFingerprint,
    String candidateRevisionHash,
    Instant confirmedAt
) {
    public ProofConfirmation {
        Objects.requireNonNull(proverName, "proverName");
        Objects.requireNonNull(exitState, "exitState");
        proverVersion = proverVersion == null ? "" : proverVersion;
        invocationCommand = invocationCommand == null ? "" : invocationCommand;
        if (timeoutMillis < 0L) {
            timeoutMillis = 0L;
        }
        artifactHash = artifactHash == null ? "" : artifactHash;
        assumptionsFingerprint = assumptionsFingerprint == null ? "" : assumptionsFingerprint;
        candidateRevisionHash = candidateRevisionHash == null ? "" : candidateRevisionHash;
        confirmedAt = confirmedAt == null ? Instant.now() : confirmedAt;
    }

    /**
     * @return {@code true} only when the external prover returned
     *         {@link ProverExecutionResult.Status#PROVER_CONFIRMED}.
     */
    public boolean isConfirmed() {
        return ProverExecutionResult.Status.PROVER_CONFIRMED.name().equals(exitState);
    }

    /**
     * Checks whether this confirmation is still valid for the given candidate
     * revision and assumptions.
     *
     * <p>Returns {@code false} whenever the candidate statement or its
     * assumptions have changed since this confirmation was recorded, thereby
     * invalidating any cached proof.</p>
     *
     * @param candidateRevisionHash the current candidate revision hash.
     * @param assumptionsFingerprint the current assumptions fingerprint.
     * @return {@code true} iff both the candidate and assumptions are unchanged.
     */
    public boolean isValidFor(String candidateRevisionHash, String assumptionsFingerprint) {
        if (candidateRevisionHash == null || assumptionsFingerprint == null) {
            return false;
        }
        return this.candidateRevisionHash.equals(candidateRevisionHash)
            && this.assumptionsFingerprint.equals(assumptionsFingerprint);
    }

    /**
     * Builds a containerized reproduction command for this proof attempt.
     *
     * <p>The returned string is a {@code docker run} snippet that a developer
     * can copy-paste to re-run the prover in an isolated environment with the
     * same artifact and timeout.</p>
     *
     * @param artifactMountPath the host path to the proof artifact directory.
     * @return a reproducible {@code docker run} command string.
     */
    public String containerReproductionCommand(String artifactMountPath) {
        String mount = artifactMountPath == null ? "/proofs" : artifactMountPath;
        long timeoutSec = timeoutMillis > 0L ? timeoutMillis / 1000L : 30L;
        return String.format(
            "docker run --rm -v \"%s:/workspace\" regelsuche/prover-sandbox:%s "
                + "timeout %d %s /workspace/%s",
            mount,
            proverVersion.isBlank() ? "latest" : proverVersion,
            timeoutSec,
            proverName,
            artifactHash.isBlank() ? "<artifact>" : artifactHash
        );
    }

    /**
     * Convenience factory for a confirmation from a {@link ProverExecutionResult}.
     *
     * @param result            the execution result.
     * @param artifactHash      SHA-256 (or similar) digest of the proof artifact.
     * @param assumptionsFingerprint sorted assumptions fingerprint.
     * @param candidateRevisionHash  canonical hash of the candidate statement.
     * @param invocationCommand the exact command line that was used.
     * @param timeoutMillis     the configured timeout in milliseconds.
     * @return a new {@code ProofConfirmation} bound to the given revision.
     */
    public static ProofConfirmation of(
        ProverExecutionResult result,
        String artifactHash,
        String assumptionsFingerprint,
        String candidateRevisionHash,
        String invocationCommand,
        long timeoutMillis
    ) {
        Objects.requireNonNull(result, "result");
        return new ProofConfirmation(
            result.tool(),
            result.tool(),
            invocationCommand,
            timeoutMillis,
            result.status().name(),
            artifactHash,
            assumptionsFingerprint,
            candidateRevisionHash,
            Instant.now()
        );
    }
}
