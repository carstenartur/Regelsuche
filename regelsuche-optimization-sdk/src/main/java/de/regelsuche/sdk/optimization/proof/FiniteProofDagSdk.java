package de.regelsuche.sdk.optimization.proof;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.proof.FiniteProofDag;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;

/** Replay a typed proof against an independently supplied finite domain. */
public final class FiniteProofDagSdk {
    private FiniteProofDagSdk() {}

    public static FiniteProofDag.Verification verify(FiniteProofDag.Source expected, FiniteProofDag.Proof proof,
            OptimizationBudget budget, CancellationToken cancellation) {
        var limits = new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
        return FiniteProofDag.verify(expected, proof, limits, cancellation::isCancelled);
    }
}
