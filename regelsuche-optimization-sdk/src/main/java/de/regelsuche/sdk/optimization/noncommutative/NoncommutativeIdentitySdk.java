package de.regelsuche.sdk.optimization.noncommutative;

import de.regelsuche.math.algorithms.noncommutative.NoncommutativeFormula;
import de.regelsuche.math.algorithms.noncommutative.NoncommutativeIdentity;
import de.regelsuche.sdk.optimization.CancellationToken;
import java.util.List;

/** Opt-in experimental comparison of ordered polynomials over the rational numbers. */
public final class NoncommutativeIdentitySdk {
    private NoncommutativeIdentitySdk() {}

    public static NoncommutativeIdentity.Result compare(NoncommutativeFormula left, NoncommutativeFormula right,
            List<Integer> variableOrder, NoncommutativeIdentity.Limits limits, CancellationToken cancellation) {
        return NoncommutativeIdentity.compare(left, right, variableOrder, limits, cancellation::isCancelled);
    }
}
