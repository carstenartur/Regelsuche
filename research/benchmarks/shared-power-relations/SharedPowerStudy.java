import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.modular.SharedPowerEvaluation;
import de.regelsuche.math.algorithms.modular.SharedPowerSearch;
import java.math.BigInteger;
import java.util.List;

import static de.regelsuche.math.algorithms.modular.SharedPowerPlan.*;

/** Deterministic structural comparison and independently evaluated observations; no timing claim. */
public final class SharedPowerStudy {
    private static final IterationLimits LIMITS = new IterationLimits(2_000_000, 20_000, 20_000);

    private record Case(String id, BigInteger modulus, Request request, Weights weights) {}

    private SharedPowerStudy() {}

    public static void main(String[] args) {
        var weights = new Weights(1, 1, 8);
        var blsPrime = new BigInteger("1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab", 16);
        var cases = List.of(
                new Case("exact-consecutive", bi(1_000_003), new Request(bi(1_000_002), List.of(
                        new Output(bi(65_537), bi(1)), new Output(bi(65_538), bi(1)), new Output(bi(65_539), bi(1)))), weights),
                new Case("squares-1019", bi(1019), squarePair(bi(1019)), weights),
                new Case("squares-31-exact-wins", bi(31), squarePair(bi(31)), new Weights(1, 1, 1)),
                new Case("cubes-103", bi(103), new Request(bi(102), List.of(
                        new Output(bi(36), bi(3)), new Output(bi(37), bi(3)))), weights),
                new Case("composite-15", bi(15), new Request(bi(4), List.of(new Output(bi(2), bi(2)))), weights),
                new Case("squares-381-bit", blsPrime, squarePair(blsPrime), weights));
        System.out.println("case,multiply_weight,square_weight,inverse_weight,exact_cost,selected_cost,powers,multiplies,squares,inverses,search_work,verification_work,checked_bases,evaluation_work");
        for (var example : cases) {
            var result = SharedPowerSearch.search(example.request(), example.weights(), 8, LIMITS, () -> false);
            var certificate = result.certificate().orElseThrow(() -> new IllegalStateException(result.reason()));
            var cost = result.selectedCost().orElseThrow();
            int checked = 0;
            long evaluationWork = 0;
            for (int base = 1; base <= 32; base++) {
                var x = bi(base);
                if (!x.modPow(example.request().orderMultiple(), example.modulus()).equals(BigInteger.ONE)) continue;
                var execution = SharedPowerEvaluation.evaluate(example.request(), certificate, example.modulus(), x, LIMITS, () -> false);
                var values = execution.values().orElseThrow(() -> new IllegalStateException(execution.reason()));
                evaluationWork += execution.work();
                for (int output = 0; output < values.size(); output++) {
                    var source = example.request().outputs().get(output);
                    var expected = x.modPow(source.exponent(), example.modulus())
                            .modPow(source.observationPower(), example.modulus());
                    if (!expected.equals(values.get(output).modPow(source.observationPower(), example.modulus()))) {
                        throw new IllegalStateException("OBSERVATION_MISMATCH: " + example.id());
                    }
                }
                checked++;
            }
            System.out.println(example.id() + "," + example.weights().multiplication() + ","
                    + example.weights().squaring() + "," + example.weights().inverse() + ","
                    + result.exactCost().orElseThrow().weighted() + "," + cost.weighted() + ","
                    + cost.powerCalls() + "," + cost.multiplications() + "," + cost.squarings() + ","
                    + cost.inversions() + "," + result.searchWork() + "," + result.verificationWork() + ","
                    + checked + "," + evaluationWork);
        }
    }

    private static Request squarePair(BigInteger prime) {
        var exponent = prime.add(BigInteger.ONE).divide(bi(4));
        return new Request(prime.subtract(BigInteger.ONE), List.of(new Output(exponent, bi(2)),
                new Output(exponent.negate(), bi(2))));
    }

    private static BigInteger bi(long value) { return BigInteger.valueOf(value); }
}
