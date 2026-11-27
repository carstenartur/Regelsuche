package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.algebra.ComposedBilinearChecks.need;
import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlans.*;

/**
 * Bounded acquisition and composition of exact bilinear and operator programs.
 * Proposal slots count one acquisition call and each declared residual/spectral recipe across all stages.
 * They do not count subsets internal to bilinear discovery; every such operation still shares work/time/space.
 */
public final class ComposedBilinearSearch {
    private ComposedBilinearSearch() {}

    public record Policy(OperatorResidualSearch.Policy residual, Optional<SpectralBatchRepair.Policy> spectral) {
        public Policy { Objects.requireNonNull(residual); Objects.requireNonNull(spectral); }
        public static Policy residualOnly() {
            return new Policy(OperatorResidualSearch.Policy.defaults(), Optional.empty());
        }
        public static Policy defaults() {
            return new Policy(OperatorResidualSearch.Policy.defaults(), Optional.of(SpectralBatchRepair.Policy.defaults()));
        }
    }

    public enum Stage { U, V, W }
    public enum Provider { RESIDUAL, SPECTRAL_BASIS, SPECTRAL }

    /** A cost, when present, is for this linear stage's complete batch. */
    public record Proposal(String name, Optional<Cost> batchCost, String detail) {
        public Proposal { Objects.requireNonNull(name); Objects.requireNonNull(batchCost); Objects.requireNonNull(detail); }
    }

    public record Attempt(Stage stage, Provider provider, int proposalSlots, long work, Status status,
                          String detail, List<Proposal> proposals, Optional<Cost> bestBatchCost) {
        public Attempt { proposals = List.copyOf(proposals); Objects.requireNonNull(bestBatchCost); }
    }

    /** Supplied seed verification is paid here; its earlier external discovery cost is not observable. */
    public record Accounting(boolean suppliedSeed, long acquisitionWork, long compositionWork, int attemptedCandidates) {}

    /** VERIFIED means an exact executable procedure, not global optimality outside the finite policy. */
    public record Discovery(BilinearProgram.Request request, ComposedBilinearPrepared prepared,
                            Accounting accounting, List<Attempt> attempts) {
        public Discovery { attempts = List.copyOf(attempts); }
    }

    public static Result<Discovery> search(BilinearProgram.Request request,
            BilinearProgram seed, int batchWidth, Policy policy, int maximumCandidates,
            IterationLimits limits, BooleanSupplier cancel) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(seed);
        validate(policy, maximumCandidates);
        return ComposedBilinearChecks.run(limits, cancel, checks -> {
            need(batchWidth > 0, "INVALID_BATCH_WIDTH");
            checks.retain(checks.requestCells(request) + checks.programCells(seed) + policyCells(policy));
            request.checkInputs(checks.work);
            BilinearProgramVerifier.check(request, seed, checks.retained, checks.work);
            long acquisition = checks.work.used();
            return compose(request, seed, batchWidth, policy, new Slots(maximumCandidates),
                true, acquisition, checks);
        });
    }

    public static Result<Discovery> discover(BilinearProgram.Tensor tensor,
            int maximumProducts, int batchWidth, Policy policy, int maximumCandidates,
            IterationLimits limits, BooleanSupplier cancel) {
        Objects.requireNonNull(tensor);
        if (maximumProducts < 0) throw new IllegalArgumentException("NEGATIVE_PRODUCT_LIMIT");
        validate(policy, maximumCandidates);
        return ComposedBilinearChecks.run(limits, cancel, checks -> {
            need(batchWidth > 0, "INVALID_BATCH_WIDTH");
            checks.retain(policyCells(policy));
            var slots = new Slots(maximumCandidates);
            slots.take(1, checks);
            // This package-level entry shares the very same CertificateWork, including its original deadline.
            var acquired = BilinearFormDiscovery.discover(tensor, maximumProducts, checks.retained, checks.work);
            var request = acquired.request();
            var seed = acquired.witness().program();
            checks.retain(checks.requestCells(request) + checks.programCells(seed) + acquired.lowerBound().retainedCells());
            long acquisition = checks.work.used();
            return compose(request, seed, batchWidth, policy, slots, false, acquisition, checks);
        });
    }

    private static void validate(Policy policy, int maximumCandidates) {
        Objects.requireNonNull(policy);
        if (maximumCandidates < 0) throw new IllegalArgumentException("NEGATIVE_CANDIDATE_LIMIT");
    }

    private static long policyCells(Policy policy) {
        return 8L + policy.residual().predictors().size() + policy.spectral().map(p -> p.recipes().size()).orElse(0);
    }

    private static Discovery compose(BilinearProgram.Request request, BilinearProgram seed, int batchWidth,
            Policy policy, Slots slots, boolean supplied, long acquisition, ComposedBilinearChecks checks) {
        var stages = new ArrayList<ComposedBilinearPlan.Stage>(3);
        var attempts = new ArrayList<Attempt>();
        checks.retain(16);
        if (!seed.u().isEmpty()) {
            var sources = List.of(seed.u(), seed.v(), seed.w());
            var names = Stage.values();
            for (int i = 0; i < 3; i++) {
                var source = checks.matrix(sources.get(i));
                var stage = select(names[i], source, batchWidth, policy, slots, attempts, checks);
                checks.retain(checks.stageCells(stage));
                stages.add(stage);
            }
        }
        var plan = new ComposedBilinearPlan(seed, batchWidth, stages);
        // Reconstruct from evidence, not the search's prepared handles or reported costs.
        var prepared = ComposedBilinearVerifier.prepare(request, batchWidth, plan, checks);
        checks.work.charge(0);
        var accounting = new Accounting(supplied, acquisition, checks.work.used() - acquisition, slots.used);
        return new Discovery(request, prepared, accounting, attempts);
    }

    private static ComposedBilinearPlan.Stage select(Stage name, PolynomialMatrix source, int batchWidth,
            Policy policy, Slots slots, List<Attempt> attempts, ComposedBilinearChecks checks) {
        int residualSlots = policy.residual().predictors().size();
        slots.take(residualSlots, checks);
        var residualResult = checks.child(limits -> OperatorResidualSearch.discover(source, policy.residual(),
            residualSlots, limits, checks.cancelled));
        var residual = checks.require(residualResult);
        var incumbent = residual.operator();
        var bestCost = ComposedBilinearChecks.scale(incumbent.cost(), batchWidth);
        ComposedBilinearPlan.Stage best = new ComposedBilinearPlan.Linear(incumbent.plan());
        // Include the incumbent while the independent spectral provider works alongside it.
        checks.retain(8L * checks.linearCells(incumbent.plan()));
        checks.retain(12L + 6L * residual.candidates().size());
        var residualProposals = new ArrayList<Proposal>(residual.candidates().size());
        for (var candidate : residual.candidates()) {
            checks.work.charge(1);
            residualProposals.add(new Proposal(candidate.predictor().name(),
                candidate.cost().map(cost -> ComposedBilinearChecks.scale(cost, batchWidth)),
                candidate.cost().isPresent() ? "CHECKED" : "RESIDUAL_RANK_LIMIT"));
        }
        attempts.add(new Attempt(name, Provider.RESIDUAL, residualSlots, residualResult.work(), residualResult.status(),
            residualResult.detail(), residualProposals, Optional.of(bestCost)));
        if (policy.spectral().isEmpty()) return best;
        if (source.rows() != source.columns()) {
            checks.retain(12);
            attempts.add(new Attempt(name, Provider.SPECTRAL_BASIS, 0, 0, Status.UNSUPPORTED,
                "RECTANGULAR_STAGE", List.of(), Optional.empty()));
            return best;
        }

        var derived = checks.child(limits -> SpectralBatchRepair.deriveProblem(source, batchWidth, limits, checks.cancelled));
        checks.retain(12);
        attempts.add(new Attempt(name, Provider.SPECTRAL_BASIS, 0, derived.work(), derived.status(),
            derived.detail(), List.of(), Optional.empty()));
        if (derived.status() == Status.UNSUPPORTED) return best;
        var problem = checks.require(derived);
        checks.retain(checks.problemCells(problem));
        var spectralPolicy = policy.spectral().orElseThrow();
        int spectralSlots = spectralPolicy.recipes().size();
        slots.take(spectralSlots, checks);
        var spectralResult = checks.child(limits -> SpectralBatchRepair.discover(problem, spectralPolicy,
            spectralSlots, limits, checks.cancelled));
        var spectral = checks.require(spectralResult);
        var candidate = spectral.operator();
        var proposed = new ComposedBilinearPlan.Spectral(candidate.problem(), candidate.certificate());
        checks.retain(candidate.retainedCells());
        checks.retain(12L + 6L * spectral.candidates().size());
        var spectralProposals = new ArrayList<Proposal>(spectral.candidates().size());
        for (var trace : spectral.candidates()) {
            checks.work.charge(1);
            spectralProposals.add(new Proposal(trace.recipe(), trace.cost(), trace.detail()));
        }
        attempts.add(new Attempt(name, Provider.SPECTRAL, spectralSlots, spectralResult.work(), spectralResult.status(),
            spectralResult.detail(), spectralProposals, Optional.of(candidate.cost())));
        if (candidate.cost().total() < bestCost.total()) best = proposed;
        return best;
    }

    private static final class Slots {
        final int maximum;
        int used;
        Slots(int maximum) { this.maximum = maximum; }
        void take(int count, ComposedBilinearChecks checks) {
            checks.work.charge(1);
            if (count > maximum - used) throw new CertificateWork.Stop("CANDIDATE_LIMIT");
            used += count;
        }
    }
}
