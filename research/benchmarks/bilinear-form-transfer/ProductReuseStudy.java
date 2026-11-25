import de.regelsuche.math.algorithms.algebra.BilinearFormDiscovery;
import de.regelsuche.math.algorithms.algebra.BilinearProductLearning;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Tensor;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Source-only transfer across output maps, with two named out-of-family controls. */
public class ProductReuseStudy {
    private static final IterationLimits LIMITS = new IterationLimits(20_000_000, 200_000, 60_000);

    public static void main(String[] args) {
        var source = tensor(row(1,0,0,0), row(0,1,1,0), row(0,0,0,1));
        var training = BilinearProductLearning.train("convolution", source, 3, LIMITS, () -> false);
        var learned = training.value().orElseThrow();
        var directions = List.of(row(1,0), row(0,1), row(1,1));
        var fixed = new BilinearProductLearning.Basis("fixed", 2, 2, directions, directions);
        long[] totals = {0L, training.work(), 0L};
        System.out.println("training work=" + training.work());
        System.out.println("target fresh learned fixed learned-basis-used");
        for (int k = 1; k <= 6; k++) {
            // Three independent triangular output combinations of the training polynomial space.
            compare("output-map-" + k, tensor(row(1,k,k,0), row(0,1,1,-k), row(0,0,0,1)),
                learned, fixed, totals);
        }
        compare("cross-term", new Tensor(2, 2, List.of(row(0,1,0,0))), learned, fixed, totals);
        compare("rectangular", new Tensor(2, 3, List.of(row(2,0,1,6,0,3))), learned, fixed, totals);
        System.out.printf("total %d %d %d%n", totals[0], totals[1], totals[2]);
    }

    private static void compare(String label, Tensor target, BilinearProductLearning.Basis learned,
                                BilinearProductLearning.Basis fixed, long[] totals) {
        var fresh = BilinearFormDiscovery.discover(target, 3, LIMITS, () -> false);
        var warm = BilinearProductLearning.apply(learned, target, 3, LIMITS, () -> false);
        var manual = BilinearProductLearning.apply(fixed, target, 3, LIMITS, () -> false);
        long expected = fresh.value().orElseThrow().witness().cost().variableMultiplications();
        for (var check : List.of(warm, manual)) {
            var discovery = check.value().orElseThrow().discovery();
            if (!Objects.equals(target, discovery.request().tensor()) || expected != discovery.lowerBound().lowerBound()
                || expected != discovery.witness().cost().variableMultiplications()) {
                throw new IllegalStateException("Unequal target or product quality at " + label);
            }
        }
        totals[0] += fresh.work();
        totals[1] += warm.work();
        totals[2] += manual.work();
        System.out.printf("%s %d %d %d %s%n", label, fresh.work(), warm.work(), manual.work(),
            warm.value().orElseThrow().reusedSource().isPresent());
    }

    private static Tensor tensor(List<ExactRational> first, List<ExactRational> second, List<ExactRational> third) {
        return new Tensor(2, 2, List.of(first, second, third));
    }

    private static List<ExactRational> row(long... values) {
        return Arrays.stream(values).mapToObj(ExactRational::integer).toList();
    }
}
