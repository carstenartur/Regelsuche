package de.regelsuche.evolution;

import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationCatalog;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Retained TRAIN runner that executes only explicitly implemented and
 * content-addressed population protocols.
 *
 * <p>The default constructor remains bound to the exact historical legacy
 * protocol. The stratified scheduler is executable only through its distinct
 * protocol identity and mutator implementation class.</p>
 */
public final class ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRunner {
    private final EvolutionRewriteProgramPopulationExecutionProtocol protocol;

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
            mutatorImplementationClass(protocol));
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
            delegate(studyPlan).run(
                studyPlan,
                splitManifest,
                suite,
                seeds,
                catalog,
                evaluator);
        return ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRun
            .create(retained, executionPlan, protocol);
    }

    /**
     * Executes the stratified protocol and returns a separate TRAIN-only
     * diagnostics artifact without changing the canonical population run.
     */
    public DiagnosedRun runWithDiagnostics(
        EvolutionRewriteProgramPopulationExecutionPlan executionPlan,
        EvolutionRewriteProgramStudyPlan studyPlan,
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite suite,
        List<EvolutionRewriteProgramCandidate> seeds,
        MutationCatalog catalog,
        EvolutionRewriteProgramFitnessEvaluator evaluator
    ) {
        requirePlan(executionPlan, studyPlan);
        if (protocol.offspringSchedulingPolicy()
                != EvolutionRewriteProgramPopulationExecutionProtocol
                    .OffspringSchedulingPolicy.STRATIFIED_MUTATION_KIND_V1) {
            throw new IllegalArgumentException(
                "TRAIN diagnostics require the stratified execution protocol");
        }
        StratifiedMutationKindRewriteProgramMutator mutator =
            new StratifiedMutationKindRewriteProgramMutator(studyPlan);
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun retained =
            delegate(mutator).run(
                studyPlan,
                splitManifest,
                suite,
                seeds,
                catalog,
                evaluator);
        ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRun bound =
            ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRun
                .create(retained, executionPlan, protocol);
        EvolutionRewriteProgramTrainDiagnostics diagnostics =
            EvolutionRewriteProgramTrainDiagnostics.create(
                executionPlan,
                protocol,
                bound,
                seeds,
                mutator.observedBatches());
        return new DiagnosedRun(bound, diagnostics);
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
        var checkpoint = delegate(studyPlan).checkpoint(
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
            delegate(studyPlan).resume(
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

    private RetainedProtocolBoundEvolutionRewriteProgramPopulationRunner delegate(
        EvolutionRewriteProgramStudyPlan studyPlan
    ) {
        Objects.requireNonNull(studyPlan, "studyPlan");
        DeterministicRewriteProgramMutator mutator = switch (
            protocol.offspringSchedulingPolicy()) {
            case ROTATED_PREFIX_V1 -> new DeterministicRewriteProgramMutator();
            case STRATIFIED_MUTATION_KIND_V1 ->
                new StratifiedMutationKindRewriteProgramMutator(
                    Set.copyOf(studyPlan.mutationOperators()));
        };
        return delegate(mutator);
    }

    private static RetainedProtocolBoundEvolutionRewriteProgramPopulationRunner
            delegate(DeterministicRewriteProgramMutator mutator) {
        return new RetainedProtocolBoundEvolutionRewriteProgramPopulationRunner(
            new EvolutionRewriteProgramPopulationEngine(mutator));
    }

    private static Class<? extends DeterministicRewriteProgramMutator>
            mutatorImplementationClass(
                EvolutionRewriteProgramPopulationExecutionProtocol protocol
            ) {
        return switch (protocol.offspringSchedulingPolicy()) {
            case ROTATED_PREFIX_V1 -> DeterministicRewriteProgramMutator.class;
            case STRATIFIED_MUTATION_KIND_V1 ->
                StratifiedMutationKindRewriteProgramMutator.class;
        };
    }

    private static void requireImplementedProtocol(
        EvolutionRewriteProgramPopulationExecutionProtocol protocol
    ) {
        var legacy = EvolutionRewriteProgramPopulationExecutionProtocol.legacyV1();
        var stratified = EvolutionRewriteProgramPopulationExecutionProtocol
            .stratifiedMutationKindV1();
        if (!legacy.contentHash().equals(protocol.contentHash())
                && !stratified.contentHash().equals(protocol.contentHash())) {
            throw new IllegalArgumentException(
                "population execution protocol is not implemented by this runner: "
                    + "supportedHashes=[" + legacy.contentHash() + ","
                    + stratified.contentHash() + "]"
                    + ", actualHash=" + protocol.contentHash()
                    + ", engineSemantics="
                    + protocol.populationEngineSemanticsVersion()
                    + ", mutatorSemantics="
                    + protocol.mutatorSemanticsVersion()
                    + ", scheduling="
                    + protocol.offspringSchedulingPolicy());
        }
    }

    public record DiagnosedRun(
        ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRun run,
        EvolutionRewriteProgramTrainDiagnostics diagnostics
    ) {
        public DiagnosedRun {
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(diagnostics, "diagnostics");
            if (!run.contentHash().equals(diagnostics.executionBoundRunHash())) {
                throw new IllegalArgumentException(
                    "diagnostics are not bound to the returned run");
            }
        }
    }
}
