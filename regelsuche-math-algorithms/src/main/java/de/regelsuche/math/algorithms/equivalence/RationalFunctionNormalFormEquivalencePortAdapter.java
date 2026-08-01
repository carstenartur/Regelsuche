package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.equivalence.AssumptionAwareEquivalenceService;
import java.util.List;
import java.util.Objects;

/**
 * Validation-port adapter for the exact rational-function normal-form audit.
 */
public final class RationalFunctionNormalFormEquivalencePortAdapter
        implements AssumptionAwareEquivalenceService {

    private final RationalFunctionNormalFormEquivalenceService delegate;

    public RationalFunctionNormalFormEquivalencePortAdapter() {
        this(new RationalFunctionNormalFormEquivalenceService());
    }

    RationalFunctionNormalFormEquivalencePortAdapter(
        RationalFunctionNormalFormEquivalenceService delegate
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Evaluation evaluate(
        String leftExpression,
        String rightExpression,
        List<String> assumptions
    ) {
        RationalFunctionNormalFormEquivalenceService.Evaluation result =
            delegate.evaluate(leftExpression, rightExpression, assumptions);
        return new Evaluation(
            Status.valueOf(result.status().name()),
            result.equivalent(),
            result.leftCrossNormalForm(),
            result.rightCrossNormalForm(),
            result.requiredNonZeroFactors(),
            result.providedNonZeroFactors(),
            result.missingNonZeroFactors(),
            result.unsupportedAssumptions(),
            result.detail());
    }
}
