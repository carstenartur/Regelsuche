package example;

import de.regelsuche.math.algorithms.algebra.BilinearProgram;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCnf;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintPlanner.Strategy;
import de.regelsuche.math.algorithms.equivalence.RationalFunctionPlanner;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.sdk.optimization.algebra.BilinearProgramSdk;
import de.regelsuche.sdk.optimization.combinatorics.FiniteConstraintSdk;
import de.regelsuche.sdk.optimization.equivalence.RationalFunctionSdk;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

/** Exercises current source APIs through the independently resolved SDK and its original-source receivers. */
public final class BoundedPlanning {
    private BoundedPlanning() {}

    public static void main(String[] args) {
        var budget = new OptimizationBudget(2_000_000, 100_000, 10, 10_000);
        var cnf = new FiniteConstraintCnf.Source(62,
            List.of(IntStream.rangeClosed(1, 62).boxed().toList()), Map.of());
        var counted = FiniteConstraintSdk.countCnf(cnf, budget, CancellationToken.NONE);
        var certificate = counted.certificate().orElseThrow(() -> new IllegalStateException(counted.reason()));
        require(FiniteConstraintSdk.verifyCnf(cnf, certificate, budget, CancellationToken.NONE).valid(), "CNF replay");
        require(counted.count().orElseThrow().equals(BigInteger.ONE.shiftLeft(62).subtract(BigInteger.ONE)), "CNF count");
        System.out.println("62-variable OR: " + counted.count().orElseThrow() + "; replay=true");

        var factor = new FiniteConstraintCounter.Factor(List.of(0, 1),
            List.of(BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO));
        var problem = new FiniteConstraintCounter.Problem(2, List.of(factor), Map.of());
        var decision = FiniteConstraintSdk.countWithPolicy(Optional.empty(), problem, Strategy.MIN_FILL,
            1, 0, budget, CancellationToken.NONE);
        var fixed = decision.value().orElseThrow();
        require(FiniteConstraintSdk.verify(problem, fixed.certificate().orElseThrow(), budget, CancellationToken.NONE).valid(), "fixed replay");
        require(decision.selectedProvider().orElseThrow().equals("MIN_FILL"), "declared fixed strategy");
        System.out.println("fixed MIN_FILL: count=" + fixed.count().orElseThrow() + "; replay=true");

        String source = "(x^2-1)/(x-1)";
        var rational = RationalFunctionSdk.simplify(source, List.of("x != 1"), budget, CancellationToken.NONE);
        require(RationalFunctionSdk.verify(source, rational.certificate().orElseThrow(), budget, CancellationToken.NONE).valid(), "rational replay");
        System.out.println("rational simplification: " + rational.status() + "; replay=true");
        var missing = RationalFunctionSdk.simplify(source, List.of(), budget, CancellationToken.NONE);
        require(missing.certificate().isEmpty(), "missing assumption must not yield certificate");
        System.out.println("missing rational condition: " + missing.status());
        var extraHole = new RationalFunctionPlanner.Certificate("x", "(x*y)/y", List.of("y != 0"));
        boolean rejected = !RationalFunctionSdk.verify("x", extraHole, budget, CancellationToken.NONE).valid();
        require(rejected, "new denominator hole");
        System.out.println("new denominator hole: rejected=" + rejected);

        var complex = new BilinearProgram.Tensor(2, 2, List.of(
            List.of(ExactRational.ONE, ExactRational.ZERO, ExactRational.ZERO, ExactRational.NEGATIVE_ONE),
            List.of(ExactRational.ZERO, ExactRational.ONE, ExactRational.ONE, ExactRational.ZERO)));
        var bound = BilinearProgramSdk.computeRationalRankBound(complex, budget, CancellationToken.NONE).value().orElseThrow();
        var discovered = BilinearProgramSdk.discover(complex, 3, budget, CancellationToken.NONE).value().orElseThrow();
        var interval = BilinearProgramSdk.certifyRationalProductBounds(discovered.request(), discovered.witness().program(),
            bound, budget, CancellationToken.NONE).value().orElseThrow();
        require(interval.lowerBound() == 3 && interval.upperBound() == 3, "matching rational rank bounds");
        System.out.println("complex multiplication rational rank: 3; replay=true");
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new IllegalStateException("Failed independent SDK check: " + description);
    }
}
