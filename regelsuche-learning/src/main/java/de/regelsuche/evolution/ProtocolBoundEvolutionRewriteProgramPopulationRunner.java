package de.regelsuche.evolution;

import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationCatalog;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.PopulationCheckpoint;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.PopulationRun;
import java.util.List;
import java.util.Objects;

/**
 * Authoritative population entrypoint for the flagship study.
 *
 * <p>The underlying engine remains reusable for deterministic mechanics tests.
 * This runner additionally requires the evaluator object, frozen study plan and
 * every returned evidence artifact to agree on one exact evaluation-protocol
 * hash before any TRAIN population result is accepted.</p>
 */
public final class ProtocolBoundEvolutionRewriteProgramPopulationRunner {
    private final EvolutionRewriteProgramPopulationEngine engine;

    public ProtocolBoundEvolutionRewriteProgramPopulationRunner() {
        this(new EvolutionRewriteProgramPopulationEngine());
    }

    public ProtocolBoundEvolutionRewriteProgramPopulationRunner(
        EvolutionRewriteProgramPopulationEngine engine
    ) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public PopulationRun run(
        EvolutionRewriteProgramStudyPlan plan,
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite suite,
        List<EvolutionRewriteProgramCandidate> seeds,
        MutationCatalog catalog,
        EvolutionRewriteProgramFitnessEvaluator evaluator
    ) {
        requireProtocol(plan, splitManifest, suite, seeds, catalog, evaluator);
        return engine.run(
            plan,
            splitManifest,
            suite,
            seeds,
            catalog,
            verified(plan, evaluator));
    }

    public PopulationCheckpoint checkpoint(
        EvolutionRewriteProgramStudyPlan plan,
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite suite,
        List<EvolutionRewriteProgramCandidate> seeds,
        MutationCatalog catalog,
        EvolutionRewriteProgramFitnessEvaluator evaluator,
        int completedGeneration
    ) {
        requireProtocol(plan, splitManifest, suite, seeds, catalog, evaluator);
        return engine.checkpoint(
            plan,
            splitManifest,
            suite,
            seeds,
            catalog,
            verified(plan, evaluator),
            completedGeneration);
    }

    public PopulationRun resume(
        EvolutionRewriteProgramStudyPlan plan,
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite suite,
        List<EvolutionRewriteProgramCandidate> seeds,
        MutationCatalog catalog,
        EvolutionRewriteProgramFitnessEvaluator evaluator,
        PopulationCheckpoint checkpoint
    ) {
        requireProtocol(plan, splitManifest, suite, seeds, catalog, evaluator);
        return engine.resume(
            plan,
            splitManifest,
            suite,
            seeds,
            catalog,
            verified(plan, evaluator),
            checkpoint);
    }

    private static void requireProtocol(
        EvolutionRewriteProgramStudyPlan plan,
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite suite,
        List<EvolutionRewriteProgramCandidate> seeds,
        MutationCatalog catalog,
        EvolutionRewriteProgramFitnessEvaluator evaluator
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(evaluator, "evaluator");
        EvolutionRewriteProgramEvaluationProtocol protocol =
            Objects.requireNonNull(evaluator.protocol(), "evaluator protocol");
        plan.requireInputs(
            splitManifest,
            suite,
            protocol,
            catalog,
            seeds);
        if (!protocol.implementationClass().equals(
                evaluator.getClass().getName())) {
            throw new IllegalArgumentException(
                "evaluator implementation differs from frozen protocol");
        }
    }

    private static EvolutionRewriteProgramPopulationEngine.ProgramFitnessEvaluator
            verified(
                EvolutionRewriteProgramStudyPlan plan,
                EvolutionRewriteProgramFitnessEvaluator evaluator
            ) {
        return candidate -> {
            EvolutionRewriteProgramTrainFitnessEvidence evidence =
                Objects.requireNonNull(
                    evaluator.evaluate(candidate), "TRAIN fitness evidence");
            if (!plan.trainEvaluationProtocolHash().equals(
                    evidence.evaluationProtocolHash())) {
                throw new IllegalArgumentException(
                    "TRAIN evidence evaluation protocol differs from study plan");
            }
            if (!evaluator.protocol().contentHash().equals(
                    evidence.evaluationProtocolHash())) {
                throw new IllegalArgumentException(
                    "TRAIN evidence evaluation protocol differs from evaluator");
            }
            return evidence;
        };
    }
}
