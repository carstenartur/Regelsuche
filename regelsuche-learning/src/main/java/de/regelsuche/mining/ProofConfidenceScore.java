package de.regelsuche.mining;

import de.regelsuche.validation.CandidateProofStatus;

/** Rewards stronger validation states while excluding rejected hypotheses. */
public final class ProofConfidenceScore implements InterestingnessScoringModule {
    @Override
    public String name() {
        return "proofConfidence";
    }

    @Override
    public double score(InterestingnessScoringContext context) {
        CandidateProofStatus status = context.candidate().proofStatus();
        if (status == CandidateProofStatus.REJECTED) {
            return -2.0;
        }
        return status.ordinal() / (double) (CandidateProofStatus.values().length - 1);
    }
}
