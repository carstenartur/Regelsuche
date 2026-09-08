package de.regelsuche.evolution;

import de.regelsuche.evolution.ExactFinitePolynomialPlanCandidateEvidenceVerifier.VerifiedCandidateEvidence;
import de.regelsuche.transform.ExactTheoryEvidence;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.WorkAwareTransformationEngine;
import java.util.List;

/** One explicitly selected verified candidate, for a work-aware ordinary Source node. */
public final class VerifiedFinitePolynomialTransformationEngine implements WorkAwareTransformationEngine {
    private final String source;
    private final Transformation transformation;

    public VerifiedFinitePolynomialTransformationEngine(VerifiedCandidateEvidence evidence) {
        var verified = ExactTheoryEvidence.fromVerified(evidence);
        source = verified.binding().sourceExpression();
        transformation = Transformation.exactTheory(verified);
    }

    @Override public List<Transformation> verifiedTransformations(String expression) {
        return source.equals(expression) ? List.of(transformation) : List.of();
    }
}
