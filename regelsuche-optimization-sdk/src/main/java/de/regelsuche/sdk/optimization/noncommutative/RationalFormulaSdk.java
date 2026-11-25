package de.regelsuche.sdk.optimization.noncommutative;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaComparison;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.Certificate;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Assignment;
import de.regelsuche.sdk.optimization.CancellationToken;

/** Bounded, occurrence-sensitive realization and concrete evaluation over rational matrices. */
public final class RationalFormulaSdk {
    private RationalFormulaSdk() {}

    public static RationalFormulaRealization.Result realize(RationalFormula source, IterationLimits limits,
                                                             CancellationToken cancellation) {
        return RationalFormulaRealization.realize(source, limits, cancellation::isCancelled);
    }

    public static RationalFormulaVerifier.Verification verify(RationalFormula source, Certificate certificate,
                                                               IterationLimits limits, CancellationToken cancellation) {
        return RationalFormulaVerifier.verify(source, certificate, limits, cancellation::isCancelled);
    }

    public static RationalFormulaVerifier.Evaluation evaluate(RationalFormula source, Certificate certificate,
                                                             Assignment assignment, IterationLimits limits,
                                                             CancellationToken cancellation) {
        return RationalFormulaVerifier.evaluate(source, certificate, assignment, limits, cancellation::isCancelled);
    }

    public static RationalFormulaComparison.Result compare(RationalFormula left, RationalFormula right, Assignment center,
                                                           IterationLimits limits, CancellationToken cancellation) {
        return RationalFormulaComparison.compare(left, right, center, limits, cancellation::isCancelled);
    }

    public static RationalFormulaComparison.Result verifyComparison(RationalFormula left, RationalFormula right,
        Assignment center, RationalFormulaComparison.Evidence evidence, IterationLimits limits, CancellationToken cancellation) {
        return RationalFormulaComparison.verify(left, right, center, evidence, limits, cancellation::isCancelled);
    }
}
