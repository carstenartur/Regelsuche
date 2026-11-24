package example;

import de.regelsuche.math.algorithms.algebra.BilinearProgram;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.sdk.optimization.algebra.BilinearProgramSdk;
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
    }

    private static List<ExactRational> row(long... values) {
        return Arrays.stream(values).mapToObj(ExactRational::integer).toList();
    }
}
