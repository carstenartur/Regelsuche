package de.regelsuche.proof;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.portfolio.LeanSolverBackend;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.List;

/** Generates actual bounded tactics and a closed theorem/axiom audit through the shared IR. */
public final class LeanProofBridge implements ProofBridge {
    @Override
    public ProofAttempt prove(String left, String right, List<Assumption> assumptions) {
        try { return prove(ProofObligationAdapter.equality(left, right, assumptions)); }
        catch (IllegalArgumentException unsupported) { return unsupported(unsupported); }
    }

    /** Typed goals include inequalities; no string-based alternate mathematics is used. */
    public ProofAttempt prove(Obligation obligation) {
        try {
            return new ProofAttempt(CandidateProofStatus.FORMALLY_PROVABLE,
                LeanSolverBackend.artifact(obligation), "lean4");
        } catch (IllegalArgumentException unsupported) { return unsupported(unsupported); }
    }

    private static ProofAttempt unsupported(IllegalArgumentException exception) {
        return new ProofAttempt(CandidateProofStatus.OBSERVED,
            "Unsupported Lean obligation: " + exception.getMessage(), "lean4");
    }
}
