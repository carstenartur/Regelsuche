package example;

import de.regelsuche.math.algorithms.algebra.BilinearIntegerSchedule;
import de.regelsuche.math.algorithms.algebra.BilinearProgram;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.sdk.optimization.algebra.BilinearProgramSdk;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/** Run with the current source SDK and Java 25; this API is not in released 0.5.3. */
class BilinearSearch {
    public static void main(String[] args) {
        var forms = List.of(row(1, 0), row(0, 1), row(1, 1));
        var tensor = new BilinearProgram.Tensor(2, 2,
            List.of(row(1, 0, 0, 0), row(0, 1, 1, 0), row(0, 0, 0, 1)));
        var request = new BilinearProgram.Request(tensor, forms, forms, 3);
        var result = BilinearProgramSdk.search(request, OptimizationBudget.DEFAULT, CancellationToken.NONE);
        var witness = result.value().orElseThrow(() -> new IllegalStateException(result.reason()));
        System.out.println(witness.cost());

        // Give the search only the coefficient tensor and product limit.
        var budget = new OptimizationBudget(3_000_000, 2_000, 32, 10_000);
        var found = BilinearProgramSdk.discover(tensor, 3, budget, CancellationToken.NONE);
        var discovery = found.value().orElseThrow(() -> new IllegalStateException(found.reason()));
        var program = discovery.witness().program();

        // Replay both the program coefficients and the lower-bound minor against the source.
        var checked = BilinearProgramSdk.certifyProductBounds(discovery.request(), program,
            discovery.lowerBound(), budget, CancellationToken.NONE);
        var interval = checked.value().orElseThrow(() -> new IllegalStateException(checked.reason()));
        System.out.printf("discovered rational bilinear products: [%d,%d]; optimal=%s%n",
            interval.lowerBound(), interval.upperBound(), interval.productOptimal());
        var evaluation = BilinearProgramSdk.evaluate(discovery.request(), program, row(2, 3), row(4, 5),
            budget, CancellationToken.NONE);
        System.out.println(evaluation.value().orElseThrow(() -> new IllegalStateException(evaluation.reason())));
        showIntegerSchedule(tensor, 8, List.of(BigInteger.TWO, BigInteger.valueOf(3)),
            List.of(BigInteger.valueOf(4), BigInteger.valueOf(5)));
        var wide = BigInteger.ONE.shiftLeft(2047);
        showIntegerSchedule(tensor, 2048, List.of(wide.negate(), wide.subtract(BigInteger.ONE)),
            List.of(wide.add(BigInteger.ONE), wide.negate()));
    }

    private static void showIntegerSchedule(BilinearProgram.Tensor tensor, int bits,
            List<BigInteger> left, List<BigInteger> right) {
        var bounds = List.of(bits, bits);
        var profile = new BilinearIntegerSchedule.Profile(bounds, bounds);
        var budget = new OptimizationBudget(150_000_000, 100_000, 1024, 30_000);
        var search = BilinearProgramSdk.selectInteger(tensor, 4, 5_000, profile, budget, CancellationToken.NONE);
        var selection = search.value().orElseThrow(() -> new IllegalStateException(search.reason()));
        var plan = selection.selected().schedule();
        var cost = plan.cost();

        var execution = BilinearProgramSdk.evaluateInteger(plan, left, right, budget, CancellationToken.NONE);
        var values = execution.value().orElseThrow(() -> new IllegalStateException(execution.reason()));
        var expected = List.of(left.get(0).multiply(right.get(0)),
            left.get(0).multiply(right.get(1)).add(left.get(1).multiply(right.get(0))),
            left.get(1).multiply(right.get(1)));
        if (!values.equals(expected)) throw new IllegalStateException("Unexpected convolution result");
        System.out.printf("%d-bit schedule: products=%d, limb work=%d, allocated words=%d, allocated objects=%d, exact=true%n",
            bits, cost.variableProducts(), cost.limbWork(), cost.allocatedWords(), cost.allocatedObjects());
    }

    private static List<ExactRational> row(long... values) {
        return Arrays.stream(values).mapToObj(ExactRational::integer).toList();
    }
}
