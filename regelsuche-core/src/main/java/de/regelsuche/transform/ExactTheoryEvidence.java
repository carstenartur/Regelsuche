package de.regelsuche.transform;

import java.util.Objects;
import java.util.ServiceLoader;

/** Immutable core capability. Public evidence descriptions alone cannot issue it. */
public final class ExactTheoryEvidence {
    private final Binding binding;

    private ExactTheoryEvidence(Binding binding) {
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    public static ExactTheoryEvidence fromVerified(Object verifierOwnedEvidence) {
        Objects.requireNonNull(verifierOwnedEvidence, "verifierOwnedEvidence");
        Binding accepted = null;
        for (ExactTheoryEvidenceProvider provider : ServiceLoader.load(
                ExactTheoryEvidenceProvider.class, ExactTheoryEvidence.class.getClassLoader())) {
            var result = Objects.requireNonNull(provider.bind(verifierOwnedEvidence), "provider result");
            if (result.isPresent()) {
                if (accepted != null) {
                    throw new IllegalArgumentException("ambiguous installed theory evidence providers");
                }
                accepted = result.orElseThrow();
            }
        }
        if (accepted == null) {
            throw new IllegalArgumentException("no installed verifier recognizes this evidence capability");
        }
        return new ExactTheoryEvidence(accepted);
    }

    public Binding binding() { return binding; }

    @Override public boolean equals(Object other) {
        return other instanceof ExactTheoryEvidence evidence && binding.equals(evidence.binding);
    }
    @Override public int hashCode() { return binding.hashCode(); }

    /** Observational data; constructing this record does not authorize execution. */
    public record Binding(String sourceExpression, String transformedExpression, String theoryStepId,
                          String evidenceHash, String receiptArtifactId, String runArtifactId,
                          long canonicalWorkUnits, String canonicalEvidenceJson) {
        public Binding {
            requireText(sourceExpression);
            requireText(transformedExpression);
            requireText(theoryStepId);
            requireText(canonicalEvidenceJson);
            for (String hash : new String[] { evidenceHash, receiptArtifactId, runArtifactId }) {
                if (hash == null || !hash.matches("sha256:[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("invalid evidence/artifact identity");
                }
            }
            if (sourceExpression.equals(transformedExpression) || canonicalWorkUnits < 1) {
                throw new IllegalArgumentException("theory evidence needs a changed representation and positive work");
            }
        }
        private static void requireText(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("blank evidence field");
        }
    }
}
