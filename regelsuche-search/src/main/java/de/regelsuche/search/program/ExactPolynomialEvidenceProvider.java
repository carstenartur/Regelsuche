package de.regelsuche.search.program;

import de.regelsuche.transform.ExactTheoryEvidence;
import de.regelsuche.transform.ExactTheoryEvidenceProvider;
import java.util.Optional;

/** Installed-code bridge: public result records, strings and hashes cannot issue this capability. */
public final class ExactPolynomialEvidenceProvider implements ExactTheoryEvidenceProvider {
    @Override public Optional<ExactTheoryEvidence.Binding> bind(Object evidence) {
        return evidence instanceof ExactPolynomialTransformationSource.VerifiedExecution verified
            ? Optional.of(verified.binding()) : Optional.empty();
    }
}
