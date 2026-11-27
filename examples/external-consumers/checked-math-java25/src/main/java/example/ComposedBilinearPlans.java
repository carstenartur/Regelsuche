package example;

import de.regelsuche.math.algorithms.algebra.BilinearProgram;
import de.regelsuche.math.algorithms.algebra.ComposedBilinearSearch;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.sdk.optimization.algebra.ComposedBilinearSdk;
import java.util.ArrayList;
import java.util.List;

/** Optimizes the two dense linear stages of a centered componentwise product. */
public final class ComposedBilinearPlans {
    private ComposedBilinearPlans() {}

    public static void main(String[] args) {
        int n = 8;
        var center = new ArrayList<List<ExactRational>>();
        var identity = new ArrayList<List<ExactRational>>();
        var fraction = ExactRational.ONE.divide(ExactRational.integer(n));
        for (int i = 0; i < n; i++) {
            var row = new ArrayList<ExactRational>();
            var unit = new ArrayList<ExactRational>();
            for (int j = 0; j < n; j++) {
                var delta = i == j ? ExactRational.ONE : ExactRational.ZERO;
                row.add(delta.subtract(fraction));
                unit.add(delta);
            }
            center.add(row);
            identity.add(unit);
        }
        var tensorRows = new ArrayList<List<ExactRational>>();
        for (int k = 0; k < n; k++) {
            var row = new ArrayList<ExactRational>();
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++)
                row.add(center.get(k).get(i).multiply(center.get(k).get(j)));
            tensorRows.add(row);
        }
        var tensor = new BilinearProgram.Tensor(n, n, tensorRows);
        var request = new BilinearProgram.Request(tensor, center, center, n);
        var seed = new BilinearProgram(center, center, identity);
        var budget = new OptimizationBudget(30_000_000, 900_000, 32, 15_000);
        var token = CancellationToken.NONE;
        var found = ComposedBilinearSdk.search(request, seed, 1,
            ComposedBilinearSearch.Policy.residualOnly(), budget, token).requireValue();
        var prepared = found.prepared();
        ComposedBilinearSdk.verify(request, 1, prepared.plan(), budget, token).requireValue();
        var values = new ArrayList<ExactRational>();
        var expected = new ArrayList<ExactRational>();
        var mean = ExactRational.integer(9).divide(ExactRational.integer(2));
        for (int i = 1; i <= n; i++) {
            var value = ExactRational.integer(i);
            values.add(value);
            var deviation = value.subtract(mean);
            expected.add(deviation.multiply(deviation));
        }
        var actual = ComposedBilinearSdk.apply(prepared, List.of(values), List.of(values), budget, token)
            .requireValue().getFirst();
        if (!actual.equals(expected)) throw new IllegalStateException("Independent centered formula disagrees");
        System.out.println("centered product: " + actual);
        System.out.println("operations per pair: direct=" + prepared.directCost().total()
            + ", composed=" + prepared.cost().total() + "; replay=true");
        System.out.println("searched stages: " + found.attempts().stream().map(ComposedBilinearSearch.Attempt::stage)
            .distinct().toList());
    }
}
