package de.regelsuche.proof;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.portfolio.SmtProofArtifacts;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.List;

/** Uses the portfolio's lossless renderer; unsupported analytic semantics are rejected. */
public final class SmtProofBridge implements ProofBridge {
    @Override
    public ProofAttempt prove(String left, String right, List<Assumption> assumptions) {
        try { return prove(ProofObligationAdapter.equality(left, right, assumptions)); }
        catch (IllegalArgumentException unsupported) { return unsupported(unsupported); }
    }

    /** Accepts the native relation/assumption model, not just equations. */
    public ProofAttempt prove(Obligation obligation) {
        try {
            return new ProofAttempt(CandidateProofStatus.FORMALLY_PROVABLE,
                SmtProofArtifacts.artifact(obligation), "smtlib2");
        } catch (IllegalArgumentException unsupported) { return unsupported(unsupported); }
    }

    private static ProofAttempt unsupported(IllegalArgumentException exception) {
        return new ProofAttempt(CandidateProofStatus.OBSERVED,
            "Unsupported SMT obligation: " + exception.getMessage(), "smtlib2");
    }
}
