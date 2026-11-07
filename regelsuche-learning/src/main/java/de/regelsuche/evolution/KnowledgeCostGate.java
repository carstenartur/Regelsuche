package de.regelsuche.evolution;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Performance eligibility only. Neither measurements nor this decision authorize mathematics. */
public final class KnowledgeCostGate {
    public enum Decision { ADOPT, NO_GAIN, QUALITY_REGRESSION, INCOMPLETE, NO_CANDIDATE, BUDGET_EXHAUSTED, FAILED }
    public record Cost(long wallNanos, long cpuNanos) {
        public Cost {
            if (wallNanos < 0 || cpuNanos < -1) throw new IllegalArgumentException("nonnegative wall/CPU or unavailable CPU required");
        }
    }
    public record Sample(Cost cost, boolean solved, long score, boolean complete) {
        public Sample { Objects.requireNonNull(cost); }
    }
    public record Trial(Sample previous, Sample candidate) {
        public Trial { Objects.requireNonNull(previous); Objects.requireNonNull(candidate); }
    }
    private KnowledgeCostGate() { }

    /** Projected average savings must repay the actually paid cost in BOTH wall time and CPU. */
    public static Decision evaluate(Cost paid, List<Trial> trials, long futureQueries) {
        Objects.requireNonNull(paid); trials = List.copyOf(trials);
        if (futureQueries < 1 || trials.isEmpty()) throw new IllegalArgumentException("positive horizon and actual paired trials required");
        if (paid.cpuNanos() < 0 || trials.stream().anyMatch(t -> !complete(t.previous()) || !complete(t.candidate())))
            return Decision.INCOMPLETE;
        for (var trial : trials) {
            if (trial.previous().solved() && !trial.candidate().solved()
                    || trial.candidate().score() > trial.previous().score()) return Decision.QUALITY_REGRESSION;
        }
        BigInteger wall = BigInteger.ZERO, cpu = BigInteger.ZERO;
        for (var trial : trials) {
            wall = wall.add(difference(trial.previous().cost().wallNanos(), trial.candidate().cost().wallNanos()));
            cpu = cpu.add(difference(trial.previous().cost().cpuNanos(), trial.candidate().cost().cpuNanos()));
        }
        var count = BigInteger.valueOf(trials.size());
        var horizon = BigInteger.valueOf(futureQueries);
        return wall.multiply(horizon).compareTo(BigInteger.valueOf(paid.wallNanos()).multiply(count)) > 0
            && cpu.multiply(horizon).compareTo(BigInteger.valueOf(paid.cpuNanos()).multiply(count)) > 0
            ? Decision.ADOPT : Decision.NO_GAIN;
    }
    private static boolean complete(Sample sample) { return sample.complete() && sample.cost().cpuNanos() >= 0; }
    private static BigInteger difference(long previous, long candidate) {
        return BigInteger.valueOf(previous).subtract(BigInteger.valueOf(candidate));
    }
}
