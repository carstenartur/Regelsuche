package de.regelsuche.evolution;

import de.regelsuche.math.algorithms.equivalence.RationalFunctionNormalFormEquivalencePortAdapter;
import java.util.Objects;
import java.util.Set;

/**
 * Application composition root for the exact, protocol-bound rewrite-program
 * TRAIN evaluator. Lower layers depend only on validation ports; the app selects
 * the concrete rational normal-form adapter.
 */
public final class RewriteProgramFitnessComposition {
    private RewriteProgramFitnessComposition() {
    }

    public static ProtocolBoundInformationParityRewriteProgramTrainFitnessEvaluator
            exactRationalTrainEvaluator(
        EvolutionRewriteProgramTrainSuite suite,
        Set<EvolutionStudyPlan.FitnessComponent> requiredComponents
    ) {
        return new ProtocolBoundInformationParityRewriteProgramTrainFitnessEvaluator(
            Objects.requireNonNull(suite, "suite"),
            Set.copyOf(Objects.requireNonNull(
                requiredComponents, "requiredComponents")),
            new RationalFunctionNormalFormEquivalencePortAdapter());
    }
}
