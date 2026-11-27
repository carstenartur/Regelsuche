package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.representation.RepresentationBridge;
import java.util.List;
import java.util.Objects;

/**
 * A proposed fixed-width certificate for Ux, Vy and W((Ux).*(Vy)).
 * A nonzero-rank program has exactly three stages, in U/V/W order; zero rank has none.
 * These records are evidence to be checked, never authority to execute.
 */
public record ComposedBilinearPlan(BilinearProgram program, int batchWidth, List<Stage> stages) {
    public ComposedBilinearPlan {
        Objects.requireNonNull(program);
        if (batchWidth < 1) throw new IllegalArgumentException("INVALID_BATCH_WIDTH");
        stages = List.copyOf(stages);
    }

    /** Both stage families assert the same exact rational linear map as their expected source. */
    public sealed interface Stage permits Linear, Spectral {
        default RepresentationBridge.Relation relation() {
            return RepresentationBridge.Relation.LINEAR_MAP_REPRESENTATION_EQUIVALENCE;
        }
    }

    public record Linear(OperatorResidualPlans.Plan plan) implements Stage {
        public Linear { Objects.requireNonNull(plan); }
    }

    /** The basis, inverse, spectrum, exceptional sets and batch width remain in the evidence. */
    public record Spectral(SpectralBatchRepair.Problem problem,
                           SpectralBatchRepair.Certificate certificate) implements Stage {
        public Spectral { Objects.requireNonNull(problem); Objects.requireNonNull(certificate); }
    }

    /** Counts execution arithmetic for one complete batch, excluding acquisition and checking. */
    public record Cost(long variableMultiplications, long constantMultiplications, long additions) {
        public Cost {
            if (variableMultiplications < 0 || constantMultiplications < 0 || additions < 0)
                throw new IllegalArgumentException("NEGATIVE_EXECUTION_COST");
        }
        public long total() {
            return Math.addExact(variableMultiplications, Math.addExact(constantMultiplications, additions));
        }
        Cost plus(OperatorResidualPlans.Cost linear) {
            return new Cost(variableMultiplications, Math.addExact(constantMultiplications, linear.multiplies()),
                Math.addExact(additions, linear.additions()));
        }
    }
}
