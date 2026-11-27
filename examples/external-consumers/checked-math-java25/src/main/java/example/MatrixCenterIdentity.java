package example;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaCenterSearch;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaComparison;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Assignment;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Matrix;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.noncommutative.RationalFormulaSdk;
import java.util.List;
import java.util.Map;

/** A matrix center unlocks a rational identity undefined at every scalar center. */
public final class MatrixCenterIdentity {
    private MatrixCenterIdentity() {}

    public static void main(String[] args) {
        var x = new RationalFormula.Variable(0);
        var y = new RationalFormula.Variable(1);
        var h = new RationalFormula.Sum(new RationalFormula.Product(x, y),
            new RationalFormula.Product(scalar(-1), new RationalFormula.Product(y, x)));
        var inverse = new RationalFormula.Inverse(h);
        var left = new RationalFormula.Product(inverse, h);
        var right = scalar(1);
        var limits = new IterationLimits(20_000_000, 500_000, 15_000);
        var token = CancellationToken.NONE;
        var scalarResult = RationalFormulaSdk.compare(left, right,
            new RationalFormulaCenterSearch.Policy(List.of(ExactRational.ZERO, ExactRational.ONE), 4), limits, token);
        if (scalarResult.status() != RationalFormulaComparison.Status.INCONCLUSIVE)
            throw new IllegalStateException("The scalar centers must be undefined");
        System.out.println("scalar grid: " + scalarResult.status());

        var center = new Assignment(2, Map.of(0, matrix(0, 1, 1, 0), 1, matrix(1, 0, 0, -1)));
        var equal = RationalFormulaSdk.compare(left, right, center, limits, token);
        var equalReplay = RationalFormulaSdk.verifyComparison(left, right, center,
            equal.evidence().orElseThrow(), limits, token);
        if (equal.status() != RationalFormulaComparison.Status.EQUAL_ON_SHARED_DOMAIN
                || equalReplay.status() != equal.status())
            throw new IllegalStateException("Matrix identity did not replay");
        System.out.println("matrix-center equality: " + equal.status() + "; replay=true");

        var impostor = new RationalFormula.Product(
            new RationalFormula.Scalar(ExactRational.ONE.negate().divide(ExactRational.integer(4))), h);
        var distinct = RationalFormulaSdk.compare(inverse, impostor, center, limits, token);
        var witnessReplay = RationalFormulaSdk.verifyComparison(inverse, impostor, center,
            distinct.evidence().orElseThrow(), limits, token);
        if (distinct.status() != RationalFormulaComparison.Status.DISTINCT
                || witnessReplay.status() != distinct.status())
            throw new IllegalStateException("Coincidence at a center must not prove an identity");
        System.out.println("same center, different functions: " + distinct.status() + "; replay=true");
    }

    private static RationalFormula.Scalar scalar(long value) {
        return new RationalFormula.Scalar(ExactRational.integer(value));
    }

    private static Matrix matrix(long a, long b, long c, long d) {
        return new Matrix(2, List.of(a, b, c, d).stream().map(ExactRational::integer).toList());
    }
}
