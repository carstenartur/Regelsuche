import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.positivity.PolynomialPotential;
import de.regelsuche.math.algorithms.positivity.SosSynthesis;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.sdk.optimization.positivity.SosSynthesisSdk;
import java.util.List;
import java.util.Map;

/** Run with the current source SDK and Java 25; this API is not in released 0.5.3. */
class SosPotential {
    public static void main(String[] args) {
        var x = Polynomial.variable("x");
        var one = Polynomial.constant(Rational.ONE);
        var grid = List.of(ExactRational.ZERO, ExactRational.ONE, ExactRational.integer(2));
        var domain = new SosSynthesis.Template(List.of(Monomial.constant(), Monomial.variable("x")),
            List.of(new SosSynthesis.Premise(SosSynthesis.Relation.NONNEGATIVE, x.subtract(one),
                List.of(Monomial.constant()))), grid);
        var request = new PolynomialPotential.Request(Map.of("x", x.subtract(one)), domain,
            List.of(new Monomial(Map.of("x", 2))), grid, ExactRational.ONE, true);
        var budget = new OptimizationBudget(5_000_000, 10_000, 1, 10_000);
        var result = SosSynthesisSdk.search(request, budget, CancellationToken.NONE);
        var proof = result.certificate().orElseThrow(() -> new IllegalStateException(result.reason()));
        var checked = SosSynthesisSdk.verify(request, proof, budget, CancellationToken.NONE);
        if (!checked.valid() || !proof.potential().equals(x.pow(2))) throw new IllegalStateException("Unexpected potential");
        System.out.println("V=x^2; minimum decrease=1 on active x>=1; verified=" + checked.valid());
        System.out.println("termination under supplied transition/domain contract=" + checked.terminationUnderContract());
    }
}
