package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.modular.SharedPowerPlan.*;

/** Bounded, source-only common-power proposals. Every candidate is independently replayed. */
public final class SharedPowerSearch {
    private SharedPowerSearch() {}

    public record Result(Optional<Certificate> certificate, Optional<Cost> exactCost,
            Optional<Cost> selectedCost, String reason, long searchWork, long verificationWork) {
        public long work() { return searchWork + verificationWork; }
    }

    /** An exact proposal used unreduced representatives for every requested exponent. */
    public record Candidate(Certificate certificate, Cost cost, boolean exact) {}

    /** Distinct independently verified schedules in increasing weighted cost order. */
    public record Portfolio(List<Candidate> candidates, String reason, long searchWork, long verificationWork) {
        public Portfolio { candidates = List.copyOf(candidates); }
        public long work() { return searchWork + verificationWork; }
        long retainedCells() {
            return candidates.stream().mapToLong(candidate -> cells(candidate.certificate())).sum();
        }
    }

    /**
     * Compares exact and observation-reduced representatives under identical reuse rules,
     * each in source, ascending and descending order. The exact candidate is always retained.
     * maximumShift bounds positive multiplications by x (0..64); no global optimum is claimed.
     */
    public static Result search(Request request, Weights weights, int maximumShift,
            IterationLimits limits, BooleanSupplier cancellation) {
        return search(request, weights, maximumShift, 6, limits, cancellation);
    }

    /** Stops after at most maximumCandidates (1..6) verified proposals, with exact candidates first. */
    public static Result search(Request request, Weights weights, int maximumShift, int maximumCandidates,
            IterationLimits limits, BooleanSupplier cancellation) {
        var result = portfolio(request, weights, maximumShift, maximumCandidates, limits, cancellation);
        var best = result.candidates().stream().findFirst();
        var exact = result.candidates().stream().filter(Candidate::exact).findFirst();
        return new Result(best.map(Candidate::certificate), exact.map(Candidate::cost), best.map(Candidate::cost),
                result.reason(), result.searchWork(), result.verificationWork());
    }

    /** Retains distinct exact and observation-reduced schedules from up to six ordered proposals. */
    public static Portfolio portfolio(Request request, Weights weights, int maximumShift, int maximumCandidates,
            IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(weights);
        if (maximumShift < 0 || maximumShift > 64) throw new IllegalArgumentException("SHIFT_LIMIT");
        if (maximumCandidates < 1 || maximumCandidates > 6) throw new IllegalArgumentException("CANDIDATE_LIMIT");
        var work = new CertificateWork(limits, cancellation);
        long verificationWork = 0;
        try {
            SharedPowerVerifier.inspect(request, work);
            var distinct = new ArrayList<Candidate>();
            long retained = 0;
            int candidates = 0;
            portfolio:
            for (boolean relaxed : new boolean[]{false, true}) {
                for (int order = 0; order < 3; order++) {
                    if (candidates == maximumCandidates) break portfolio;
                    candidates++;
                    work.cells(retained + 8L * request.outputs().size() + 8);
                    var targets = targets(request, relaxed, order, work);
                    var builder = new Builder(request.orderMultiple(), weights, maximumShift, work,
                            retained + 8L * request.outputs().size());
                    var outputs = new Integer[targets.size()];
                    for (var target : targets) outputs[target.position()] = builder.ensure(target.exponent());
                    work.charge(1L + builder.steps.size() + outputs.length);
                    long buildCells = retained + 8L * outputs.length + 5L * builder.values.size();
                    work.cells(buildCells + cells(builder.steps.size(), outputs.length));
                    var certificate = new Certificate(request, new Schedule(builder.steps, List.of(outputs)));
                    long before = work.used();
                    var checked = SharedPowerVerifier.verify(request, certificate, weights, work,
                            buildCells + cells(certificate));
                    verificationWork += work.used() - before;
                    if (!checked.valid()) {
                        return new Portfolio(List.of(), checked.reason(), work.used() - verificationWork, verificationWork);
                    }
                    work.charge((1L + distinct.size()) * (1L + builder.steps.size() + outputs.length));
                    if (distinct.stream().noneMatch(prior -> prior.certificate().schedule().equals(certificate.schedule()))) {
                        retained += cells(certificate);
                        work.cells(retained);
                        distinct.add(new Candidate(certificate, checked.cost().orElseThrow(), !relaxed));
                    }
                }
            }
            work.cells(retained + 2L * distinct.size());
            distinct.sort((a, b) -> {
                work.charge(1);
                return Long.compare(a.cost().weighted(), b.cost().weighted());
            });
            work.charge(1L + distinct.size());
            return new Portfolio(distinct, "VERIFIED", work.used() - verificationWork, verificationWork);
        } catch (CertificateWork.Stop stop) {
            return new Portfolio(List.of(), stop.getMessage(), work.used() - verificationWork, verificationWork);
        }
    }

    private record Target(int position, BigInteger exponent) {}

    private static List<Target> targets(Request request, boolean relaxed, int order, CertificateWork work) {
        var result = new ArrayList<Target>();
        for (int i = 0; i < request.outputs().size(); i++) {
            var output = request.outputs().get(i);
            work.charge(1L + SharedPowerVerifier.words(request.orderMultiple())
                    + SharedPowerVerifier.words(output.exponent()));
            var modulus = relaxed ? request.orderMultiple().divide(output.observationPower()) : request.orderMultiple();
            result.add(new Target(i, output.exponent().mod(modulus)));
        }
        if (order != 0) result.sort((a, b) -> {
            work.charge(1L + SharedPowerVerifier.words(a.exponent()) + SharedPowerVerifier.words(b.exponent()));
            int comparison = a.exponent().compareTo(b.exponent());
            return order == 1 ? comparison : -comparison;
        });
        return result;
    }

    private static long cells(Certificate certificate) {
        return cells(certificate.schedule().steps().size(), certificate.schedule().outputs().size());
    }

    private static long cells(int steps, int outputs) {
        return 14L + 5L * steps + outputs;
    }

    private static final class Builder {
        private final BigInteger n;
        private final Weights weights;
        private final int maximumShift;
        private final CertificateWork work;
        private final long retained;
        private final List<Step> steps = new ArrayList<>();
        private final Map<BigInteger, Integer> values = new LinkedHashMap<>();

        Builder(BigInteger n, Weights weights, int maximumShift, CertificateWork work, long retained) {
            this.n = n;
            this.weights = weights;
            this.maximumShift = maximumShift;
            this.work = work;
            this.retained = retained;
            values.put(BigInteger.ZERO, 0);
            values.putIfAbsent(BigInteger.ONE.mod(n), 1);
        }

        int ensure(BigInteger target) {
            work.charge(1);
            var present = values.get(target);
            if (present != null) return present;
            var complement = target.negate().mod(n);
            long bestCost = powerCost(target, weights);
            int operation = 0, reference = -1, shift = 0;
            var inverse = values.get(complement);
            if (inverse != null && weights.inverse() <= bestCost) {
                bestCost = weights.inverse();
                operation = 1;
                reference = inverse;
            }
            long complementCost = powerCost(complement, weights) + weights.inverse();
            if (complementCost < bestCost) {
                bestCost = complementCost;
                operation = 2;
            }
            BigInteger start = BigInteger.ZERO;
            for (var value : values.entrySet()) {
                work.charge(1L + SharedPowerVerifier.words(target) + SharedPowerVerifier.words(value.getKey()));
                var difference = target.subtract(value.getKey()).mod(n);
                if (difference.compareTo(BigInteger.valueOf(maximumShift)) <= 0) {
                    int count = difference.intValueExact();
                    long cost = count * Math.max(weights.multiplication(), weights.squaring());
                    if (cost < bestCost) {
                        bestCost = cost;
                        operation = 3;
                        reference = value.getValue();
                        start = value.getKey();
                        shift = count;
                    }
                }
            }
            if (operation == 1) return append(Step.inverse(reference), target);
            if (operation == 2) {
                int seed = append(Step.power(complement), complement);
                return append(Step.inverse(seed), target);
            }
            if (operation == 3) {
                for (int i = 0; i < shift; i++) {
                    work.charge(1);
                    start = start.add(BigInteger.ONE).mod(n);
                    var previous = values.get(start);
                    reference = previous == null ? append(Step.multiply(reference, 1), start) : previous;
                }
                return reference;
            }
            return append(Step.power(target), target);
        }

        private int append(Step step, BigInteger exponent) {
            work.charge(1);
            work.cells(retained + 5L * (steps.size() + 3));
            if (steps.size() == MAX_STEPS) throw new CertificateWork.Stop("POWER_STEP_LIMIT");
            steps.add(step);
            int reference = steps.size() + 1;
            values.put(exponent, reference);
            return reference;
        }
    }
}
