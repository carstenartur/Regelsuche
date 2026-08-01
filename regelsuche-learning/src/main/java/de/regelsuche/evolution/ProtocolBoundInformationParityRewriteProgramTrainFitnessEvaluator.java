package de.regelsuche.evolution;

import de.regelsuche.equivalence.AssumptionAwareEquivalenceService;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import java.util.Objects;
import java.util.Set;

/**
 * Authoritative #521 TRAIN evaluator. It delegates mathematical execution to
 * the information-parity evaluator and reissues the canonical evidence with the
 * exact frozen protocol identity attached.
 */
public final class ProtocolBoundInformationParityRewriteProgramTrainFitnessEvaluator
        implements EvolutionRewriteProgramFitnessEvaluator {
    private static final EvolutionRewriteProgramEvaluationProtocol PROTOCOL =
        EvolutionRewriteProgramEvaluationProtocol
            .informationParityExactRationalV1();

    private final EvolutionRewriteProgramTrainSuite suite;
    private final InformationParityRewriteProgramTrainFitnessEvaluator delegate;

    public ProtocolBoundInformationParityRewriteProgramTrainFitnessEvaluator(
        EvolutionRewriteProgramTrainSuite suite,
        Set<FitnessComponent> requiredComponents,
        AssumptionAwareEquivalenceService equivalence
    ) {
        this.suite = Objects.requireNonNull(suite, "suite");
        this.delegate = new InformationParityRewriteProgramTrainFitnessEvaluator(
            suite,
            Objects.requireNonNull(requiredComponents, "requiredComponents"),
            Objects.requireNonNull(equivalence, "equivalence"));
        if (suite.evaluatorProfile() != PROTOCOL.evaluatorProfile()) {
            throw new IllegalArgumentException(
                "TRAIN suite evaluator profile differs from frozen protocol");
        }
    }

    @Override
    public EvolutionRewriteProgramEvaluationProtocol protocol() {
        return PROTOCOL;
    }

    @Override
    public EvolutionRewriteProgramTrainFitnessEvidence evaluate(
        EvolutionRewriteProgramCandidate candidate
    ) {
        EvolutionRewriteProgramTrainFitnessEvidence result =
            delegate.evaluate(candidate);
        return EvolutionRewriteProgramTrainFitnessEvidence.create(
            suite,
            protocol(),
            candidate,
            result.cases(),
            result.rawComponents(),
            result.blockers());
    }
}
