package de.regelsuche.evolution;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.transform.ExactTheoryEvidence;
import de.regelsuche.transform.NativeExactTheoryEvidence;
import de.regelsuche.transform.ExactTheoryEvidenceProvider;
import java.util.Optional;

/** Installed bridge accepts only the model's privately issued, immutable application capability. */
public final class CheckedSchemaTheoryEvidenceProvider implements ExactTheoryEvidenceProvider,RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){}
    @Override public Optional<ExactTheoryEvidence.Binding> bind(Object value) {
        return value instanceof CheckedLearnedSchemaModel.VerifiedApplication application
            ? Optional.of(application.binding()) : Optional.empty();
    }
    @Override public Optional<NativeExactTheoryEvidence.Binding> bindNative(Object value) {
        return value instanceof CheckedLearnedSchemaModel.VerifiedApplication application?Optional.of(application.nativeBinding()):Optional.empty();
    }
    @Override public Object exportNative(Object value) {
        if(!(value instanceof CheckedLearnedSchemaModel.VerifiedApplication))throw new IllegalArgumentException("private checked application required");
        return value;
    }
}
