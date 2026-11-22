import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Inverse;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Scalar;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Sum;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Variable;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.Status;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Assignment;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Matrix;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.noncommutative.RationalFormulaSdk;
import java.util.Arrays;
import java.util.Map;

/** Java 25 consumer of the 0.5.4-SNAPSHOT source SDK. */
class RationalMatrixFormula {
    public static void main(String[] args) {
        var source = new Sum(new Inverse(new Inverse(new Variable(0))), new Scalar(ExactRational.ONE));
        var limits = new IterationLimits(1_000_000, 50_000, 5_000);
        var token = CancellationToken.NONE;
        var result = RationalFormulaSdk.realize(source, limits, token);
        var certificate = result.certificate().orElseThrow(() -> new IllegalStateException(result.reason()));
        var verification = RationalFormulaSdk.verify(source, certificate, limits, token);
        if (verification.status() != Status.VERIFIED) throw new IllegalStateException(verification.reason());
        var singular = new Assignment(2, Map.of(0, matrix(0, 1, 0, 0)));
        var nonsingular = new Assignment(2, Map.of(0, matrix(1, 1, 0, 2)));
        var undefined = RationalFormulaSdk.evaluate(source, certificate, singular, limits, token);
        var defined = RationalFormulaSdk.evaluate(source, certificate, nonsingular, limits, token);
        if (undefined.status() != Status.UNDEFINED || defined.status() != Status.DEFINED) {
            throw new IllegalStateException("Unexpected formula domain");
        }
        var value = defined.value().orElseThrow();
        if (!value.equals(matrix(2, 1, 0, 3))) throw new IllegalStateException("Unexpected matrix value");
        System.out.println("singular X: " + undefined.status());
        System.out.println("invertible X: " + defined.status() + ", row-major entries " + value.entries());
    }

    private static Matrix matrix(long... entries) {
        return new Matrix(2, Arrays.stream(entries).mapToObj(ExactRational::integer).toList());
    }
}
