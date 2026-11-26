package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import static de.regelsuche.math.algorithms.modular.QuadraticRootProgram.*;

/** Bounded shared-power synthesis followed by an independent signed permutation of each branch. */
public final class QuadraticRootSearch {
    private QuadraticRootSearch() {}
    private static final int PERMUTATIONS = 8;

    public record Certificate(QuadraticRootContract contract, SharedPowerPlan.Certificate power, QuadraticRootProgram program) {
        public Certificate {
            Objects.requireNonNull(contract);
            Objects.requireNonNull(power);
            Objects.requireNonNull(program);
        }
    }
    public record Result(Optional<Certificate> certificate, String reason, long work, int candidates) {}

    /** Verified whole programs ordered by the default mathematical score, including alternatives to the original. */
    public record Portfolio(List<Certificate> certificates, String reason, long work, int candidates) {
        public Portfolio { certificates = List.copyOf(certificates); }
    }

    private record Ranked(Certificate certificate, long score) {}
    private record Outcome(Portfolio portfolio, boolean improvesOriginal) {}

    public static Result search(QuadraticRootContract expected, SharedPowerPlan.Weights weights, int maximumCandidates,
            IterationLimits limits, BooleanSupplier cancellation) {
        var outcome = searchPrograms(expected, weights, maximumCandidates, limits, cancellation);
        var all = outcome.portfolio();
        if (all.certificates().isEmpty()) return new Result(Optional.empty(), all.reason(), all.work(), all.candidates());
        if (!outcome.improvesOriginal()) return new Result(Optional.empty(), "NO_COST_IMPROVEMENT", all.work(), all.candidates());
        return new Result(Optional.of(all.certificates().getFirst()), all.reason(), all.work(), all.candidates());
    }

    /** Splits the global branch-pair allowance across distinct verified schedules, cheapest power schedule first. */
    public static Portfolio portfolio(QuadraticRootContract expected, SharedPowerPlan.Weights weights, int maximumCandidates,
            IterationLimits limits, BooleanSupplier cancellation) {
        return searchPrograms(expected, weights, maximumCandidates, limits, cancellation).portfolio();
    }

    private static final class PairBudget {
        private final int maximum;
        private int tried;
        PairBudget(int maximum) { this.maximum = maximum; }
        int schedules(int available) {
            // With a small budget, cover a full row of signed unequal exits before opening another schedule.
            return Math.min(available, Math.max(1, maximum / PERMUTATIONS));
        }
        int allowance(int schedulesLeft) {
            return Math.min(PERMUTATIONS * PERMUTATIONS, 1 + (maximum - tried - 1) / schedulesLeft);
        }
    }

    private static Outcome searchPrograms(QuadraticRootContract expected, SharedPowerPlan.Weights weights, int maximumCandidates,
            IterationLimits limits, BooleanSupplier cancellation) {
        if (maximumCandidates < 1) throw new IllegalArgumentException("POSITIVE_CANDIDATE_LIMIT_REQUIRED");
        var work = new CertificateWork(limits, cancellation);
        var budget = new PairBudget(maximumCandidates);
        try {
            QuadraticRootVerifier.checkField(expected, work, cancellation);
            var powers = powerCandidates(expected, weights, work, cancellation);
            if (powers.candidates().isEmpty()) throw new IllegalArgumentException(powers.reason());
            var ranked = new ArrayList<Ranked>();
            long retained = powers.retainedCells();
            int schedulesLeft = budget.schedules(powers.candidates().size());
            for (var power : powers.candidates()) {
                if (schedulesLeft == 0) break;
                int allowance = budget.allowance(schedulesLeft);
                schedulesLeft--;
                var child = new CertificateWork(work.remainingLimits(retained), cancellation);
                List<Certificate> proposals;
                try {
                    proposals = propose(expected, power.certificate(), allowance, budget, child, cancellation);
                } finally {
                    work.chargeCompleted(child.used());
                }
                long pending = proposals.stream().mapToLong(c -> programCells(c.program())).sum();
                long replayCells = retained + pending;
                work.cells(replayCells);
                for (var cert : proposals) {
                    long cells = programCells(cert.program());
                    work.charge((1L + ranked.size()) * cells);
                    if (ranked.stream().noneMatch(prior -> prior.certificate().program().equals(cert.program()))) {
                        var replay = new CertificateWork(work.remainingLimits(replayCells), cancellation);
                        try {
                            QuadraticRootVerifier.verifyProgram(expected, cert, replay, cancellation);
                        } finally {
                            work.chargeCompleted(replay.used());
                        }
                        ranked.add(new Ranked(cert, score(cert.program(), weights, work)));
                        retained += cells;
                    }
                }
            }
            if (ranked.isEmpty()) throw new IllegalArgumentException(
                    budget.tried < (long) powers.candidates().size() * PERMUTATIONS * PERMUTATIONS
                            ? "CANDIDATE_LIMIT" : "NO_PROVED_BRANCH_PAIR");
            work.cells(retained + 3L * ranked.size());
            ranked.sort((a, b) -> {
                work.charge(1);
                return Long.compare(a.score(), b.score());
            });
            long baseline = score(expected.original(), weights, work);
            work.charge(1L + ranked.size());
            var result = new Portfolio(ranked.stream().map(Ranked::certificate).toList(), "VERIFIED", work.used(), budget.tried);
            return new Outcome(result, ranked.getFirst().score() < baseline);
        } catch (CertificateWork.Stop | IllegalArgumentException ex) {
            return new Outcome(new Portfolio(List.of(), ex.getMessage(), work.used(), budget.tried), false);
        }
    }

    private static long programCells(QuadraticRootProgram program) {
        return 16L + 5L * (program.prefix().size() + program.equal().steps().size() + program.unequal().steps().size());
    }

    private static long score(QuadraticRootProgram program, SharedPowerPlan.Weights weights, CertificateWork work) {
        for (var block : List.of(program.prefix(), program.equal().steps(), program.unequal().steps())) {
            for (var step : block) work.charge(2L * (1L + SharedPowerVerifier.words(step.literal())));
        }
        return program.score(weights);
    }

    private static SharedPowerSearch.Portfolio powerCandidates(QuadraticRootContract expected, SharedPowerPlan.Weights weights,
            CertificateWork work, BooleanSupplier cancellation) {
        var basis = new QuadraticRootBasis(expected, work);
        var powers = SharedPowerSearch.portfolio(basis.request(), weights, 8, 6,
                work.remainingLimits(basis.retainedCells()), cancellation);
        work.chargeCompleted(powers.work());
        return powers;
    }

    private record Exits(Arm equal, Arm unequal) {
        boolean dominates(Exits other) {
            return dominates(equal, other.equal) && dominates(unequal, other.unequal);
        }
        private static boolean dominates(Arm a, Arm b) {
            return a.steps().size() <= b.steps().size() && (!a.normalizeRealCharacter() || b.normalizeRealCharacter());
        }
    }

    private static List<Certificate> propose(QuadraticRootContract expected, SharedPowerPlan.Certificate power, int allowance,
            PairBudget budget, CertificateWork work, BooleanSupplier cancellation) {
        var t = new QuadraticRootBasis(expected, work).instantiate(power.schedule());
        // Template, permutation instructions, two match caches, and a bounded frontier of exit pairs.
        long retained = 5L * t.prefix().size() + 160 + 4L * allowance;
        work.cells(retained);
        work.charge(4L * PERMUTATIONS + t.prefix().size());
        var options = permutations(t);
        var exits = new ArrayList<Exits>();
        var proof = new CertificateWork(work.remainingLimits(retained), cancellation);
        try {
            var lanes = QuadraticRootVerifier.lanes(expected, t.program(options.getFirst(), options.getFirst()), proof);
            var equal = new ArrayList<Optional<Arm>>();
            var unequal = new ArrayList<Optional<Arm>>();
            for (int i = 0; i < allowance; i++) {
                budget.tried++;
                proof.charge(1);
                var a = check(i / options.size(), true, options, equal, lanes, expected, proof);
                var b = check(i % options.size(), false, options, unequal, lanes, expected, proof);
                if (a.isEmpty() || b.isEmpty()) continue;
                var pair = new Exits(a.orElseThrow(), b.orElseThrow());
                proof.charge(1L + 2L * exits.size());
                if (exits.stream().anyMatch(known -> known.dominates(pair))) continue;
                exits.removeIf(pair::dominates);
                exits.add(pair);
            }
        } finally {
            work.chargeCompleted(proof.used());
        }
        var proposals = new ArrayList<Certificate>();
        for (var pair : exits) {
            work.charge(1L + t.prefix().size());
            work.cells(retained + (proposals.size() + 1L) * (16L + 5L * (t.prefix().size() + 4L)));
            proposals.add(new Certificate(expected, power, t.program(pair.equal(), pair.unequal())));
        }
        return proposals;
    }

    private static List<Arm> permutations(QuadraticRootBasis.Template t) {
        var out = new ArrayList<Arm>();
        for (boolean swap : new boolean[]{false, true}) {
            for (int signs : new int[]{0, 1, 2, 3}) {
                int a = swap ? t.imaginary() : t.real();
                int b = swap ? t.real() : t.imaginary();
                var steps = new ArrayList<Step>();
                int next = INPUTS + t.prefix().size();
                if ((signs & 2) != 0) { steps.add(Step.negate(a)); a = next++; }
                if ((signs & 1) != 0) { steps.add(Step.negate(b)); b = next; }
                out.add(new Arm(steps, a, b, false));
            }
        }
        return out;
    }

    private static Optional<Arm> check(int index, boolean equal, List<Arm> options, List<Optional<Arm>> cache,
            List<QuadraticRootVerifier.Lane> lanes, QuadraticRootContract contract, CertificateWork work) {
        if (index < cache.size()) return cache.get(index);
        var arm = options.get(index);
        Optional<Arm> option = Optional.empty();
        if (QuadraticRootVerifier.matches(lanes, equal, arm, contract.observation(), work)) option = Optional.of(arm);
        else if (contract.observation() == QuadraticRootContract.Observation.EXACT_PAIR) {
            var normalized = new Arm(arm.steps(), arm.real(), arm.imaginary(), true);
            if (QuadraticRootVerifier.matches(lanes, equal, normalized, contract.observation(), work)) option = Optional.of(normalized);
        }
        cache.add(option);
        return option;
    }
}
