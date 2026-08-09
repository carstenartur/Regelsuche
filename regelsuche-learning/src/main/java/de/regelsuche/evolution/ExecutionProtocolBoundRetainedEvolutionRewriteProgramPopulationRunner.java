package de.regelsuche.evolution;

import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationCatalog;
import java.util.List;
import java.util.Objects;

/**
 * Retained TRAIN runner that binds the previously implicit population mechanics
 * without changing the historical execution algorithm.
 *
 * <p>This first protocol tranche deliberately supports only the exact legacy
 * scheduler. A future scheduling policy must receive a separate implementation
 * and protocol identity before it becomes executable.</p>
 */
public final class ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRunner {
    private final EvolutionRewriteProgramPopulationExecutionProtocol protocol;
    private final RetainedProtocolBoundEvolutionRewriteProgramPopulationRunner
        delegate;

    public ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRunner() {
        this(EvolutionRewriteProgramPopulationExecutionProtocol.legacyV1());
    }

    public ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRunner(
        EvolutionRewriteProgramPopulationExecutionProtocol protocol
    ) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        requireImplementedProtocol(protocol);
        protocol.requireImplementations(
            EvolutionRewriteProgramPopulationEngine.class,
            DeterministicRewriteProgramMutator.class);
        this.delegate =
            new RetainedProtocolBoundEvolutionRewriteProgramPopulationRunner(
                new EvolutionRewriteProgramPopulationEngine(
                    new DeterministicRewriteProgramMutator()));
    }

    public ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRun run(
        EvolutionRewriteProgramPopulationExecutionPlan executionPlan,
        EvolutionRewriteProgramStudyPlan studyPlan,
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite suite,
        List<EvolutionRewriteProgramCandidate> seeds,
        MutationCatalog catalog,
        EvolutionRewriteProgramFitnessEvaluator evaluator
    ) {
        requirePlan(executionPlan, studyPlan);
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun retained =
            delegate.run(
                studyPlan,
                splitManifest,
                suite,
                seeds,
                catalog,
                evaluator);
        return ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRun
            .create(retained, executionPlan, protocol);
    }

    public ExecutionProtocolBoundEvolutionRewriteProgramPopulationCheckpoint
            checkpoint(
        EvolutionRewriteProgramPopulationExecutionPlan executionPlan,
        EvolutionRewriteProgramStudyPlan studyPlan,
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite suite,
        List<EvolutionRewriteProgramCandidate> seeds,
        MutationCatalog catalog,
        EvolutionRewriteProgramFitnessEvaluator evaluator,
        int completedGeneration
    ) {
        requirePlan(executionPlan, studyPlan);
        var checkpoint = delegate.checkpoint(
            studyPlan,
            splitManifest,
            suite,
            seeds,
            catalog,
            evaluator,
            completedGeneration);
        return ExecutionProtocolBoundEvolutionRewriteProgramPopulationCheckpoint
            .create(checkpoint, executionPlan, protocol);
    }

    public ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRun resume(
        EvolutionRewriteProgramPopulationExecutionPlan executionPlan,
        EvolutionRewriteProgramStudyPlan studyPlan,
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite suite,
        List<EvolutionRewriteProgramCandidate> seeds,
        MutationCatalog catalog,
        EvolutionRewriteProgramFitnessEvaluator evaluator,
        ExecutionProtocolBoundEvolutionRewriteProgramPopulationCheckpoint
            checkpoint
    ) {
        requirePlan(executionPlan, studyPlan);
        Objects.requireNonNull(checkpoint, "checkpoint");
        checkpoint.requireCompatible(executionPlan, protocol);
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun retained =
            delegate.resume(
                studyPlan,
                splitManifest,
                suite,
                seeds,
                catalog,
                evaluator,
                checkpoint.checkpoint());
        return ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRun
            .create(retained, executionPlan, protocol);
    }

    public EvolutionRewriteProgramPopulationExecutionProtocol protocol() {
        return protocol;
    }

    private void requirePlan(
        EvolutionRewriteProgramPopulationExecutionPlan executionPlan,
        EvolutionRewriteProgramStudyPlan studyPlan
    ) {
        Objects.requireNonNull(executionPlan, "executionPlan")
            .requireInputs(studyPlan, protocol);
    }

    private static void requireImplementedProtocol(
        EvolutionRewriteProgramPopulationExecutionProtocol protocol
    ) {
        var legacy = EvolutionRewriteProgramPopulationExecutionProtocol.legacyV1();
        if (!legacy.contentHash().equals(protocol.contentHash())) {
            throw new IllegalArgumentException(
                "population execution protocol is declared but not implemented: "
                    + protocol.offspringSchedulingPolicy());
        }
    }
}
