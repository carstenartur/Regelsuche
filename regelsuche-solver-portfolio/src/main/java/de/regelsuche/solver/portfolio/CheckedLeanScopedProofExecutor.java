package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.ScopedProofDag;
import de.regelsuche.solver.ir.ScopedProofExecutor;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import java.util.Objects;

/** A concrete adapter that executes Lean and independently rebuilds its source-bound evidence. */
public final class CheckedLeanScopedProofExecutor implements ScopedProofExecutor {
    private final LeanSolverBackend kernel;
    private final LeanEvidenceRebuilder independent;

    public CheckedLeanScopedProofExecutor(LeanSolverBackend kernel, LeanEvidenceRebuilder independent) {
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        this.independent = Objects.requireNonNull(independent, "independent");
    }

    @Override
    public VerifiedReplay verify(ScopedProofDag dag) {
        Objects.requireNonNull(dag, "dag");
        var original = kernel.using(dag).executeWithEvidence(dag.obligation());
        var result = original.execution().result();
        if (result.status() != ResultStatus.CONFIRMED) {
            return new VerifiedReplay(dag.hash(), result.status(), result.message(), "", null, "", null);
        }
        try {
            var rebuilt = independent.rebuild(
                dag.obligation(), result.certificateHash(), original.directory());
            return new VerifiedReplay(dag.hash(), ResultStatus.CONFIRMED, result.message(),
                result.certificateHash(), original.directory(),
                rebuilt.receiptHash(), rebuilt.directory());
        } catch (Exception refusal) {
            return new VerifiedReplay(dag.hash(), ResultStatus.ERROR,
                "Independent proof reconstruction failed: " + refusal,
                "", null, "", null);
        }
    }
}
