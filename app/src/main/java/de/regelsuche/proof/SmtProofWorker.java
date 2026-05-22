package de.regelsuche.proof;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.mining.RuleCandidate;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * {@link ProofWorker} that emits an SMT-LIB 2 script and—when Z3 or CVC5 is
 * present on the system PATH—executes it to obtain a confirmed proof.
 *
 * <p>Without an executor the worker behaves identically to the old
 * {@link SmtProofBridge}: it generates a {@code .smt2} script and reports
 * {@link de.regelsuche.mining.CandidateProofStatus#FORMALLY_PROVABLE}.</p>
 */
public final class SmtProofWorker implements ProofWorker {

    private final ProofBridgeService service;

    /** Skeleton-only constructor (no artifact written, no executor). */
    public SmtProofWorker() {
        this(null, null);
    }

    /** Writes artifacts to {@code artifactDirectory}; tries to run Z3. */
    public SmtProofWorker(Path artifactDirectory) {
        this(artifactDirectory, ProverExecutor.z3());
    }

    /** Full constructor — caller chooses the executor (Z3, CVC5, …). */
    public SmtProofWorker(Path artifactDirectory, ProverExecutor executor) {
        this.service = new ProofBridgeService(new SmtProofBridge(), artifactDirectory, executor);
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
        return "smtlib2";
    }
}
