package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.math.algorithms.iteration.FiniteIterationReduction;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.Objects;

/** SDK facade for the independent telescoping checker. */
public final class FiniteIterationVerifier {
    /** cost is null on failure; work is this verification's bounded accounting, never candidate data. */
    public record Verification(boolean verified, String reason, long work, FiniteIterationOptimizer.Cost cost) {}
    public Verification verify(FiniteIteration source, FiniteIterationOptimizer.Candidate candidate,
                               OptimizationBudget budget, CancellationToken cancellation) {
        Objects.requireNonNull(candidate);
        return verify(source,candidate.result(),candidate.certificate(),budget,cancellation);
    }
    /** Recheck the actual emitted scalar expression, allowing any supported equivalent ring polynomial. */
    public Verification verify(FiniteIteration source, Expr emitted, FiniteIterationOptimizer.Certificate certificate,
                               OptimizationBudget budget, CancellationToken cancellation) {
        var proof = new FiniteIterationReduction.Certificate(certificate.source(), certificate.antidifference());
        var check = FiniteIterationReduction.verify(source, emitted, proof, SeriesSupport.limits(budget),
            cancellation::isCancelled);
        var cost = check.verified() ? FiniteIterationOptimizer.sdkCost(check.cost()) : null;
        return new Verification(check.verified(), check.reason(), check.work(), cost);
    }
}
