package de.regelsuche.sdk.optimization.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearFormDiscovery;
import de.regelsuche.math.algorithms.algebra.BilinearIntegerPortfolio;
import de.regelsuche.math.algorithms.algebra.BilinearIntegerSchedule;
import de.regelsuche.math.algorithms.algebra.BilinearProductLearning;
import de.regelsuche.math.algorithms.algebra.BilinearProgram;
import de.regelsuche.math.algorithms.algebra.BilinearRankBound;
import de.regelsuche.math.algorithms.algebra.BilinearProgramSearch;
import de.regelsuche.math.algorithms.algebra.BilinearProgramVerifier;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.planning.BoundedPlanSelection;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/** SDK entry points for bounded rational tensor-program search, checking and execution. */
public final class BilinearProgramSdk {
    private BilinearProgramSdk() {}

    /** Compare verified integer programs at specified operand widths, retaining the directly computed baseline. */
    public static BilinearProgram.Result<BilinearIntegerPortfolio.Selection> selectInteger(
            BilinearProgram.Tensor tensor, int maximumProducts, int maximumSubsets, BilinearIntegerSchedule.Profile profile,
            OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearIntegerPortfolio.select(tensor, maximumProducts, maximumSubsets, budget.maximumCandidates(),
            profile, limits(budget), cancellation::isCancelled);
    }

    /** Select a directly checked integer plan or replay a supplied acquisition at matching operand widths. */
    public static BoundedPlanSelection.Decision<BilinearIntegerSchedule.Profile, BilinearIntegerPortfolio.Candidate>
            selectIntegerWithReuse(BilinearProgram.Tensor tensor, BilinearIntegerSchedule.Profile profile,
                BilinearIntegerPortfolio.Objective objective, Optional<BilinearIntegerPortfolio.Reuse> reuse, long maximumProbeWork,
                OptimizationBudget budget, CancellationToken cancellation) {
        long probe = budget.maximumCandidates() == 1 ? Math.min(0, maximumProbeWork) : maximumProbeWork;
        return BilinearIntegerPortfolio.selectWithReuse(tensor, profile, objective, reuse, probe,
            limits(budget), cancellation::isCancelled);
    }

    public static BilinearProgram.Result<BilinearProductLearning.Basis> trainProducts(String sourceId,
            BilinearProgram.Tensor tensor, int maximumProducts, OptimizationBudget budget,
            CancellationToken cancellation) {
        return BilinearProductLearning.train(sourceId, tensor, maximumProducts, limits(budget),
            cancellation::isCancelled);
    }

    public static BilinearProgram.Result<BilinearProductLearning.Application> reuseProducts(
            BilinearProductLearning.Basis basis, BilinearProgram.Tensor tensor, int maximumProducts,
            OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearProductLearning.apply(basis, tensor, maximumProducts, limits(budget),
            cancellation::isCancelled);
    }

    public static BilinearProgram.Result<BilinearFormDiscovery.Discovery> discover(BilinearProgram.Tensor tensor,
            int maximumProducts, OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearFormDiscovery.discover(tensor, maximumProducts, limits(budget), cancellation::isCancelled);
    }

    public static BilinearProgram.Result<BilinearRankBound.Certificate> computeRankBound(BilinearProgram.Tensor tensor,
            OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearRankBound.compute(tensor, limits(budget), cancellation::isCancelled);
    }

    public static BilinearProgram.Result<Integer> verifyRankBound(BilinearProgram.Tensor tensor,
            BilinearRankBound.Certificate certificate, OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearRankBound.verify(tensor, certificate, limits(budget), cancellation::isCancelled);
    }

    public static BilinearProgram.Result<BilinearRankBound.Interval> certifyProductBounds(
            BilinearProgram.Request request, BilinearProgram program, BilinearRankBound.Certificate lowerBound,
            OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearRankBound.certify(request, program, lowerBound, limits(budget), cancellation::isCancelled);
    }

    public static BilinearProgram.Result<BilinearRankBound.RationalCertificate> computeRationalRankBound(
            BilinearProgram.Tensor tensor, OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearRankBound.computeRational(tensor, limits(budget), cancellation::isCancelled);
    }

    public static BilinearProgram.Result<Integer> verifyRationalRankBound(BilinearProgram.Tensor tensor,
            BilinearRankBound.RationalCertificate proof, OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearRankBound.verifyRational(tensor, proof, limits(budget), cancellation::isCancelled);
    }

    public static BilinearProgram.Result<BilinearRankBound.Interval> certifyRationalProductBounds(
            BilinearProgram.Request request, BilinearProgram program, BilinearRankBound.RationalCertificate proof,
            OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearRankBound.certifyRational(request, program, proof, limits(budget), cancellation::isCancelled);
    }

    public static BilinearProgram.Result<BilinearProgram.Witness> searchWithRationalBound(BilinearProgram.Request request,
            BilinearRankBound.RationalCertificate proof, OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearProgramSearch.searchWithRationalBound(request, proof, limits(budget), cancellation::isCancelled);
    }

    public static BilinearProgram.Result<BilinearProductLearning.Transfer> applyInputBases(BilinearProgram.Request source,
            BilinearProgram program, BilinearProductLearning.InputBases bases, BilinearProgram.Tensor target,
            OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearProductLearning.applyInputBases(source, program, bases, target, limits(budget), cancellation::isCancelled);
    }

    public static BilinearProgram.Result<BilinearProgram.Cost> verifyInputBases(BilinearProgram.Request source,
            BilinearProgram program, BilinearProductLearning.InputBases bases, BilinearProgram.Request target,
            BilinearProgram transported, OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearProgramVerifier.verifyInputBases(source, program, bases, target, transported,
            limits(budget), cancellation::isCancelled);
    }

    public static BilinearProgram.Result<BilinearProgram.Witness> search(BilinearProgram.Request request,
            OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearProgramSearch.search(request, limits(budget), cancellation::isCancelled);
    }

    public static BilinearProgram.Result<BilinearProgram.Cost> verify(BilinearProgram.Request request,
            BilinearProgram program, OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearProgramVerifier.verify(request, program, limits(budget), cancellation::isCancelled);
    }

    public static BilinearProgram.Result<List<ExactRational>> evaluate(BilinearProgram.Request request,
            BilinearProgram program, List<ExactRational> x, List<ExactRational> y,
            OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearProgramVerifier.evaluate(request, program, x, y, limits(budget), cancellation::isCancelled);
    }

    /** Check an existing witness against its source and lower it for signed integers of the given widths. */
    public static BilinearProgram.Result<BilinearIntegerSchedule.Plan> scheduleInteger(
            BilinearProgram.Request request, BilinearProgram program, BilinearIntegerSchedule.Profile profile,
            OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearProgramVerifier.scheduleInteger(request, program, profile, limits(budget),
            cancellation::isCancelled);
    }

    /** Execute the selected instructions on values within the schedule's input profile. */
    public static BilinearProgram.Result<List<BigInteger>> evaluateInteger(BilinearIntegerSchedule.Plan plan,
            List<BigInteger> x, List<BigInteger> y, OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearIntegerSchedule.evaluate(plan, x, y, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
