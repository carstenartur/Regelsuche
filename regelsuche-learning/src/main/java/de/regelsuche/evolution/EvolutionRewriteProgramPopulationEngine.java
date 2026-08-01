package de.regelsuche.evolution;

import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationAttempt;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationBatch;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationCatalog;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationLimits;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationStatus;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessWeight;
import de.regelsuche.json.JsonWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Deterministic TRAIN-only populations of combined genome/program candidates. */
public final class EvolutionRewriteProgramPopulationEngine {
    public static final String RUN_SCHEMA =
        "regelsuche.evolution-rewrite-program-population-run/v1";
    public static final String GENERATION_SCHEMA =
        "regelsuche.evolution-rewrite-program-generation-report/v1";
    public static final String CHECKPOINT_SCHEMA =
        "regelsuche.evolution-rewrite-program-population-checkpoint/v1";
    private static final int GENERATED_POOL_MULTIPLIER = 2;

    private final DeterministicRewriteProgramMutator mutator;

    public EvolutionRewriteProgramPopulationEngine() {
        this(new DeterministicRewriteProgramMutator());
    }

    public EvolutionRewriteProgramPopulationEngine(
        DeterministicRewriteProgramMutator mutator
    ) {
        this.mutator = Objects.requireNonNull(mutator, "mutator");
    }

    public PopulationRun run(
        EvolutionRewriteProgramStudyPlan plan,
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite suite,
        List<EvolutionRewriteProgramCandidate> seeds,
        MutationCatalog catalog,
        ProgramFitnessEvaluator evaluator
    ) {
        validateInputs(plan, splitManifest, suite, seeds, catalog, evaluator);
        ExecutionState state = ExecutionState.initial(seeds);
        evolve(
            plan,
            catalog,
            evaluator,
            state,
            1,
            plan.populationPolicy().generationCount());
        return PopulationRun.create(plan, seeds, state);
    }

    public PopulationCheckpoint checkpoint(
        EvolutionRewriteProgramStudyPlan plan,
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite suite,
        List<EvolutionRewriteProgramCandidate> seeds,
        MutationCatalog catalog,
        ProgramFitnessEvaluator evaluator,
        int completedGeneration
    ) {
        validateInputs(plan, splitManifest, suite, seeds, catalog, evaluator);
        if (completedGeneration < 1
                || completedGeneration >= plan.populationPolicy().generationCount()) {
            throw new IllegalArgumentException(
                "completedGeneration must be in [1,generationCount-1]");
        }
        ExecutionState state = ExecutionState.initial(seeds);
        evolve(
            plan,
            catalog,
            evaluator,
            state,
            1,
            completedGeneration);
        if (state.terminalOutcome != null) {
            throw new IllegalStateException(
                "terminal population cannot be checkpointed: "
                    + state.terminalOutcome);
        }
        return PopulationCheckpoint.create(
            plan, suite, catalog, seeds, completedGeneration, state);
    }

    public PopulationRun resume(
        EvolutionRewriteProgramStudyPlan plan,
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite suite,
        List<EvolutionRewriteProgramCandidate> seeds,
        MutationCatalog catalog,
        ProgramFitnessEvaluator evaluator,
        PopulationCheckpoint checkpoint
    ) {
        validateInputs(plan, splitManifest, suite, seeds, catalog, evaluator);
        Objects.requireNonNull(checkpoint, "checkpoint");
        checkpoint.requireCompatible(plan, suite, catalog, seeds);
        ExecutionState state = checkpoint.executionState();
        evolve(
            plan,
            catalog,
            evaluator,
            state,
            checkpoint.nextGeneration(),
            plan.populationPolicy().generationCount());
        return PopulationRun.create(plan, seeds, state);
    }

    private static void validateInputs(
        EvolutionRewriteProgramStudyPlan plan,
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite suite,
        List<EvolutionRewriteProgramCandidate> seeds,
        MutationCatalog catalog,
        ProgramFitnessEvaluator evaluator
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(evaluator, "evaluator");
        Objects.requireNonNull(seeds, "seeds");
        plan.requireInputs(splitManifest, suite, catalog, seeds);
        if (seeds.size() > plan.populationPolicy().populationSize()) {
            throw new IllegalArgumentException(
                "seed population exceeds frozen population size");
        }
        requireUniqueCandidateIdentities(seeds, "seed population");
    }

    private void evolve(
        EvolutionRewriteProgramStudyPlan plan,
        MutationCatalog catalog,
        ProgramFitnessEvaluator evaluator,
        ExecutionState state,
        int firstGeneration,
        int lastGeneration
    ) {
        for (int generation = firstGeneration;
                generation <= lastGeneration;
                generation++) {
            evaluateCandidates(plan, state.population, evaluator, state);
            List<EvolutionRewriteProgramCandidate> parents = eligible(
                state.population, state.evaluations);
            if (parents.isEmpty()) {
                state.reports.add(GenerationReport.create(
                    generation,
                    evaluations(state.population, state.evaluations),
                    List.of(),
                    List.of(),
                    List.of(),
                    0,
                    state.mutationAttempts,
                    state.trainEvaluations,
                    GenerationOutcome.EXTINCT));
                state.population = List.of();
                state.terminalOutcome = TerminalOutcome.EXTINCT;
                return;
            }

            MutationRound mutationRound = mutate(
                plan,
                catalog,
                generation,
                parents,
                state.population,
                state);
            evaluateCandidates(plan, mutationRound.children(), evaluator, state);
            List<EvolutionRewriteProgramCandidate> pool = merge(
                state.population, mutationRound.children());
            List<EvolutionRewriteProgramCandidate> selected = select(
                plan, state.population, pool, state.evaluations);
            int diversity = Math.toIntExact(selected.stream()
                .map(EvolutionRewriteProgramCandidate::alphaStructuralHash)
                .distinct()
                .count());
            GenerationOutcome outcome = outcome(
                plan,
                generation,
                state.population,
                selected,
                mutationRound,
                diversity,
                state);
            state.reports.add(GenerationReport.create(
                generation,
                evaluations(pool, state.evaluations),
                selected.stream()
                    .map(EvolutionRewriteProgramCandidate::contentHash)
                    .toList(),
                mutationRound.lineage(),
                mutationRound.rejections(),
                diversity,
                state.mutationAttempts,
                state.trainEvaluations,
                outcome));
            state.population = selected;
            if (outcome.terminal() != null) {
                state.terminalOutcome = outcome.terminal();
                return;
            }
        }
    }

    private MutationRound mutate(
        EvolutionRewriteProgramStudyPlan plan,
        MutationCatalog catalog,
        int generation,
        List<EvolutionRewriteProgramCandidate> parents,
        List<EvolutionRewriteProgramCandidate> currentPopulation,
        ExecutionState state
    ) {
        List<EvolutionRewriteProgramCandidate> children = new ArrayList<>();
        List<LineageEdge> lineage = new ArrayList<>();
        List<MutationRejection> rejections = new ArrayList<>();
        Set<String> contentHashes = new LinkedHashSet<>();
        Set<String> alphaHashes = new LinkedHashSet<>();
        currentPopulation.forEach(candidate -> {
            contentHashes.add(candidate.contentHash());
            alphaHashes.add(candidate.alphaStructuralHash());
        });
        Set<EvolutionRewriteProgramMutationKind> permitted = Set.copyOf(
            plan.mutationOperators());
        int poolLimit = Math.multiplyExact(
            plan.populationPolicy().populationSize(),
            GENERATED_POOL_MULTIPLIER);

        for (EvolutionRewriteProgramCandidate parent : parents) {
            int remainingAttempts = plan.budget().maxMutationAttempts()
                - state.mutationAttempts;
            if (remainingAttempts <= 0 || children.size() >= poolLimit) {
                break;
            }
            int maxProposals = Math.min(128, remainingAttempts);
            int maxAccepted = Math.min(
                plan.populationPolicy().maxOffspringPerLineage(),
                maxProposals);
            MutationBatch batch = mutator.mutate(
                parent.genome(),
                parent.plan(),
                catalog,
                mutationSeed(
                    plan.contentHash(), generation, parent.contentHash()),
                new MutationLimits(maxProposals, maxAccepted));
            state.mutationAttempts = Math.addExact(
                state.mutationAttempts, batch.attempts().size());

            int acceptedIndex = 0;
            for (MutationAttempt attempt : batch.attempts()) {
                if (attempt.status() == MutationStatus.REJECTED) {
                    rejections.add(MutationRejection.from(
                        parent.contentHash(), attempt, attempt.blockers()));
                    continue;
                }
                EvolutionRewriteProgramPlan childPlan =
                    batch.acceptedPlans().get(acceptedIndex++);
                EvolutionRewriteProgramCandidate child =
                    EvolutionRewriteProgramCandidate.create(
                        parent.genome(), childPlan);
                List<String> blockers = new ArrayList<>();
                if (!permitted.contains(attempt.kind())) {
                    blockers.add("MUTATION_KIND_NOT_PREREGISTERED:"
                        + attempt.kind());
                }
                if (contentHashes.contains(child.contentHash())) {
                    blockers.add("DUPLICATE_CANDIDATE:contentHash");
                }
                if (alphaHashes.contains(child.alphaStructuralHash())) {
                    blockers.add(
                        "STRUCTURAL_DIVERSITY_DUPLICATE:candidateAlphaStructuralHash");
                }
                if (!blockers.isEmpty()) {
                    rejections.add(MutationRejection.from(
                        parent.contentHash(), attempt, blockers));
                    continue;
                }
                contentHashes.add(child.contentHash());
                alphaHashes.add(child.alphaStructuralHash());
                children.add(child);
                lineage.add(new LineageEdge(
                    parent.contentHash(),
                    child.contentHash(),
                    child.plan().contentHash(),
                    child.alphaStructuralHash(),
                    attempt.kind(),
                    attempt.proposalKey()));
                if (children.size() >= poolLimit) {
                    break;
                }
            }
        }
        return new MutationRound(
            List.copyOf(children),
            List.copyOf(lineage),
            List.copyOf(rejections));
    }

    private static void evaluateCandidates(
        EvolutionRewriteProgramStudyPlan plan,
        Collection<EvolutionRewriteProgramCandidate> candidates,
        ProgramFitnessEvaluator evaluator,
        ExecutionState state
    ) {
        List<EvolutionRewriteProgramCandidate> pending = candidates.stream()
            .filter(candidate ->
                !state.evaluations.containsKey(candidate.contentHash()))
            .sorted(Comparator.comparing(
                EvolutionRewriteProgramCandidate::contentHash))
            .toList();
        if (pending.isEmpty()) {
            return;
        }
        int remaining = Math.max(0,
            plan.budget().maxTrainEvaluations() - state.trainEvaluations);
        int executableCount = Math.min(remaining, pending.size());
        List<EvolutionRewriteProgramCandidate> executable =
            pending.subList(0, executableCount);
        List<CandidateEvaluation> completed = evaluateParallel(
            plan, executable, evaluator);
        for (int index = 0; index < executable.size(); index++) {
            state.evaluations.put(
                executable.get(index).contentHash(), completed.get(index));
        }
        state.trainEvaluations = Math.addExact(
            state.trainEvaluations, executableCount);
        for (int index = executableCount; index < pending.size(); index++) {
            EvolutionRewriteProgramCandidate candidate = pending.get(index);
            state.evaluations.put(
                candidate.contentHash(),
                CandidateEvaluation.blocked(
                    candidate,
                    List.of("TRAIN_EVALUATION_BUDGET_EXHAUSTED")));
        }
    }

    private static List<CandidateEvaluation> evaluateParallel(
        EvolutionRewriteProgramStudyPlan plan,
        List<EvolutionRewriteProgramCandidate> candidates,
        ProgramFitnessEvaluator evaluator
    ) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        int parallelism = Math.min(
            plan.populationPolicy().parallelism(), candidates.size());
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        boolean restoreInterruption = false;
        try {
            List<Future<EvolutionRewriteProgramTrainFitnessEvidence>> futures =
                candidates.stream()
                    .map(candidate -> executor.submit(
                        () -> evaluator.evaluate(candidate)))
                    .toList();
            List<CandidateEvaluation> result = new ArrayList<>();
            for (int index = 0; index < candidates.size(); index++) {
                EvolutionRewriteProgramCandidate candidate = candidates.get(index);
                try {
                    result.add(CandidateEvaluation.from(
                        plan, candidate, futures.get(index).get()));
                } catch (InterruptedException exception) {
                    restoreInterruption = true;
                    result.add(CandidateEvaluation.blocked(
                        candidate,
                        List.of("TRAIN_EVALUATION_INTERRUPTED")));
                } catch (ExecutionException exception) {
                    result.add(CandidateEvaluation.blocked(
                        candidate,
                        List.of("TRAIN_EVALUATION_FAILED:"
                            + stableFailure(exception.getCause()))));
                }
            }
            return List.copyOf(result);
        } finally {
            executor.shutdownNow();
            if (restoreInterruption) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static List<EvolutionRewriteProgramCandidate> eligible(
        Collection<EvolutionRewriteProgramCandidate> candidates,
        Map<String, CandidateEvaluation> evaluations
    ) {
        return candidates.stream()
            .filter(candidate -> evaluations.containsKey(candidate.contentHash()))
            .filter(candidate -> evaluations.get(candidate.contentHash()).eligible())
            .sorted(ranking(evaluations))
            .toList();
    }

    private static List<EvolutionRewriteProgramCandidate> select(
        EvolutionRewriteProgramStudyPlan plan,
        List<EvolutionRewriteProgramCandidate> previous,
        List<EvolutionRewriteProgramCandidate> pool,
        Map<String, CandidateEvaluation> evaluations
    ) {
        Comparator<EvolutionRewriteProgramCandidate> ranking = ranking(evaluations);
        List<EvolutionRewriteProgramCandidate> previousEligible = previous.stream()
            .filter(candidate -> evaluations.get(candidate.contentHash()).eligible())
            .sorted(ranking)
            .toList();
        List<EvolutionRewriteProgramCandidate> poolEligible = pool.stream()
            .filter(candidate -> evaluations.get(candidate.contentHash()).eligible())
            .sorted(ranking)
            .toList();
        LinkedHashMap<String, EvolutionRewriteProgramCandidate> selected =
            new LinkedHashMap<>();
        previousEligible.stream()
            .limit(plan.populationPolicy().eliteCount())
            .forEach(candidate -> selected.put(
                candidate.alphaStructuralHash(), candidate));
        for (EvolutionRewriteProgramCandidate candidate : poolEligible) {
            if (selected.size() >= plan.populationPolicy().populationSize()) {
                break;
            }
            selected.putIfAbsent(candidate.alphaStructuralHash(), candidate);
        }
        return selected.values().stream().sorted(ranking).toList();
    }

    private static Comparator<EvolutionRewriteProgramCandidate> ranking(
        Map<String, CandidateEvaluation> evaluations
    ) {
        return Comparator
            .<EvolutionRewriteProgramCandidate>comparingInt(candidate ->
                evaluations.get(candidate.contentHash()).scalarFitness())
            .reversed()
            .thenComparing(candidate -> candidate.plan().nodeCount())
            .thenComparing(EvolutionRewriteProgramCandidate::contentHash);
    }

    private static GenerationOutcome outcome(
        EvolutionRewriteProgramStudyPlan plan,
        int generation,
        List<EvolutionRewriteProgramCandidate> previous,
        List<EvolutionRewriteProgramCandidate> selected,
        MutationRound mutationRound,
        int diversity,
        ExecutionState state
    ) {
        if (selected.isEmpty()) {
            return GenerationOutcome.EXTINCT;
        }
        if (diversity
                < plan.populationPolicy().minimumDistinctAlphaStructures()) {
            return GenerationOutcome.DIVERSITY_FLOOR;
        }
        if (state.trainEvaluations >= plan.budget().maxTrainEvaluations()
                || state.mutationAttempts >= plan.budget().maxMutationAttempts()) {
            return GenerationOutcome.BUDGET_EXHAUSTED;
        }
        if (samePopulation(previous, selected)
                && mutationRound.children().isEmpty()) {
            return GenerationOutcome.STAGNATED;
        }
        if (generation == plan.populationPolicy().generationCount()) {
            return GenerationOutcome.COMPLETED;
        }
        return GenerationOutcome.CONTINUE;
    }

    private static boolean samePopulation(
        List<EvolutionRewriteProgramCandidate> left,
        List<EvolutionRewriteProgramCandidate> right
    ) {
        return left.stream().map(EvolutionRewriteProgramCandidate::contentHash)
            .toList()
            .equals(right.stream()
                .map(EvolutionRewriteProgramCandidate::contentHash).toList());
    }

    private static List<EvolutionRewriteProgramCandidate> merge(
        List<EvolutionRewriteProgramCandidate> left,
        List<EvolutionRewriteProgramCandidate> right
    ) {
        LinkedHashMap<String, EvolutionRewriteProgramCandidate> result =
            new LinkedHashMap<>();
        left.forEach(candidate -> result.put(candidate.contentHash(), candidate));
        right.forEach(candidate -> result.put(candidate.contentHash(), candidate));
        return result.values().stream()
            .sorted(Comparator.comparing(
                EvolutionRewriteProgramCandidate::contentHash))
            .toList();
    }

    private static List<CandidateEvaluation> evaluations(
        Collection<EvolutionRewriteProgramCandidate> candidates,
        Map<String, CandidateEvaluation> evaluations
    ) {
        return candidates.stream()
            .map(candidate -> evaluations.get(candidate.contentHash()))
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(CandidateEvaluation::candidateHash))
            .toList();
    }

    private static long mutationSeed(
        String studyHash,
        int generation,
        String parentHash
    ) {
        String digest = EvolutionGenome.hash(
            studyHash + "\n" + generation + "\n" + parentHash);
        return Long.parseUnsignedLong(
            digest.substring("sha256:".length(), "sha256:".length() + 16),
            16);
    }

    private static String stableFailure(Throwable failure) {
        Throwable value = failure == null
            ? new IllegalStateException("unknown failure") : failure;
        String message = value.getMessage();
        return value.getClass().getSimpleName()
            + (message == null || message.isBlank()
                ? ""
                : ":" + message.replaceAll("\\s+", " ").trim());
    }

    @FunctionalInterface
    public interface ProgramFitnessEvaluator {
        EvolutionRewriteProgramTrainFitnessEvidence evaluate(
            EvolutionRewriteProgramCandidate candidate);
    }

    public enum GenerationOutcome {
        CONTINUE(null),
        COMPLETED(null),
        EXTINCT(TerminalOutcome.EXTINCT),
        DIVERSITY_FLOOR(TerminalOutcome.DIVERSITY_FLOOR),
        BUDGET_EXHAUSTED(TerminalOutcome.BUDGET_EXHAUSTED),
        STAGNATED(TerminalOutcome.STAGNATED);

        private final TerminalOutcome terminal;

        GenerationOutcome(TerminalOutcome terminal) {
            this.terminal = terminal;
        }

        TerminalOutcome terminal() {
            return terminal;
        }
    }

    public enum TerminalOutcome {
        COMPLETED,
        EXTINCT,
        DIVERSITY_FLOOR,
        BUDGET_EXHAUSTED,
        STAGNATED
    }

    public record CandidateEvaluation(
        String candidateHash,
        String alphaStructuralHash,
        Map<FitnessComponent, Integer> rawComponents,
        List<String> blockers,
        int scalarFitness,
        String evidenceHash,
        String contentHash
    ) {
        public CandidateEvaluation {
            EvolutionGenome.requireSha256(candidateHash, "candidateHash");
            EvolutionGenome.requireSha256(
                alphaStructuralHash, "alphaStructuralHash");
            rawComponents = canonicalComponents(rawComponents);
            blockers = canonicalStrings(blockers);
            if (scalarFitness < -1000 || scalarFitness > 1000) {
                throw new IllegalArgumentException(
                    "scalarFitness must be in [-1000,1000]");
            }
            if (evidenceHash != null) {
                EvolutionGenome.requireSha256(evidenceHash, "evidenceHash");
            }
            EvolutionGenome.requireSha256(contentHash, "contentHash");
            String expected = EvolutionGenome.hash(render(
                candidateHash,
                alphaStructuralHash,
                rawComponents,
                blockers,
                scalarFitness,
                evidenceHash,
                null));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "candidate evaluation contentHash mismatch");
            }
        }

        static CandidateEvaluation from(
            EvolutionRewriteProgramStudyPlan plan,
            EvolutionRewriteProgramCandidate candidate,
            EvolutionRewriteProgramTrainFitnessEvidence evidence
        ) {
            Objects.requireNonNull(evidence, "evidence");
            if (!candidate.contentHash().equals(evidence.candidateHash())
                    || !candidate.genome().contentHash().equals(
                        evidence.genomeHash())
                    || !candidate.plan().contentHash().equals(evidence.planHash())) {
                return blocked(candidate, List.of(
                    "TRAIN_EVIDENCE_CANDIDATE_IDENTITY_MISMATCH"));
            }
            List<String> blockers = new ArrayList<>(evidence.blockers());
            for (FitnessWeight weight : plan.fitnessWeights()) {
                if (!evidence.rawComponents().containsKey(weight.component())) {
                    blockers.add("MISSING_REQUIRED_FITNESS_COMPONENT:"
                        + weight.component());
                }
            }
            int scalar = scalar(plan.fitnessWeights(), evidence.rawComponents());
            return create(
                candidate,
                evidence.rawComponents(),
                blockers,
                scalar,
                evidence.contentHash());
        }

        static CandidateEvaluation blocked(
            EvolutionRewriteProgramCandidate candidate,
            List<String> blockers
        ) {
            return create(candidate, Map.of(), blockers, -1000, null);
        }

        private static CandidateEvaluation create(
            EvolutionRewriteProgramCandidate candidate,
            Map<FitnessComponent, Integer> components,
            List<String> blockers,
            int scalar,
            String evidenceHash
        ) {
            Map<FitnessComponent, Integer> canonicalComponents =
                canonicalComponents(components);
            List<String> canonicalBlockers = canonicalStrings(blockers);
            String hash = EvolutionGenome.hash(render(
                candidate.contentHash(),
                candidate.alphaStructuralHash(),
                canonicalComponents,
                canonicalBlockers,
                scalar,
                evidenceHash,
                null));
            return new CandidateEvaluation(
                candidate.contentHash(),
                candidate.alphaStructuralHash(),
                canonicalComponents,
                canonicalBlockers,
                scalar,
                evidenceHash,
                hash);
        }

        public boolean eligible() {
            return blockers.isEmpty();
        }

        private static int scalar(
            List<FitnessWeight> weights,
            Map<FitnessComponent, Integer> components
        ) {
            long weighted = 0;
            for (FitnessWeight weight : weights) {
                weighted += (long) components.getOrDefault(
                    weight.component(), 0) * weight.weightPermille();
            }
            return Math.max(-1000, Math.min(1000,
                Math.toIntExact(weighted / 1000L)));
        }

        private static String render(
            String candidateHash,
            String alphaStructuralHash,
            Map<FitnessComponent, Integer> rawComponents,
            List<String> blockers,
            int scalarFitness,
            String evidenceHash,
            String contentHash
        ) {
            JsonWriter json = new JsonWriter().beginObject()
                .property("candidateHash", candidateHash)
                .property("alphaStructuralHash", alphaStructuralHash)
                .object("rawComponents", object -> {
                    for (FitnessComponent component : FitnessComponent.values()) {
                        Integer value = rawComponents.get(component);
                        if (value != null) {
                            object.property(component.name(), value);
                        }
                    }
                })
                .stringArray("blockers", blockers)
                .property("scalarFitness", scalarFitness);
            if (evidenceHash == null) {
                json.nullProperty("evidenceHash");
            } else {
                json.property("evidenceHash", evidenceHash);
            }
            if (contentHash != null) {
                json.property("contentHash", contentHash);
            }
            return json.endObject().toString();
        }
    }

    public record LineageEdge(
        String parentCandidateHash,
        String childCandidateHash,
        String childPlanHash,
        String childAlphaStructuralHash,
        EvolutionRewriteProgramMutationKind mutationKind,
        String proposalKey
    ) {
        public LineageEdge {
            EvolutionGenome.requireSha256(
                parentCandidateHash, "parentCandidateHash");
            EvolutionGenome.requireSha256(
                childCandidateHash, "childCandidateHash");
            EvolutionGenome.requireSha256(childPlanHash, "childPlanHash");
            EvolutionGenome.requireSha256(
                childAlphaStructuralHash, "childAlphaStructuralHash");
            Objects.requireNonNull(mutationKind, "mutationKind");
            requireText(proposalKey, "proposalKey");
        }
    }

    public record MutationRejection(
        String parentCandidateHash,
        EvolutionRewriteProgramMutationKind mutationKind,
        String proposalKey,
        String childPlanHash,
        String childAlphaStructuralHash,
        List<String> blockers
    ) {
        public MutationRejection {
            EvolutionGenome.requireSha256(
                parentCandidateHash, "parentCandidateHash");
            Objects.requireNonNull(mutationKind, "mutationKind");
            requireText(proposalKey, "proposalKey");
            if (childPlanHash != null) {
                EvolutionGenome.requireSha256(childPlanHash, "childPlanHash");
            }
            if (childAlphaStructuralHash != null) {
                EvolutionGenome.requireSha256(
                    childAlphaStructuralHash, "childAlphaStructuralHash");
            }
            blockers = canonicalStrings(blockers);
            if (blockers.isEmpty()) {
                throw new IllegalArgumentException(
                    "mutation rejection requires blockers");
            }
        }

        static MutationRejection from(
            String parentHash,
            MutationAttempt attempt,
            List<String> blockers
        ) {
            return new MutationRejection(
                parentHash,
                attempt.kind(),
                attempt.proposalKey(),
                attempt.childPlanHash(),
                attempt.childAlphaStructuralHash(),
                blockers);
        }
    }

    public record GenerationReport(
        String schema,
        int generation,
        List<CandidateEvaluation> evaluations,
        List<String> selectedCandidateHashes,
        List<LineageEdge> lineage,
        List<MutationRejection> rejections,
        int distinctAlphaStructures,
        int cumulativeMutationAttempts,
        int cumulativeTrainEvaluations,
        GenerationOutcome outcome,
        String contentHash
    ) {
        public GenerationReport {
            if (!GENERATION_SCHEMA.equals(schema) || generation < 1) {
                throw new IllegalArgumentException(
                    "invalid rewrite-program generation report");
            }
            evaluations = canonicalEvaluations(evaluations);
            selectedCandidateHashes = canonicalHashes(
                selectedCandidateHashes, false, "selectedCandidateHashes");
            lineage = List.copyOf(Objects.requireNonNull(lineage, "lineage"));
            rejections = List.copyOf(
                Objects.requireNonNull(rejections, "rejections"));
            if (distinctAlphaStructures < 0
                    || cumulativeMutationAttempts < 0
                    || cumulativeTrainEvaluations < 0) {
                throw new IllegalArgumentException(
                    "generation counters must not be negative");
            }
            Objects.requireNonNull(outcome, "outcome");
            Set<String> evaluated = evaluations.stream()
                .map(CandidateEvaluation::candidateHash)
                .collect(java.util.stream.Collectors.toSet());
            if (!evaluated.containsAll(selectedCandidateHashes)) {
                throw new IllegalArgumentException(
                    "selected candidates are absent from generation evaluations");
            }
            if (selectedCandidateHashes.isEmpty()
                    != (outcome == GenerationOutcome.EXTINCT)) {
                throw new IllegalArgumentException(
                    "only an extinct generation may select no candidate");
            }
            if (distinctAlphaStructures > selectedCandidateHashes.size()) {
                throw new IllegalArgumentException(
                    "diversity exceeds selected candidate count");
            }
            EvolutionGenome.requireSha256(contentHash, "contentHash");
            String expected = EvolutionGenome.hash(render(
                generation,
                evaluations,
                selectedCandidateHashes,
                lineage,
                rejections,
                distinctAlphaStructures,
                cumulativeMutationAttempts,
                cumulativeTrainEvaluations,
                outcome,
                null));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "generation report contentHash mismatch");
            }
        }

        static GenerationReport create(
            int generation,
            List<CandidateEvaluation> evaluations,
            List<String> selectedCandidateHashes,
            List<LineageEdge> lineage,
            List<MutationRejection> rejections,
            int distinctAlphaStructures,
            int mutationAttempts,
            int trainEvaluations,
            GenerationOutcome outcome
        ) {
            List<CandidateEvaluation> canonicalEvaluations =
                canonicalEvaluations(evaluations);
            List<String> selected = canonicalHashes(
                selectedCandidateHashes, false, "selectedCandidateHashes");
            String hash = EvolutionGenome.hash(render(
                generation,
                canonicalEvaluations,
                selected,
                lineage,
                rejections,
                distinctAlphaStructures,
                mutationAttempts,
                trainEvaluations,
                outcome,
                null));
            return new GenerationReport(
                GENERATION_SCHEMA,
                generation,
                canonicalEvaluations,
                selected,
                lineage,
                rejections,
                distinctAlphaStructures,
                mutationAttempts,
                trainEvaluations,
                outcome,
                hash);
        }

        private static String render(
            int generation,
            List<CandidateEvaluation> evaluations,
            List<String> selected,
            List<LineageEdge> lineage,
            List<MutationRejection> rejections,
            int diversity,
            int mutationAttempts,
            int trainEvaluations,
            GenerationOutcome outcome,
            String contentHash
        ) {
            JsonWriter json = new JsonWriter().beginObject()
                .property("schema", GENERATION_SCHEMA)
                .property("generation", generation)
                .stringArray("evaluationHashes", evaluations.stream()
                    .map(CandidateEvaluation::contentHash).toList())
                .stringArray("selectedCandidateHashes", selected)
                .array("lineage", array -> lineage.forEach(edge ->
                    array.objectValue(object -> object
                        .property("parentCandidateHash",
                            edge.parentCandidateHash())
                        .property("childCandidateHash",
                            edge.childCandidateHash())
                        .property("childPlanHash", edge.childPlanHash())
                        .property("childAlphaStructuralHash",
                            edge.childAlphaStructuralHash())
                        .property("mutationKind", edge.mutationKind().name())
                        .property("proposalKey", edge.proposalKey()))))
                .array("rejections", array -> rejections.forEach(rejection ->
                    array.objectValue(object -> {
                        object.property("parentCandidateHash",
                                rejection.parentCandidateHash())
                            .property("mutationKind",
                                rejection.mutationKind().name())
                            .property("proposalKey", rejection.proposalKey());
                        if (rejection.childPlanHash() == null) {
                            object.nullProperty("childPlanHash");
                        } else {
                            object.property("childPlanHash",
                                rejection.childPlanHash());
                        }
                        if (rejection.childAlphaStructuralHash() == null) {
                            object.nullProperty("childAlphaStructuralHash");
                        } else {
                            object.property("childAlphaStructuralHash",
                                rejection.childAlphaStructuralHash());
                        }
                        object.stringArray("blockers", rejection.blockers());
                    })))
                .property("distinctAlphaStructures", diversity)
                .property("cumulativeMutationAttempts", mutationAttempts)
                .property("cumulativeTrainEvaluations", trainEvaluations)
                .property("outcome", outcome.name());
            if (contentHash != null) {
                json.property("contentHash", contentHash);
            }
            return json.endObject().toString();
        }
    }

    public record PopulationRun(
        String schema,
        String studyPlanHash,
        List<String> seedCandidateHashes,
        List<GenerationReport> generationReports,
        List<String> finalCandidateHashes,
        TerminalOutcome terminalOutcome,
        int mutationAttempts,
        int trainEvaluations,
        String validationStatus,
        String finalTestStatus,
        String proofStatus,
        String externalNoveltyStatus,
        String contentHash
    ) {
        public PopulationRun {
            if (!RUN_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported rewrite-program population-run schema");
            }
            EvolutionGenome.requireSha256(studyPlanHash, "studyPlanHash");
            seedCandidateHashes = canonicalHashes(
                seedCandidateHashes, true, "seedCandidateHashes");
            generationReports = List.copyOf(
                Objects.requireNonNull(generationReports, "generationReports"));
            if (generationReports.isEmpty()) {
                throw new IllegalArgumentException(
                    "population run requires generation reports");
            }
            for (int index = 0; index < generationReports.size(); index++) {
                if (generationReports.get(index).generation() != index + 1) {
                    throw new IllegalArgumentException(
                        "generation reports are incomplete or reordered");
                }
            }
            finalCandidateHashes = canonicalHashes(
                finalCandidateHashes, false, "finalCandidateHashes");
            Objects.requireNonNull(terminalOutcome, "terminalOutcome");
            if (terminalOutcome == TerminalOutcome.EXTINCT
                    != finalCandidateHashes.isEmpty()) {
                throw new IllegalArgumentException(
                    "only extinct runs may have an empty final population");
            }
            if (mutationAttempts < 0 || trainEvaluations < 0) {
                throw new IllegalArgumentException(
                    "population-run counters must not be negative");
            }
            requireLaterStagesNotEvaluated(
                validationStatus,
                finalTestStatus,
                proofStatus,
                externalNoveltyStatus);
            EvolutionGenome.requireSha256(contentHash, "contentHash");
            String expected = EvolutionGenome.hash(render(
                studyPlanHash,
                seedCandidateHashes,
                generationReports,
                finalCandidateHashes,
                terminalOutcome,
                mutationAttempts,
                trainEvaluations,
                null));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "population-run contentHash mismatch");
            }
        }

        static PopulationRun create(
            EvolutionRewriteProgramStudyPlan plan,
            List<EvolutionRewriteProgramCandidate> seeds,
            ExecutionState state
        ) {
            TerminalOutcome terminal = state.terminalOutcome == null
                ? TerminalOutcome.COMPLETED : state.terminalOutcome;
            List<String> seedHashes = seeds.stream()
                .map(EvolutionRewriteProgramCandidate::contentHash).toList();
            List<String> finalHashes = state.population.stream()
                .map(EvolutionRewriteProgramCandidate::contentHash).toList();
            String hash = EvolutionGenome.hash(render(
                plan.contentHash(),
                canonicalHashes(seedHashes, true, "seedCandidateHashes"),
                state.reports,
                canonicalHashes(finalHashes, false, "finalCandidateHashes"),
                terminal,
                state.mutationAttempts,
                state.trainEvaluations,
                null));
            return new PopulationRun(
                RUN_SCHEMA,
                plan.contentHash(),
                seedHashes,
                state.reports,
                finalHashes,
                terminal,
                state.mutationAttempts,
                state.trainEvaluations,
                "NOT_EVALUATED",
                "NOT_EVALUATED",
                "NOT_EVALUATED",
                "NOT_EVALUATED",
                hash);
        }

        public String toCanonicalJson() {
            return render(
                studyPlanHash,
                seedCandidateHashes,
                generationReports,
                finalCandidateHashes,
                terminalOutcome,
                mutationAttempts,
                trainEvaluations,
                contentHash);
        }

        private static String render(
            String studyHash,
            List<String> seedHashes,
            List<GenerationReport> reports,
            List<String> finalHashes,
            TerminalOutcome terminal,
            int mutationAttempts,
            int trainEvaluations,
            String contentHash
        ) {
            JsonWriter json = new JsonWriter().beginObject()
                .property("schema", RUN_SCHEMA)
                .property("studyPlanHash", studyHash)
                .stringArray("seedCandidateHashes", seedHashes)
                .stringArray("generationReportHashes", reports.stream()
                    .map(GenerationReport::contentHash).toList())
                .stringArray("finalCandidateHashes", finalHashes)
                .property("terminalOutcome", terminal.name())
                .property("mutationAttempts", mutationAttempts)
                .property("trainEvaluations", trainEvaluations)
                .property("validationStatus", "NOT_EVALUATED")
                .property("finalTestStatus", "NOT_EVALUATED")
                .property("proofStatus", "NOT_EVALUATED")
                .property("externalNoveltyStatus", "NOT_EVALUATED");
            if (contentHash != null) {
                json.property("contentHash", contentHash);
            }
            return json.endObject().toString();
        }
    }

    public record PopulationCheckpoint(
        String schema,
        String studyPlanHash,
        String trainSuiteHash,
        String mutationCatalogHash,
        List<String> seedCandidateHashes,
        int completedGeneration,
        List<EvolutionRewriteProgramCandidate> population,
        List<CandidateEvaluation> evaluations,
        List<GenerationReport> generationReports,
        int mutationAttempts,
        int trainEvaluations,
        String validationStatus,
        String finalTestStatus,
        String contentHash
    ) {
        public PopulationCheckpoint {
            if (!CHECKPOINT_SCHEMA.equals(schema) || completedGeneration < 1) {
                throw new IllegalArgumentException(
                    "invalid rewrite-program population checkpoint");
            }
            EvolutionGenome.requireSha256(studyPlanHash, "studyPlanHash");
            EvolutionGenome.requireSha256(trainSuiteHash, "trainSuiteHash");
            EvolutionGenome.requireSha256(
                mutationCatalogHash, "mutationCatalogHash");
            seedCandidateHashes = canonicalHashes(
                seedCandidateHashes, true, "seedCandidateHashes");
            population = canonicalCandidates(population);
            if (population.isEmpty()) {
                throw new IllegalArgumentException(
                    "checkpoint population must not be empty");
            }
            evaluations = canonicalEvaluations(evaluations);
            generationReports = List.copyOf(
                Objects.requireNonNull(generationReports, "generationReports"));
            if (generationReports.size() != completedGeneration) {
                throw new IllegalArgumentException(
                    "checkpoint generation count does not match reports");
            }
            for (int index = 0; index < generationReports.size(); index++) {
                if (generationReports.get(index).generation() != index + 1) {
                    throw new IllegalArgumentException(
                        "checkpoint generation reports are incomplete or reordered");
                }
            }
            Set<String> evaluated = evaluations.stream()
                .map(CandidateEvaluation::candidateHash)
                .collect(java.util.stream.Collectors.toSet());
            for (EvolutionRewriteProgramCandidate candidate : population) {
                if (!evaluated.contains(candidate.contentHash())) {
                    throw new IllegalArgumentException(
                        "checkpoint population candidate lacks evaluation");
                }
            }
            if (mutationAttempts < 0 || trainEvaluations < 0) {
                throw new IllegalArgumentException(
                    "checkpoint counters must not be negative");
            }
            if (!"NOT_EVALUATED".equals(validationStatus)
                    || !"NOT_EVALUATED".equals(finalTestStatus)) {
                throw new IllegalArgumentException(
                    "checkpoint cannot contain later-stage outcomes");
            }
            EvolutionGenome.requireSha256(contentHash, "contentHash");
            String expected = EvolutionGenome.hash(render(
                studyPlanHash,
                trainSuiteHash,
                mutationCatalogHash,
                seedCandidateHashes,
                completedGeneration,
                population,
                evaluations,
                generationReports,
                mutationAttempts,
                trainEvaluations,
                null));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "checkpoint contentHash mismatch");
            }
        }

        static PopulationCheckpoint create(
            EvolutionRewriteProgramStudyPlan plan,
            EvolutionRewriteProgramTrainSuite suite,
            MutationCatalog catalog,
            List<EvolutionRewriteProgramCandidate> seeds,
            int completedGeneration,
            ExecutionState state
        ) {
            List<String> seedHashes = seeds.stream()
                .map(EvolutionRewriteProgramCandidate::contentHash).toList();
            List<CandidateEvaluation> retainedEvaluations =
                state.evaluations.values().stream()
                    .sorted(Comparator.comparing(
                        CandidateEvaluation::candidateHash))
                    .toList();
            String hash = EvolutionGenome.hash(render(
                plan.contentHash(),
                suite.contentHash(),
                catalog.contentHash(),
                canonicalHashes(seedHashes, true, "seedCandidateHashes"),
                completedGeneration,
                canonicalCandidates(state.population),
                retainedEvaluations,
                state.reports,
                state.mutationAttempts,
                state.trainEvaluations,
                null));
            return new PopulationCheckpoint(
                CHECKPOINT_SCHEMA,
                plan.contentHash(),
                suite.contentHash(),
                catalog.contentHash(),
                seedHashes,
                completedGeneration,
                state.population,
                retainedEvaluations,
                state.reports,
                state.mutationAttempts,
                state.trainEvaluations,
                "NOT_EVALUATED",
                "NOT_EVALUATED",
                hash);
        }

        public int nextGeneration() {
            return completedGeneration + 1;
        }

        void requireCompatible(
            EvolutionRewriteProgramStudyPlan plan,
            EvolutionRewriteProgramTrainSuite suite,
            MutationCatalog catalog,
            List<EvolutionRewriteProgramCandidate> seeds
        ) {
            if (!studyPlanHash.equals(plan.contentHash())
                    || !trainSuiteHash.equals(suite.contentHash())
                    || !mutationCatalogHash.equals(catalog.contentHash())) {
                throw new IllegalArgumentException(
                    "checkpoint source identity mismatch");
            }
            List<String> actualSeeds = canonicalHashes(
                seeds.stream()
                    .map(EvolutionRewriteProgramCandidate::contentHash)
                    .toList(),
                true,
                "seedCandidateHashes");
            if (!seedCandidateHashes.equals(actualSeeds)) {
                throw new IllegalArgumentException(
                    "checkpoint seed identity mismatch");
            }
            if (completedGeneration
                    >= plan.populationPolicy().generationCount()) {
                throw new IllegalArgumentException(
                    "checkpoint is not resumable for this study");
            }
        }

        ExecutionState executionState() {
            LinkedHashMap<String, CandidateEvaluation> byHash =
                new LinkedHashMap<>();
            evaluations.forEach(value -> byHash.put(
                value.candidateHash(), value));
            return new ExecutionState(
                population,
                byHash,
                generationReports,
                mutationAttempts,
                trainEvaluations,
                null);
        }

        public String toCanonicalJson() {
            return render(
                studyPlanHash,
                trainSuiteHash,
                mutationCatalogHash,
                seedCandidateHashes,
                completedGeneration,
                population,
                evaluations,
                generationReports,
                mutationAttempts,
                trainEvaluations,
                contentHash);
        }

        private static String render(
            String studyHash,
            String suiteHash,
            String catalogHash,
            List<String> seedHashes,
            int completedGeneration,
            List<EvolutionRewriteProgramCandidate> population,
            List<CandidateEvaluation> evaluations,
            List<GenerationReport> reports,
            int mutationAttempts,
            int trainEvaluations,
            String contentHash
        ) {
            JsonWriter json = new JsonWriter().beginObject()
                .property("schema", CHECKPOINT_SCHEMA)
                .property("studyPlanHash", studyHash)
                .property("trainSuiteHash", suiteHash)
                .property("mutationCatalogHash", catalogHash)
                .stringArray("seedCandidateHashes", seedHashes)
                .property("completedGeneration", completedGeneration)
                .stringArray("populationCandidateHashes", population.stream()
                    .map(EvolutionRewriteProgramCandidate::contentHash).toList())
                .stringArray("evaluationHashes", evaluations.stream()
                    .map(CandidateEvaluation::contentHash).toList())
                .stringArray("generationReportHashes", reports.stream()
                    .map(GenerationReport::contentHash).toList())
                .property("mutationAttempts", mutationAttempts)
                .property("trainEvaluations", trainEvaluations)
                .property("validationStatus", "NOT_EVALUATED")
                .property("finalTestStatus", "NOT_EVALUATED");
            if (contentHash != null) {
                json.property("contentHash", contentHash);
            }
            return json.endObject().toString();
        }
    }

    private record MutationRound(
        List<EvolutionRewriteProgramCandidate> children,
        List<LineageEdge> lineage,
        List<MutationRejection> rejections
    ) {
    }

    private static final class ExecutionState {
        private List<EvolutionRewriteProgramCandidate> population;
        private final LinkedHashMap<String, CandidateEvaluation> evaluations;
        private final List<GenerationReport> reports;
        private int mutationAttempts;
        private int trainEvaluations;
        private TerminalOutcome terminalOutcome;

        private ExecutionState(
            List<EvolutionRewriteProgramCandidate> population,
            Map<String, CandidateEvaluation> evaluations,
            List<GenerationReport> reports,
            int mutationAttempts,
            int trainEvaluations,
            TerminalOutcome terminalOutcome
        ) {
            this.population = canonicalCandidates(population);
            this.evaluations = new LinkedHashMap<>(evaluations);
            this.reports = new ArrayList<>(reports);
            this.mutationAttempts = mutationAttempts;
            this.trainEvaluations = trainEvaluations;
            this.terminalOutcome = terminalOutcome;
        }

        static ExecutionState initial(
            List<EvolutionRewriteProgramCandidate> seeds
        ) {
            return new ExecutionState(
                seeds, Map.of(), List.of(), 0, 0, null);
        }
    }

    private static Map<FitnessComponent, Integer> canonicalComponents(
        Map<FitnessComponent, Integer> values
    ) {
        EnumMap<FitnessComponent, Integer> result =
            new EnumMap<>(FitnessComponent.class);
        if (values != null) {
            values.forEach((component, value) -> {
                Objects.requireNonNull(component, "fitness component");
                Objects.requireNonNull(value, "fitness value");
                if (value < -1000 || value > 1000) {
                    throw new IllegalArgumentException(
                        "fitness value must be in [-1000,1000]");
                }
                result.put(component, value);
            });
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<CandidateEvaluation> canonicalEvaluations(
        List<CandidateEvaluation> values
    ) {
        Objects.requireNonNull(values, "evaluations");
        List<CandidateEvaluation> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "evaluation"))
            .sorted(Comparator.comparing(CandidateEvaluation::candidateHash))
            .toList();
        if (new HashSet<>(result.stream()
                .map(CandidateEvaluation::candidateHash).toList()).size()
                != result.size()) {
            throw new IllegalArgumentException(
                "candidate evaluations must be unique");
        }
        return result;
    }

    private static List<EvolutionRewriteProgramCandidate> canonicalCandidates(
        List<EvolutionRewriteProgramCandidate> values
    ) {
        Objects.requireNonNull(values, "candidates");
        List<EvolutionRewriteProgramCandidate> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "candidate"))
            .sorted(Comparator.comparing(
                EvolutionRewriteProgramCandidate::contentHash))
            .toList();
        requireUniqueCandidateIdentities(result, "candidate list");
        return result;
    }

    private static void requireUniqueCandidateIdentities(
        List<EvolutionRewriteProgramCandidate> candidates,
        String name
    ) {
        if (new HashSet<>(candidates.stream()
                .map(EvolutionRewriteProgramCandidate::contentHash).toList()).size()
                != candidates.size()) {
            throw new IllegalArgumentException(
                name + " contains duplicate candidate hashes");
        }
        if (new HashSet<>(candidates.stream()
                .map(EvolutionRewriteProgramCandidate::alphaStructuralHash)
                .toList()).size() != candidates.size()) {
            throw new IllegalArgumentException(
                name + " contains alpha-structural duplicates");
        }
    }

    private static List<String> canonicalStrings(List<String> values) {
        return values == null
            ? List.of()
            : values.stream()
                .map(value -> requireText(value, "value"))
                .distinct()
                .sorted()
                .toList();
    }

    private static List<String> canonicalHashes(
        List<String> values,
        boolean requireNonEmpty,
        String name
    ) {
        Objects.requireNonNull(values, name);
        List<String> result = values.stream()
            .peek(value -> EvolutionGenome.requireSha256(value, name))
            .sorted()
            .toList();
        if (requireNonEmpty && result.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        if (new HashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException(name + " must be unique");
        }
        return result;
    }

    private static void requireLaterStagesNotEvaluated(
        String validationStatus,
        String finalTestStatus,
        String proofStatus,
        String externalNoveltyStatus
    ) {
        if (!"NOT_EVALUATED".equals(validationStatus)
                || !"NOT_EVALUATED".equals(finalTestStatus)
                || !"NOT_EVALUATED".equals(proofStatus)
                || !"NOT_EVALUATED".equals(externalNoveltyStatus)) {
            throw new IllegalArgumentException(
                "TRAIN population cannot contain later-stage outcomes");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
