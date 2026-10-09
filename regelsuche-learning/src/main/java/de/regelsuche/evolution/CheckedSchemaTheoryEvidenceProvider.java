package de.regelsuche.evolution;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.transform.ExactTheoryEvidence;
import de.regelsuche.transform.NativeExactTheoryEvidence;
import de.regelsuche.transform.ExactTheoryEvidenceProvider;
import java.util.Optional;

/** Installed bridge accepts only the model's privately issued, immutable application capability. */
public final class CheckedSchemaTheoryEvidenceProvider implements ExactTheoryEvidenceProvider,RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){}
    @Override public Optional<ExactTheoryEvidence.Binding> bind(Object value) {
        if (!(value instanceof CheckedLearnedSchemaModel.VerifiedApplication application)) return Optional.empty();
        Object[] pending = new Object[1];
        var retained = RetainedOperation.retainCompleted(1, application, pending);
        Throwable primary = null;
        try {
            var binding = application.binding();
            pending[0] = binding;
            RetainedOperation.work(1);
            return RetainedOperation.produced(Optional.of(binding));
        } catch (RuntimeException | Error failure) {
            primary = failure;
            observeFailure(failure);
            throw failure;
        } finally {
            closeFrame(retained, primary);
        }
    }
    @Override public Optional<NativeExactTheoryEvidence.Binding> bindNative(Object value) {
        if (!(value instanceof CheckedLearnedSchemaModel.VerifiedApplication application)) return Optional.empty();
        Object[] pending = new Object[1];
        var retained = RetainedOperation.retainCompleted(1, application, pending);
        Throwable primary = null;
        try {
            var binding = application.nativeBinding();
            pending[0] = binding;
            RetainedOperation.work(1);
            return RetainedOperation.produced(Optional.of(binding));
        } catch (RuntimeException | Error failure) {
            primary = failure;
            observeFailure(failure);
            throw failure;
        } finally {
            closeFrame(retained, primary);
        }
    }

    private static void observeFailure(Throwable failure) {
        try { RetainedOperation.checkpoint(); }
        catch (RuntimeException | Error observation) {
            if (observation != failure) failure.addSuppressed(observation);
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
    @Override public Object exportNative(Object value) {
        if(!(value instanceof CheckedLearnedSchemaModel.VerifiedApplication))throw new IllegalArgumentException("private checked application required");
        return value;
    }
}
