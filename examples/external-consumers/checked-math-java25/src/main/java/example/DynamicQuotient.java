package example;

import de.regelsuche.math.algorithms.polynomial.DynamicAlgebraEvaluation.Inverse;
import de.regelsuche.math.algorithms.polynomial.DynamicAlgebraEvaluation.Literal;
import de.regelsuche.math.algorithms.polynomial.DynamicAlgebraEvaluation.Request;
import de.regelsuche.math.algorithms.polynomial.DynamicAlgebraEvaluation.Status;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.sdk.optimization.polynomial.DynamicAlgebraSdk;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/** Java 25 consumer of the 0.5.4-SNAPSHOT source SDK. */
class DynamicQuotient {
    public static void main(String[] args) {
        var source = new Request(5, coefficients(-1, 0, 1), new Inverse(new Literal(coefficients(-1, 1))));
        var budget = new OptimizationBudget(100_000, 2_000, 1, 5_000);
        var token = CancellationToken.NONE;
        var result = DynamicAlgebraSdk.evaluate(source, budget, token);
        var certificate = result.value().orElseThrow(() -> new IllegalStateException(result.reason()));
        var verification = DynamicAlgebraSdk.verify(source, certificate, budget, token);
        if (verification.status() != Status.VERIFIED) throw new IllegalStateException(verification.reason());
        for (var branch : certificate.branches()) {
            var value = branch.value().map(Object::toString).orElse("undefined");
            System.out.println("factor " + branch.factor() + " => " + value);
        }
        System.out.println("factor coverage and values: " + verification.status());
    }

    private static List<BigInteger> coefficients(long... values) {
        return Arrays.stream(values).mapToObj(BigInteger::valueOf).toList();
    }
}
