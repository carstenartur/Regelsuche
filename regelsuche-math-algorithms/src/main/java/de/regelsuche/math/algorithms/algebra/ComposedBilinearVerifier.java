package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.algebra.ComposedBilinearChecks.need;
import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlans.Result;

/** Independently expands the source tensor and rechecks every stage against U, V or W. */
public final class ComposedBilinearVerifier {
    private ComposedBilinearVerifier() {}

    /** No claimed cost, predictor label or acquisition history is trusted. */
    public static Result<ComposedBilinearPlan.Cost> verify(BilinearProgram.Request request,
            int batchWidth, ComposedBilinearPlan plan, IterationLimits limits, BooleanSupplier cancel) {
        Objects.requireNonNull(request);
        return ComposedBilinearChecks.run(limits, cancel, checks -> prepare(request, batchWidth, plan, checks).cost());
    }

    public static Result<ComposedBilinearPrepared> prepare(BilinearProgram.Request request,
            int batchWidth, ComposedBilinearPlan plan, IterationLimits limits, BooleanSupplier cancel) {
        Objects.requireNonNull(request);
        return ComposedBilinearChecks.run(limits, cancel, checks -> prepare(request, batchWidth, plan, checks));
    }

    static ComposedBilinearPrepared prepare(BilinearProgram.Request request, int batchWidth,
            ComposedBilinearPlan plan, ComposedBilinearChecks checks) {
        need(plan != null, "MISSING_COMPOSED_PLAN");
        need(batchWidth > 0 && batchWidth == plan.batchWidth(), "BATCH_WIDTH_MISMATCH");
        int rank = plan.program().u().size();
        need(plan.stages().size() == (rank == 0 ? 0 : 3), "STAGE_COUNT_MISMATCH");
        long retainedBefore = checks.retained;
        checks.retain(checks.requestCells(request) + checks.programCells(plan.program()) + 8);
        for (var stage : plan.stages()) checks.retain(checks.stageCells(stage));
        request.checkInputs(checks.work);
        BilinearProgramVerifier.check(request, plan.program(), checks.retained, checks.work);
        var cost = new ComposedBilinearPlan.Cost((long) rank * batchWidth, 0, 0);
        var directCost = cost;
        var executors = new ArrayList<ComposedBilinearPrepared.ExecutableStage>(plan.stages().size());
        if (rank != 0) {
            var matrices = List.of(plan.program().u(), plan.program().v(), plan.program().w());
            for (int i = 0; i < 3; i++) {
                PolynomialMatrix expected = checks.matrix(matrices.get(i));
                var stage = plan.stages().get(i);
                ComposedBilinearPrepared.ExecutableStage executable;
                if (stage instanceof ComposedBilinearPlan.Linear linear) {
                    var compiled = checks.require(checks.child(limits -> OperatorResidualSearch.prepare(expected,
                        linear.plan(), limits, checks.cancelled)));
                    // The compiler retains row registers, common scalings and the source tree.
                    checks.retain(8L * checks.linearCells(linear.plan()));
                    executable = new ComposedBilinearPrepared.LinearStage(compiled);
                    directCost = directCost.plus(ComposedBilinearChecks.scale(compiled.directCost(), batchWidth));
                } else {
                    var spectral = (ComposedBilinearPlan.Spectral) stage;
                    need(spectral.problem().batchWidth() == batchWidth, "BATCH_WIDTH_MISMATCH");
                    checks.work.charge((long) expected.rows() * expected.columns());
                    need(expected.equals(spectral.problem().source()), "STAGE_SOURCE_MISMATCH");
                    var problem = new SpectralBatchRepair.Problem(expected, spectral.problem().basis(),
                        spectral.problem().inverse(), batchWidth);
                    // Preparation invokes the independent spectral checker, including the original source binding.
                    var compiled = checks.require(checks.child(limits -> SpectralBatchRepair.prepare(problem,
                        spectral.certificate(), limits, checks.cancelled)));
                    checks.retain(compiled.retainedCells());
                    executable = new ComposedBilinearPrepared.SpectralStage(compiled);
                    directCost = directCost.plus(compiled.directCost());
                }
                cost = cost.plus(executable.batchCost(batchWidth));
                executors.add(executable);
            }
        }
        checks.work.charge(0);
        return ComposedBilinearPrepared.checked(plan, request.tensor(), executors, cost, directCost,
            checks.retained - retainedBefore);
    }
}
