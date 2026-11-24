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
        var evaluation = BilinearProgramSdk.evaluate(request, witness.program(), row(2, 3), row(4, 5),
            OptimizationBudget.DEFAULT, CancellationToken.NONE);
        System.out.println(witness.cost());
        System.out.println(evaluation.value().orElseThrow(() -> new IllegalStateException(evaluation.reason())));
    }

    private static List<ExactRational> row(long... values) {
        return Arrays.stream(values).mapToObj(ExactRational::integer).toList();
    }
}
