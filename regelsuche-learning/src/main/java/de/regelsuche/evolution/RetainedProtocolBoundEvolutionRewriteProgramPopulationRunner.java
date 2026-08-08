package de.regelsuche.evolution;

import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationCatalog;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.PopulationCheckpoint;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.PopulationRun;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protocol-bound TRAIN population runner that retains complete final candidates.
 *
 * <p>The ordinary {@link PopulationRun} remains the canonical work/result
 * ledger. This wrapper records every immutable candidate object that enters the
 * evaluator and combines the terminal roots with their complete genome and
 * program payloads immediately after the run. No VALIDATION or FINAL TEST input
 * is accepted.</p>
 */
public final class RetainedProtocolBoundEvolutionRewriteProgramPopulationRunner {
    private final EvolutionRewriteProgramPopulationEngine engine;

    public RetainedProtocolBoundEvolutionRewriteProgramPopulationRunner() {
        this(new EvolutionRewriteProgramPopulationEngine());
    }

    public RetainedProtocolBoundEvolutionRewriteProgramPopulationRunner(
        EvolutionRewriteProgramPopulationEngine engine
    ) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public RetainedEvolutionRewriteProgramPopulationRun run(
        EvolutionRewriteProgramStudyPlan plan,
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite suite,
        List<EvolutionRewriteProgramCandidate> seeds,
        MutationCatalog catalog,
        EvolutionRewriteProgramFitnessEvaluator evaluator
    ) {
        requireProtocol(
            plan, splitManifest, suite, seeds, catalog, evaluator);
        CandidateRegistry registry = new CandidateRegistry(seeds);
        PopulationRun run = engine.run(
            plan,
            splitManifest,
            suite,
            seeds,
            catalog,
            verified(plan, evaluator, registry));
        return retain(run, registry);
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
        requireProtocol(
            plan, splitManifest, suite, seeds, catalog, evaluator);
        CandidateRegistry registry = new CandidateRegistry(seeds);
        return engine.checkpoint(
            plan,
            splitManifest,
            suite,
            seeds,
            catalog,
            verified(plan, evaluator, registry),
            completedGeneration);
    }

    public RetainedEvolutionRewriteProgramPopulationRun resume(
        EvolutionRewriteProgramStudyPlan plan,
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite suite,
        List<EvolutionRewriteProgramCandidate> seeds,
        MutationCatalog catalog,
        EvolutionRewriteProgramFitnessEvaluator evaluator,
        PopulationCheckpoint checkpoint
    ) {
        requireProtocol(
            plan, splitManifest, suite, seeds, catalog, evaluator);
        Objects.requireNonNull(checkpoint, "checkpoint");
        CandidateRegistry registry = new CandidateRegistry(seeds);
        checkpoint.population().forEach(registry::retain);
        PopulationRun run = engine.resume(
            plan,
            splitManifest,
            suite,
            seeds,
            catalog,
            verified(plan, evaluator, registry),
            checkpoint);
        return retain(run, registry);
    }

    private static RetainedEvolutionRewriteProgramPopulationRun retain(
        PopulationRun run,
        CandidateRegistry registry
    ) {
        List<EvolutionRewriteProgramCandidate> finalCandidates =
            run.finalCandidateHashes().stream()
                .map(registry::required)
                .toList();
        return RetainedEvolutionRewriteProgramPopulationRun.create(
            run, finalCandidates);
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
            Objects.requireNonNull(
                evaluator.protocol(), "evaluator protocol");
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
                EvolutionRewriteProgramFitnessEvaluator evaluator,
                CandidateRegistry registry
            ) {
        return candidate -> {
            registry.retain(candidate);
            EvolutionRewriteProgramTrainFitnessEvidence evidence =
                Objects.requireNonNull(
                    evaluator.evaluate(candidate),
                    "TRAIN fitness evidence");
            if (!plan.trainEvaluationProtocolHash().equals(
                    evidence.evaluationProtocolHash())) {
                throw new IllegalArgumentException(
                    "TRAIN evidence protocol differs from study plan");
            }
            if (!evaluator.protocol().contentHash().equals(
                    evidence.evaluationProtocolHash())) {
                throw new IllegalArgumentException(
                    "TRAIN evidence protocol differs from evaluator");
            }
            return evidence;
        };
    }

    private static final class CandidateRegistry {
        private final Map<String, EvolutionRewriteProgramCandidate> candidates =
            new ConcurrentHashMap<>();

        private CandidateRegistry(
            List<EvolutionRewriteProgramCandidate> initial
        ) {
            Objects.requireNonNull(initial, "initial candidates")
                .forEach(this::retain);
        }

        private void retain(EvolutionRewriteProgramCandidate candidate) {
            Objects.requireNonNull(candidate, "candidate");
            candidates.compute(candidate.contentHash(), (hash, previous) -> {
                if (previous != null && !previous.equals(candidate)) {
                    throw new IllegalArgumentException(
                        "candidate hash identifies different payloads");
                }
                return candidate;
            });
        }

        private EvolutionRewriteProgramCandidate required(String hash) {
            EvolutionRewriteProgramCandidate candidate = candidates.get(hash);
            if (candidate == null) {
                throw new IllegalStateException(
                    "terminal population candidate payload was not retained: "
                        + hash);
            }
            return candidate;
        }
    }
}
