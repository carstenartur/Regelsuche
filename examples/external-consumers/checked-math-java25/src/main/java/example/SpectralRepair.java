package example;

import de.regelsuche.math.algorithms.algebra.SpectralBatchRepair;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactMatrixExpression;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.sdk.optimization.algebra.SpectralBatchSdk;
import java.util.Arrays;
import java.util.List;

/** Executes a nontrivial exact repair, then lets discovery select the cheaper direct plan. */
public final class SpectralRepair {
    private SpectralRepair() {}

    public static void main(String[] args) {
        var basis = matrix(new long[][] {{1, 1, 0}, {0, 1, 1}, {0, 0, 1}});
        var inverse = matrix(new long[][] {{1, -1, 1}, {0, 1, -1}, {0, 0, 1}});
        var source = matrix(new long[][] {{1, -2, 2}, {0, -1, 2}, {0, 0, 1}});
        var problem = new SpectralBatchRepair.Problem(source, basis, inverse, 3);
        List<ExactMatrixExpression> predictors = List.of(
            predictor("P0", new long[][] {{2, -3, 3}, {0, -1, 2}, {0, 0, 1}}),
            predictor("P1", new long[][] {{1, 2, -2}, {0, 3, -2}, {0, 0, 1}}),
            predictor("P2", new long[][] {{1, -2, 2}, {0, -1, 1}, {0, 0, 0}}));
        var budget = new OptimizationBudget(20_000_000, 900_000, 8, 15_000);
        var token = CancellationToken.NONE;
        var certificate = SpectralBatchSdk.construct(problem, predictors, budget, token).requireValue();
        SpectralBatchSdk.verify(problem, certificate, budget, token).requireValue();
        var prepared = SpectralBatchSdk.prepare(problem, certificate, budget, token).requireValue();
        var input = List.of(vector(1, 2, 3), vector(-2, 4, 1), vector(5, 0, -3));
        var actual = SpectralBatchSdk.apply(prepared, input, budget, token).requireValue();
        var expected = List.of(vector(3, 4, 3), vector(-8, -2, 1), vector(-1, -6, -3));
        if (!actual.equals(expected)) throw new IllegalStateException("Repair disagrees with the direct result");
        var selected = SpectralBatchSdk.discover(source, 3, SpectralBatchRepair.Policy.defaults(), budget, token)
            .requireValue().operator();
        if (!SpectralBatchSdk.apply(selected, input, budget, token).requireValue().equals(expected))
            throw new IllegalStateException("Selected plan changed the output");
        System.out.println("repair supports: " + certificate.frequencies().stream()
            .map(SpectralBatchRepair.Frequency::support).toList()
            + "; forward rows=" + prepared.costBreakdown().forwardRows()
            + "; inverse rows=" + prepared.costBreakdown().inverseRows());
        System.out.println("repair output: " + actual);
        System.out.println("batch operations: repair=" + prepared.cost().total()
            + ", direct=" + prepared.directCost().total() + "; selected=" + selected.kind());
    }

    private static ExactMatrixExpression predictor(String name, long[][] entries) {
        return new ExactMatrixExpression.Matrix(name, matrix(entries));
    }

    private static PolynomialMatrix matrix(long[][] entries) {
        return new PolynomialMatrix(Arrays.stream(entries).map(row -> Arrays.stream(row)
            .mapToObj(value -> Polynomial.constant(Rational.of(value))).toList()).toList());
    }

    private static List<ExactRational> vector(long... values) {
        return Arrays.stream(values).mapToObj(ExactRational::integer).toList();
    }
}
