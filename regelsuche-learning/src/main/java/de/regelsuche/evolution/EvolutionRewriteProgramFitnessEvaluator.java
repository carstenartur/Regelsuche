package de.regelsuche.evolution;

/**
 * TRAIN evaluator whose semantic protocol is independently content-addressed.
 */
public interface EvolutionRewriteProgramFitnessEvaluator {
    EvolutionRewriteProgramEvaluationProtocol protocol();

    EvolutionRewriteProgramTrainFitnessEvidence evaluate(
        EvolutionRewriteProgramCandidate candidate);
}
