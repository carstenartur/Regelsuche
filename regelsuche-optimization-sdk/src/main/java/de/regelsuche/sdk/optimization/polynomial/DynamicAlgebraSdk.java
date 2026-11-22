package de.regelsuche.sdk.optimization.polynomial;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.polynomial.DynamicAlgebraEvaluation;
import de.regelsuche.math.algorithms.polynomial.DynamicAlgebraEvaluation.Certificate;
import de.regelsuche.math.algorithms.polynomial.DynamicAlgebraEvaluation.Request;
import de.regelsuche.math.algorithms.polynomial.DynamicAlgebraEvaluation.Result;
import de.regelsuche.math.algorithms.polynomial.DynamicAlgebraVerifier;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;

/** Bounded evaluation and independent checking over square-free prime-field quotient algebras. */
public final class DynamicAlgebraSdk {
    private DynamicAlgebraSdk() {}

    public static Result<Certificate> evaluate(Request request, OptimizationBudget budget, CancellationToken token) {
        return DynamicAlgebraEvaluation.evaluate(request, limits(budget), token::isCancelled);
    }

    public static Result<Boolean> verify(Request request, Certificate certificate,
                                         OptimizationBudget budget, CancellationToken token) {
        return DynamicAlgebraVerifier.verify(request, certificate, limits(budget), token::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
