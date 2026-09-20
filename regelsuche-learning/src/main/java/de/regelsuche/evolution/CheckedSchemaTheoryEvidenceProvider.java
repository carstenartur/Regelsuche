package de.regelsuche.evolution;

import de.regelsuche.transform.ExactTheoryEvidence;
import de.regelsuche.transform.ExactTheoryEvidenceProvider;
import java.util.Optional;

/** Installed bridge accepts only the model's privately issued, immutable application capability. */
public final class CheckedSchemaTheoryEvidenceProvider implements ExactTheoryEvidenceProvider {
    @Override public Optional<ExactTheoryEvidence.Binding> bind(Object value) {
        return value instanceof CheckedLearnedSchemaModel.VerifiedApplication application
            ? Optional.of(application.binding()) : Optional.empty();
    }
}
