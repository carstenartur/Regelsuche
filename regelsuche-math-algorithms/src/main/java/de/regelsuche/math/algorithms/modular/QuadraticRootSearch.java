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

    public record Certificate(QuadraticRootContract contract, SharedPowerPlan.Certificate power, QuadraticRootProgram program) {
        public Certificate {
            Objects.requireNonNull(contract);
            Objects.requireNonNull(power);
            Objects.requireNonNull(program);
        }
    }
    public record Result(Optional<Certificate> certificate, String reason, long work, int candidates) {}

    public static Result search(QuadraticRootContract expected, SharedPowerPlan.Weights weights, int maximumCandidates,
            IterationLimits limits, BooleanSupplier cancellation) {
        if (maximumCandidates < 1) throw new IllegalArgumentException("POSITIVE_CANDIDATE_LIMIT_REQUIRED");
        var work = new CertificateWork(limits, cancellation);
        int tried = 0;
        try {
            QuadraticRootVerifier.checkField(expected, work, cancellation);
            var basis = new QuadraticRootBasis(expected, work);
            var powers = SharedPowerSearch.search(basis.request(), weights, 8, work.remainingLimits(basis.retainedCells()), cancellation);
            work.chargeCompleted(powers.work());
            var power = powers.certificate().orElseThrow(() -> new IllegalArgumentException(powers.reason()));
            var template = basis.instantiate(power.schedule());
            var options = permutations(template);
            var lanes = QuadraticRootVerifier.lanes(expected, template.program(options.getFirst(), options.getFirst()), work);
            Arm bestEqual = null, bestUnequal = null;
            int bestCost = Integer.MAX_VALUE;
            var checkedEqual = new ArrayList<Optional<Arm>>();
            var checkedUnequal = new ArrayList<Optional<Arm>>();
            portfolio:
            for (int l = 0; l < options.size(); l++) for (int r = 0; r < options.size(); r++) {
                if (tried == maximumCandidates) break portfolio;
                tried++;
                work.charge(1);
                var left = check(l, true, options, checkedEqual, lanes, expected, work);
                var right = check(r, false, options, checkedUnequal, lanes, expected, work);
                if (left.isEmpty() || right.isEmpty()) continue;
                var a = left.orElseThrow();
                var b = right.orElseThrow();
                int cost = exitCost(a) + exitCost(b);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestEqual = a;
                    bestUnequal = b;
                }
            }
            if (bestEqual == null) throw new IllegalArgumentException("NO_PROVED_BRANCH_PAIR");
            var cert = new Certificate(expected, power, template.program(bestEqual, bestUnequal));
            QuadraticRootVerifier.verifyProgram(expected, cert, work, cancellation);
            work.charge(0);
            return new Result(Optional.of(cert), "VERIFIED", work.used(), tried);
        } catch (CertificateWork.Stop | IllegalArgumentException ex) {
            return new Result(Optional.empty(), ex.getMessage(), work.used(), tried);
        }
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

    /** All proposals share a power schedule. Prefer fewer character selections, then fewer negations. */
    private static int exitCost(Arm arm) {
        return arm.steps().size() + (arm.normalizeRealCharacter() ? 8 : 0);
    }
}
