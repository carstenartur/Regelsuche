package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.Expr;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.iteration.ProjectiveHandoff;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.List;

/** SDK access to exact weighted projective handoffs. */
public final class ProjectiveHandoffSdk {
    private ProjectiveHandoffSdk() {}

    public static ProjectiveHandoff.Result search(List<Expr> affine, List<Expr> scaleCandidates,
            OptimizationBudget budget, CancellationToken cancellation) {
        return ProjectiveHandoff.search(affine, scaleCandidates, limits(budget), budget.maximumCandidates(),
            cancellation::isCancelled);
    }

    public static ProjectiveHandoff.Result verify(List<Expr> affine, ProjectiveHandoff.Candidate candidate,
            OptimizationBudget budget, CancellationToken cancellation) {
        return ProjectiveHandoff.verify(affine, candidate, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
