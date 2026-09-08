package de.regelsuche.evolution;

import de.regelsuche.transform.ExactTheoryEvidence;
import de.regelsuche.transform.ExactTheoryEvidenceProvider;
import java.util.Optional;

/** Installed bridge: only the verifier's sealed capability is accepted. */
public final class FinitePolynomialTheoryEvidenceProvider implements ExactTheoryEvidenceProvider {
    @Override public Optional<ExactTheoryEvidence.Binding> bind(Object value) {
        if (!(value instanceof ExactFinitePolynomialPlanCandidateEvidenceVerifier.VerifiedCandidateEvidence evidence)) {
            return Optional.empty();
        }
        var data = evidence.data();
        if (!data.assumptions().isEmpty()
                || !ExactFinitePolynomialPlanCandidateEvidenceVerifier.THEORY_STEP_ID.equals(data.theoryStepId())) {
            throw new IllegalArgumentException("unsupported finite polynomial evidence contract");
        }
        return Optional.of(new ExactTheoryEvidence.Binding(data.sourceExpression(), data.transformedExpression(),
            data.theoryStepId(), evidence.evidenceHash(), data.receiptReference().artifactId(),
            data.planRunReference().artifactId(), data.canonicalWork().totalWorkUnits(), evidence.toCanonicalJson()));
    }
}
