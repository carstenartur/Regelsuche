package de.regelsuche.proof;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.mining.CandidateProofStatus;
import java.util.List;

/**
 * Bridge to an external proof assistant or SMT solver.
 *
 * <p>Implementations transform a rule candidate / discovered equivalence into
 * the target proof language (Lean lemma, SMT-LIB script, ...) and report the
 * resulting status. The contract is intentionally narrow so that no specific
 * proof tool is hard-wired into the rest of the codebase.</p>
 */
public interface ProofBridge {
    /**
     * Attempt to prove that {@code left} and {@code right} are equivalent under
     * the given {@code assumptions}.
     *
     * @return the new {@link CandidateProofStatus}. Implementations should
     *         return {@link CandidateProofStatus#FORMALLY_PROVED} only if the
     *         external tool actually succeeded; otherwise return at most
     *         {@link CandidateProofStatus#FORMALLY_PROVABLE} to indicate that
     *         a proof skeleton was generated and is ready for review/execution
     *         by the tool, but has not yet been checked.
     */
    ProofAttempt prove(String left, String right, List<Assumption> assumptions);

    /** Bundles the resulting status and the generated artifact (script). */
    record ProofAttempt(CandidateProofStatus status, String artifact, String tool) {
        public ProofAttempt {
            if (status == null) {
                throw new IllegalArgumentException("status must not be null");
            }
            artifact = artifact == null ? "" : artifact;
            tool = tool == null ? "unknown" : tool;
        }
    }
}
