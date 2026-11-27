package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.algebra.ComposedBilinearChecks.need;
import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlans.Result;

/** A closed checked procedure, reusable on arbitrary rational batches of its fixed width. */
public final class ComposedBilinearPrepared {
    private final ComposedBilinearPlan plan;
    private final int leftSize;
    private final int rightSize;
    private final int outputSize;
    private final List<ExecutableStage> stages;
    private final ComposedBilinearPlan.Cost cost;
    private final ComposedBilinearPlan.Cost directCost;
    private final long retainedCells;

    private ComposedBilinearPrepared(ComposedBilinearPlan plan, BilinearProgram.Tensor tensor,
            List<ExecutableStage> stages, ComposedBilinearPlan.Cost cost, ComposedBilinearPlan.Cost directCost,
            long retainedCells) {
        this.plan = plan;
        this.leftSize = tensor.leftSize();
        this.rightSize = tensor.rightSize();
        this.outputSize = tensor.outputs().size();
        this.stages = List.copyOf(stages);
        this.cost = cost;
        this.directCost = directCost;
        this.retainedCells = retainedCells;
    }

    /** Called only after the verifier has checked the tensor and all three compiled stages. */
    static ComposedBilinearPrepared checked(ComposedBilinearPlan plan, BilinearProgram.Tensor tensor,
            List<ExecutableStage> stages, ComposedBilinearPlan.Cost cost, ComposedBilinearPlan.Cost directCost,
            long retainedCells) {
        return new ComposedBilinearPrepared(plan, tensor, stages, cost, directCost, retainedCells);
    }

    public ComposedBilinearPlan plan() { return plan; }
    public ComposedBilinearPlan.Cost cost() { return cost; }
    public ComposedBilinearPlan.Cost directCost() { return directCost; }
    /** Conservative scalar/reference bound for the retained certificate and compiled procedures, not bytes. */
    public long retainedCells() { return retainedCells; }

    /** Executes U, V, the componentwise products, and W; no tensor expansion or search occurs here. */
    public Result<List<List<ExactRational>>> apply(List<List<ExactRational>> left,
            List<List<ExactRational>> right, IterationLimits limits, BooleanSupplier cancel) {
        return ComposedBilinearChecks.run(limits, cancel, checks -> {
            checks.retain(retainedCells);
            var x = input(left, leftSize, checks);
            var y = input(right, rightSize, checks);
            int rank = plan.program().u().size();
            if (rank == 0) {
                checks.retain(2L * plan.batchWidth() * (1L + outputSize));
                var output = new ArrayList<List<ExactRational>>(plan.batchWidth());
                for (int b = 0; b < plan.batchWidth(); b++) {
                    var row = new ArrayList<ExactRational>(outputSize);
                    for (int k = 0; k < outputSize; k++) {
                        checks.work.charge(1);
                        row.add(ExactRational.ZERO);
                    }
                    output.add(List.copyOf(row));
                }
                return List.copyOf(output);
            }
            var u = stages.get(0).apply(x, checks);
            var v = stages.get(1).apply(y, checks);
            checks.retain(2L * plan.batchWidth() * (1L + rank));
            var products = new ArrayList<List<ExactRational>>(plan.batchWidth());
            for (int b = 0; b < plan.batchWidth(); b++) {
                var row = new ArrayList<ExactRational>(rank);
                for (int p = 0; p < rank; p++) row.add(checks.work.mul(u.get(b).get(p), v.get(b).get(p)));
                products.add(List.copyOf(row));
            }
            return stages.get(2).apply(List.copyOf(products), checks);
        });
    }

    private List<List<ExactRational>> input(List<List<ExactRational>> values, int width, ComposedBilinearChecks checks) {
        need(values != null && values.size() == plan.batchWidth(), "BATCH_WIDTH_MISMATCH");
        checks.retain(2L * plan.batchWidth() * (1L + width));
        var copy = new ArrayList<List<ExactRational>>(values.size());
        for (var row : values) {
            need(row != null && row.size() == width, "INPUT_VECTOR_SHAPE");
            for (var entry : row) {
                need(entry != null, "MISSING_VECTOR_VALUE");
                checks.work.check(entry);
            }
            copy.add(List.copyOf(row));
        }
        return List.copyOf(copy);
    }

    sealed interface ExecutableStage permits LinearStage, SpectralStage {
        OperatorResidualPlans.Cost batchCost(int batchWidth);
        List<List<ExactRational>> apply(List<List<ExactRational>> input, ComposedBilinearChecks checks);
    }

    record LinearStage(PreparedOperator operator) implements ExecutableStage {
        @Override public OperatorResidualPlans.Cost batchCost(int batchWidth) {
            return ComposedBilinearChecks.scale(operator.cost(), batchWidth);
        }
        @Override public List<List<ExactRational>> apply(List<List<ExactRational>> input, ComposedBilinearChecks checks) {
            checks.retain(2L * input.size());
            var output = new ArrayList<List<ExactRational>>(input.size());
            for (var row : input) {
                var values = checks.require(checks.child(limits -> operator.apply(row, limits, checks.cancelled)));
                checks.retain(1L + values.size());
                output.add(values);
            }
            return List.copyOf(output);
        }
    }

    record SpectralStage(PreparedSpectralBatch operator) implements ExecutableStage {
        @Override public OperatorResidualPlans.Cost batchCost(int batchWidth) { return operator.cost(); }
        @Override public List<List<ExactRational>> apply(List<List<ExactRational>> input, ComposedBilinearChecks checks) {
            var output = checks.require(checks.child(limits -> operator.apply(input, limits, checks.cancelled)));
            checks.retain(BilinearProgram.matrixCells(output));
            return output;
        }
    }
}
