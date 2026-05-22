package de.regelsuche.proof;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.mining.RuleCandidate;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * {@link ProofWorker} that generates a Lean 4 theorem skeleton and—when a
 * Lean toolchain is present—actually runs it.
 *
 * <p>Constructed without an artifact directory or executor the worker behaves
 * identically to the old skeleton-only {@link LeanProofBridge}: it produces a
 * {@code .lean} file in memory and reports
 * {@link de.regelsuche.mining.CandidateProofStatus#FORMALLY_PROVABLE}.  Pass
 * a real {@link ProverExecutor#lean()} to upgrade to full proof execution.</p>
 */
public final class LeanProofWorker implements ProofWorker {

    private final ProofBridgeService service;

    /** Skeleton-only constructor (no artifact written, no executor). */
    public LeanProofWorker() {
        this(null, null);
    }

    /** Writes artifacts to {@code artifactDirectory}; tries to run {@code lean}. */
    public LeanProofWorker(Path artifactDirectory) {
        this(artifactDirectory, ProverExecutor.lean());
    }

    /** Full constructor. */
    public LeanProofWorker(Path artifactDirectory, ProverExecutor executor) {
        this.service = new ProofBridgeService(new LeanProofBridge(), artifactDirectory, executor);
    }

    @Override
    public Result prove(RuleCandidate candidate, List<Assumption> assumptions) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(assumptions, "assumptions");
        long start = System.currentTimeMillis();
        ProofBridgeService.ProofAttemptOutcome outcome = service.attemptWithDetails(candidate, assumptions);
        long duration = System.currentTimeMillis() - start;
        return new Result(
            outcome.candidate(),
            outcome.candidate().proofStatus(),
            outcome.attempt().artifact(),
            outcome.attempt().tool(),
            duration
        );
    }

    @Override
    public String workerId() {
        return "lean4";
    }
}
