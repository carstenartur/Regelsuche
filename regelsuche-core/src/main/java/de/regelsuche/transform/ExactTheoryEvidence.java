package de.regelsuche.transform;

import de.regelsuche.retention.RetainedOperation;
import java.util.Objects;
import java.util.ServiceLoader;

/** Immutable core capability. Public evidence descriptions alone cannot issue it. */
public final class ExactTheoryEvidence implements de.regelsuche.retention.RetainedGraph.View {
    @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v){v.reference(binding);}

    private final Binding binding;

    private ExactTheoryEvidence(Binding binding) {
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    public static ExactTheoryEvidence fromVerified(Object verifierOwnedEvidence) {
        Objects.requireNonNull(verifierOwnedEvidence, "verifierOwnedEvidence");
        // Keep the accepted Optional while later providers are consulted. Opaque
        // unrecognized inputs remain the caller's input, not an admitted capability.
        Object[] pending = new Object[2];
        var retained = RetainedOperation.retainCompleted(1, (Object) pending);
        Throwable primary = null;
        try {
            Binding accepted = null;
            for (ExactTheoryEvidenceProvider provider : ServiceLoader.load(
                    ExactTheoryEvidenceProvider.class, ExactTheoryEvidence.class.getClassLoader())) {
                var result = Objects.requireNonNull(provider.bind(verifierOwnedEvidence), "provider result");
                pending[1] = result;
                RetainedOperation.work(1);
                RetainedOperation.checkpoint();
                if (result.isPresent()) {
                    if (accepted != null) {
                        throw new IllegalArgumentException("ambiguous installed theory evidence providers");
                    }
                    accepted = result.orElseThrow();
                    pending[0] = result;
                    RetainedOperation.work(1);
                }
            }
            if (accepted == null) {
                throw new IllegalArgumentException("no installed verifier recognizes this evidence capability");
            }
            return RetainedOperation.produced(new ExactTheoryEvidence(accepted));
        } catch (RuntimeException | Error failure) {
            primary = failure;
            try { RetainedOperation.checkpoint(); }
            catch (RuntimeException | Error observation) {
                if (observation != failure) failure.addSuppressed(observation);
            }
            throw failure;
        } finally {
            closeFrame(retained, primary);
        }
    }

    private static void closeFrame(RetainedOperation.Frame frame, Throwable primary) {
        if (frame == null) return;
        try { frame.close(); }
        catch (RuntimeException | Error cleanup) {
            if (primary == null) throw cleanup;
            if (cleanup != primary) primary.addSuppressed(cleanup);
        }
    }

    public Binding binding() { return binding; }

    @Override public boolean equals(Object other) {
        return other instanceof ExactTheoryEvidence evidence && binding.equals(evidence.binding);
    }
    @Override public int hashCode() { return binding.hashCode(); }

    /** Observational data; constructing this record does not authorize execution. */
    public record Binding(String sourceExpression, String transformedExpression, String theoryStepId,
                          String evidenceHash, String receiptArtifactId, String runArtifactId,
                          long canonicalWorkUnits, String canonicalEvidenceJson) implements de.regelsuche.retention.RetainedGraph.View {
    @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v){v.reference(sourceExpression);v.reference(transformedExpression);v.reference(theoryStepId);v.reference(evidenceHash);v.reference(receiptArtifactId);v.reference(runArtifactId);v.reference(canonicalEvidenceJson);}

        public Binding {
            requireText(sourceExpression);
            requireText(transformedExpression);
            requireText(theoryStepId);
            requireText(canonicalEvidenceJson);
            requireHash(evidenceHash);
            requireHash(receiptArtifactId);
            requireHash(runArtifactId);
            if (sourceExpression.equals(transformedExpression) || canonicalWorkUnits < 1) {
                throw new IllegalArgumentException("theory evidence needs a changed representation and positive work");
            }
        }
        private static void requireText(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("blank evidence field");
        }
        private static void requireHash(String hash) {
            if (hash == null || !hash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid evidence/artifact identity");
            }
        }
    }
}
