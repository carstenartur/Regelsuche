package de.regelsuche.proof;

import de.regelsuche.validation.CandidateProofStatus;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.mining.RuleCandidate;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Uses actual generated Lean tactics and the shared exact-goal/axiom gate.
 * Without an executor it only generates an unchecked artifact. */
public final class LeanProofWorker implements ProofWorker {

    private final ProofBridgeService service;
    private final ProverExecutor executor;

    /** Generation-only constructor (no artifact written, no executor). */
    public LeanProofWorker() {
        this(null, null);
    }

    /** Writes artifacts to {@code artifactDirectory}; uses the configured pinned Lean project. */
    public LeanProofWorker(Path artifactDirectory) {
        this(artifactDirectory, ProverExecutor.lean());
    }

    /** Full constructor. */
    public LeanProofWorker(Path artifactDirectory, ProverExecutor executor) {
        this.executor = executor;
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
    public String cacheIdentity() {
        return executor == null ? "proof-cache/v2/lean4/generation-only" : executor.cacheIdentity();
    }

    @Override
    public String workerId() {
        return "lean4";
    }
}
