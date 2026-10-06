package de.regelsuche.discovery.signal;

import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Known exact identity: DFT([d|k])(j) = [L/d|j]/d, with normalization 1/L. */
public final class PeriodicFourier {
    private PeriodicFourier() { }

    public enum Plan { DIRECT, MERGE_PERIODS }

    /** Both plans use the same comb identity; merging is the only extra rewrite. */
    public static Result evaluate(FourierQuery query, Plan plan) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(plan, "plan");
        var build = new SignalWorkMeter();
        var prepared = new ArrayList<Comb>();
        if (plan == Plan.DIRECT) {
            for (var term : query.signal().terms()) {
                prepared.add(prepare(query.signal().length(), term.period(), term.weight(), build));
            }
        } else {
            var weights = new LinkedHashMap<Integer, ExactRational>();
            for (var term : query.signal().terms()) {
                build.integer(); // one period lookup/update
                var previous = weights.get(term.period());
                if (previous == null) build.cells(1);
                weights.put(term.period(), build.add(previous == null ? ExactRational.ZERO : previous,
                    term.weight()));
            }
            for (var entry : weights.entrySet()) {
                build.integer(); // zero-weight elimination
                if (!entry.getValue().isZero()) {
                    prepared.add(prepare(query.signal().length(), entry.getKey(), entry.getValue(), build));
                }
            }
        }
        var evaluate = new SignalWorkMeter();
        evaluate.cells(query.frequencies().size());
        var coefficients = new ArrayList<ExactRational>();
        for (int frequency : query.frequencies()) {
            var coefficient = ExactRational.ZERO;
            for (var comb : prepared) {
                evaluate.integer();
                if (frequency % comb.spacing() == 0) {
                    coefficient = evaluate.add(coefficient, comb.weight());
                }
            }
            coefficients.add(coefficient);
        }
        return new Result(coefficients, build.snapshot(), evaluate.snapshot());
    }

    private static Comb prepare(int length, int period, ExactRational weight, SignalWorkMeter work) {
        work.integer();
        work.cells(1);
        return new Comb(length / period, work.divide(weight, ExactRational.integer(period)));
    }

    private record Comb(int spacing, ExactRational weight) { }

    public record Result(List<ExactRational> coefficients, SignalWork construction, SignalWork evaluation) {
        public Result {
            coefficients = List.copyOf(coefficients);
            Objects.requireNonNull(construction, "construction");
            Objects.requireNonNull(evaluation, "evaluation");
        }

        public SignalWork total() { return construction.plus(evaluation); }
    }
}
