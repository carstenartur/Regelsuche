package de.regelsuche.evolution;

import de.regelsuche.evolution.DeterministicGenomeMutator.MutationAttempt;
import de.regelsuche.evolution.DeterministicGenomeMutator.MutationBatch;
import de.regelsuche.evolution.DeterministicGenomeMutator.MutationCatalog;
import de.regelsuche.evolution.DeterministicGenomeMutator.MutationLimits;
import de.regelsuche.evolution.DeterministicGenomeMutator.MutationStatus;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessWeight;
import de.regelsuche.json.JsonWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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

/**
 * Deterministic, bounded TRAIN-only population orchestration.
 *
 * <p>Fitness is not proof, novelty or promotion. The engine never receives
 * VALIDATION or FINAL TEST cases and retains hard blockers separately from the
 * preregistered scalar selection profile.</p>
 */
public final class EvolutionPopulationEngine {
    public static final String RUN_SCHEMA =
        "regelsuche.evolution-population-run/v1";
    public static final String GENERATION_SCHEMA =
        "regelsuche.evolution-generation-report/v1";
    private static final int MAX_GENERATED_POOL_MULTIPLIER = 2;

    private final DeterministicGenomeMutator mutator;

    public EvolutionPopulationEngine() {
        this(new DeterministicGenomeMutator());
    }

    public EvolutionPopulationEngine(DeterministicGenomeMutator mutator) {
        this.mutator = Objects.requireNonNull(mutator, "mutator");
    }

    /** Executes the frozen number of TRAIN generations. */
    public PopulationRun run(
        EvolutionStudyPlan plan,
        List<EvolutionGenome> seedGenomes,
        MutationCatalog mutationCatalog,
        TrainFitnessEvaluator evaluator
    ) {
        validateInputs(plan, mutationCatalog, evaluator);
        ExecutionState state = initialState(plan, seedGenomes);
        executeGenerations(
            plan,
            mutationCatalog,
            evaluator,
            state,
            1,
            plan.populationPolicy().generationCount());
        return completeRun(plan, seedGenomes, state);
    }

    /**
     * Executes an exact TRAIN prefix and returns a resumable, content-addressed
     * checkpoint without evaluating VALIDATION or FINAL TEST data.
     */
    public EvolutionPopulationCheckpoint checkpoint(
        EvolutionStudyPlan plan,
        List<EvolutionGenome> seedGenomes,
        MutationCatalog mutationCatalog,
        TrainFitnessEvaluator evaluator,
        int completedGeneration
    ) {
        validateInputs(plan, mutationCatalog, evaluator);
        int generationCount = plan.populationPolicy().generationCount();
        if (completedGeneration < 1 || completedGeneration >= generationCount) {
            throw new IllegalArgumentException(
                "completedGeneration must be in [1,generationCount-1]");
        }
        ExecutionState state = initialState(plan, seedGenomes);
        executeGenerations(
            plan,
            mutationCatalog,
            evaluator,
            state,
            1,
            completedGeneration);
        if (state.terminal != null) {
            throw new IllegalStateException(
                "terminal population run cannot produce a resumable checkpoint: "
                    + state.terminal);
        }
        return EvolutionPopulationCheckpoint.create(
            plan,
            mutationCatalog,
            seedGenomes,
            completedGeneration,
            state.population,
            state.evaluations.values(),
            state.reports,
            state.budget.mutationAttempts,
            state.budget.trainEvaluations);
    }

    /** Resumes one previously verified TRAIN checkpoint. */
    public PopulationRun resume(
        EvolutionStudyPlan plan,
        List<EvolutionGenome> seedGenomes,
        MutationCatalog mutationCatalog,
        TrainFitnessEvaluator evaluator,
        EvolutionPopulationCheckpoint checkpoint
    ) {
        validateInputs(plan, mutationCatalog, evaluator);
        Objects.requireNonNull(checkpoint, "checkpoint");
        checkpoint.requireCompatible(plan, mutationCatalog, seedGenomes);
        ExecutionState state = new ExecutionState(
            checkpoint.population(),
            checkpoint.evaluationsByGenomeHash(),
            checkpoint.mutationAttempts(),
            checkpoint.trainEvaluations(),
            checkpoint.generationReports());
        executeGenerations(
            plan,
            mutationCatalog,
            evaluator,
            state,
            checkpoint.nextGeneration(),
            plan.populationPolicy().generationCount());
        return completeRun(plan, seedGenomes, state);
    }

    private static void validateInputs(
        EvolutionStudyPlan plan,
        MutationCatalog mutationCatalog,
        TrainFitnessEvaluator evaluator
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(mutationCatalog, "mutationCatalog");
        Objects.requireNonNull(evaluator, "evaluator");
    }

    private static ExecutionState initialState(
        EvolutionStudyPlan plan,
        List<EvolutionGenome> seedGenomes
    ) {
        return new ExecutionState(
            validateSeeds(plan, seedGenomes),
            Map.of(),
            0,
            0,
            List.of());
    }

    private void executeGenerations(
        EvolutionStudyPlan plan,
        MutationCatalog mutationCatalog,
        TrainFitnessEvaluator evaluator,
        ExecutionState state,
        int firstGeneration,
        int lastGeneration
    ) {
        for (int generation = firstGeneration;
                generation <= lastGeneration;
                generation++) {
            evaluate(
                plan,
                state.population,
                evaluator,
                state.evaluations,
                state.budget);
            List<EvolutionGenome> parents = eligible(
                state.population, state.evaluations);
            if (parents.isEmpty()) {
                state.reports.add(GenerationReport.create(
                    generation,
                    candidateEvaluations(state.population, state.evaluations),
                    List.of(),
                    List.of(),
                    List.of(),
                    0,
                    state.budget.mutationAttempts,
                    state.budget.trainEvaluations,
                    GenerationOutcome.EXTINCT));
                state.population = List.of();
                state.terminal = TerminalOutcome.EXTINCT;
                return;
            }

            MutationRound mutations = mutate(
                plan,
                generation,
                parents,
                state.population,
                mutationCatalog,
                state.budget);
            evaluate(
                plan,
                mutations.children(),
                evaluator,
                state.evaluations,
                state.budget);

            List<EvolutionGenome> pool = merge(
                state.population, mutations.children());
            List<EvolutionGenome> selected = select(
                plan, state.population, pool, state.evaluations);
            int distinctStructures = Math.toIntExact(selected.stream()
                .map(EvolutionGenome::alphaStructuralHash)
                .distinct()
                .count());
            GenerationOutcome outcome = outcome(
                plan,
                generation,
                state.population,
                selected,
                mutations,
                distinctStructures,
                state.budget);

            state.reports.add(GenerationReport.create(
                generation,
                candidateEvaluations(pool, state.evaluations),
                selected.stream().map(EvolutionGenome::contentHash).toList(),
                mutations.lineage(),
                mutations.rejections(),
                distinctStructures,
                state.budget.mutationAttempts,
                state.budget.trainEvaluations,
                outcome));
            state.population = selected;

            if (outcome != GenerationOutcome.CONTINUE) {
                state.terminal = outcome.terminal();
                return;
            }
        }
    }

    private static PopulationRun completeRun(
        EvolutionStudyPlan plan,
        List<EvolutionGenome> seedGenomes,
        ExecutionState state
    ) {
        TerminalOutcome terminal = state.terminal == null
            ? TerminalOutcome.COMPLETED
            : state.terminal;
        return PopulationRun.create(
            plan,
            seedGenomes,
            state.reports,
            state.population,
            terminal,
            state.budget.mutationAttempts,
            state.budget.trainEvaluations);
    }

    private MutationRound mutate(
        EvolutionStudyPlan plan,
        int generation,
        List<EvolutionGenome> parents,
        List<EvolutionGenome> population,
        MutationCatalog catalog,
        BudgetLedger budget
    ) {
        List<EvolutionGenome> children = new ArrayList<>();
        List<LineageEdge> lineage = new ArrayList<>();
        List<MutationRejection> rejections = new ArrayList<>();
        Set<String> genomeHashes = new LinkedHashSet<>();
        Set<String> structureHashes = new LinkedHashSet<>();
        population.forEach(genome -> {
            genomeHashes.add(genome.contentHash());
            structureHashes.add(genome.alphaStructuralHash());
        });

        Set<EvolutionMutationKind> permitted = Set.copyOf(
            plan.mutationOperators());
        int poolLimit = Math.multiplyExact(
            plan.populationPolicy().populationSize(),
            MAX_GENERATED_POOL_MULTIPLIER);

        for (EvolutionGenome parent : parents) {
            int remaining = plan.budget().maxMutationAttempts()
                - budget.mutationAttempts;
            if (remaining <= 0 || children.size() >= poolLimit) {
                break;
            }

            int maxProposals = Math.min(128, remaining);
            int maxAccepted = Math.min(
                plan.populationPolicy().maxOffspringPerLineage(),
                maxProposals);
            MutationBatch batch = mutator.mutate(
                parent,
                catalog,
                mutationSeed(plan.contentHash(), generation, parent.contentHash()),
                new MutationLimits(maxProposals, maxAccepted));
            budget.mutationAttempts = Math.addExact(
                budget.mutationAttempts,
                batch.attempts().size());

            int acceptedIndex = 0;
            for (MutationAttempt attempt : batch.attempts()) {
                if (attempt.status() == MutationStatus.REJECTED) {
                    rejections.add(MutationRejection.from(
                        parent.contentHash(), attempt, attempt.blockers()));
                    continue;
                }

                EvolutionGenome child = batch.acceptedChildren().get(acceptedIndex++);
                List<String> blockers = new ArrayList<>();
                if (!permitted.contains(attempt.kind())) {
                    blockers.add(
                        "MUTATION_KIND_NOT_PREREGISTERED:" + attempt.kind());
                }
                if (genomeHashes.contains(child.contentHash())) {
                    blockers.add("DUPLICATE_GENOME:contentHash");
                }
                if (structureHashes.contains(child.alphaStructuralHash())) {
                    blockers.add(
                        "STRUCTURAL_DIVERSITY_DUPLICATE:populationAlphaStructuralHash");
                }
                if (!blockers.isEmpty()) {
                    rejections.add(MutationRejection.from(
                        parent.contentHash(), attempt, blockers));
                    continue;
                }

                genomeHashes.add(child.contentHash());
                structureHashes.add(child.alphaStructuralHash());
                children.add(child);
                lineage.add(new LineageEdge(
                    parent.contentHash(),
                    child.contentHash(),
                    child.alphaStructuralHash(),
                    attempt.kind(),
                    attempt.proposalKey()));
                if (children.size() >= poolLimit) {
                    break;
                }
            }
        }
        return MutationRound.create(children, lineage, rejections);
    }

    private static void evaluate(
        EvolutionStudyPlan plan,
        Collection<EvolutionGenome> candidates,
        TrainFitnessEvaluator evaluator,
        Map<String, CandidateEvaluation> cache,
        BudgetLedger budget
    ) {
        List<EvolutionGenome> pending = candidates.stream()
            .filter(candidate -> !cache.containsKey(candidate.contentHash()))
            .sorted(Comparator.comparing(EvolutionGenome::contentHash))
            .toList();
        if (pending.isEmpty()) {
            return;
        }

        int remaining = Math.max(0,
            plan.budget().maxTrainEvaluations() - budget.trainEvaluations);
        int count = Math.min(remaining, pending.size());
        List<EvolutionGenome> executable = pending.subList(0, count);
        List<CandidateEvaluation> completed = evaluateParallel(
            plan, executable, evaluator);
        for (int index = 0; index < executable.size(); index++) {
            cache.put(executable.get(index).contentHash(), completed.get(index));
        }
        budget.trainEvaluations = Math.addExact(budget.trainEvaluations, count);

        for (int index = count; index < pending.size(); index++) {
            EvolutionGenome candidate = pending.get(index);
            cache.put(candidate.contentHash(), CandidateEvaluation.blocked(
                candidate,
                Map.of(),
                List.of("TRAIN_EVALUATION_BUDGET_EXHAUSTED")));
        }
    }

    private static List<CandidateEvaluation> evaluateParallel(
        EvolutionStudyPlan plan,
        List<EvolutionGenome> candidates,
        TrainFitnessEvaluator evaluator
    ) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        int parallelism = Math.min(
            plan.populationPolicy().parallelism(),
            candidates.size());
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<TrainFitness>> futures = candidates.stream()
                .map(candidate -> executor.submit(() -> evaluator.evaluate(candidate)))
                .toList();
            List<CandidateEvaluation> result = new ArrayList<>();
            for (int index = 0; index < candidates.size(); index++) {
                EvolutionGenome candidate = candidates.get(index);
                try {
                    result.add(toEvaluation(
                        plan, candidate, futures.get(index).get()));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    result.add(CandidateEvaluation.blocked(
                        candidate,
                        Map.of(),
                        List.of("TRAIN_EVALUATION_INTERRUPTED")));
                } catch (ExecutionException exception) {
                    result.add(CandidateEvaluation.blocked(
                        candidate,
                        Map.of(),
                        List.of("TRAIN_EVALUATION_FAILED:"
                            + stableFailure(exception.getCause()))));
                }
            }
            return List.copyOf(result);
        } finally {
            executor.shutdownNow();
        }
    }

    private static CandidateEvaluation toEvaluation(
        EvolutionStudyPlan plan,
        EvolutionGenome candidate,
        TrainFitness fitness
    ) {
        if (fitness == null) {
            return CandidateEvaluation.blocked(
                candidate,
                Map.of(),
                List.of("TRAIN_EVALUATOR_RETURNED_NULL"));
        }

        Map<FitnessComponent, Integer> components = fitness.components();
        List<String> rawBlockers = new ArrayList<>(fitness.blockers());
        Set<FitnessComponent> declared = plan.fitnessWeights().stream()
            .map(FitnessWeight::component)
            .collect(java.util.stream.Collectors.toSet());
        for (FitnessComponent component : declared) {
            if (!components.containsKey(component)) {
                rawBlockers.add("MISSING_FITNESS_COMPONENT:" + component);
            }
        }
        for (FitnessComponent component : components.keySet()) {
            if (!declared.contains(component)) {
                rawBlockers.add("UNDECLARED_FITNESS_COMPONENT:" + component);
            }
        }
        List<String> blockers = canonicalStrings(rawBlockers);
        if (!blockers.isEmpty()) {
            return CandidateEvaluation.blocked(candidate, components, blockers);
        }

        long weighted = 0L;
        for (FitnessWeight weight : plan.fitnessWeights()) {
            weighted = Math.addExact(weighted, Math.multiplyExact(
                components.get(weight.component()).longValue(),
                weight.weightPermille()));
        }
        return CandidateEvaluation.accepted(
            candidate,
            components,
            Math.toIntExact(weighted / 1000L));
    }

    private static List<EvolutionGenome> select(
        EvolutionStudyPlan plan,
        List<EvolutionGenome> current,
        List<EvolutionGenome> pool,
        Map<String, CandidateEvaluation> evaluations
    ) {
        Comparator<EvolutionGenome> ranking = ranking(evaluations);
        List<EvolutionGenome> result = new ArrayList<>();
        Set<String> genomeHashes = new HashSet<>();
        Set<String> structures = new HashSet<>();

        current.stream()
            .filter(genome -> evaluations.get(genome.contentHash()).accepted())
            .sorted(ranking)
            .limit(plan.populationPolicy().eliteCount())
            .forEach(genome -> addUnique(
                result, genomeHashes, structures, genome));
        pool.stream()
            .filter(genome -> evaluations.get(genome.contentHash()).accepted())
            .sorted(ranking)
            .forEach(genome -> {
                if (result.size() < plan.populationPolicy().populationSize()) {
                    addUnique(result, genomeHashes, structures, genome);
                }
            });
        return List.copyOf(result);
    }

    private static void addUnique(
        List<EvolutionGenome> result,
        Set<String> genomeHashes,
        Set<String> structures,
        EvolutionGenome genome
    ) {
        if (genomeHashes.add(genome.contentHash())
                && structures.add(genome.alphaStructuralHash())) {
            result.add(genome);
        }
    }

    private static List<EvolutionGenome> eligible(
        List<EvolutionGenome> population,
        Map<String, CandidateEvaluation> evaluations
    ) {
        return population.stream()
            .filter(genome -> evaluations.get(genome.contentHash()).accepted())
            .sorted(ranking(evaluations))
            .toList();
    }

    private static Comparator<EvolutionGenome> ranking(
        Map<String, CandidateEvaluation> evaluations
    ) {
        return Comparator
            .comparingInt((EvolutionGenome genome) -> evaluations
                .get(genome.contentHash()).weightedScorePermille())
            .reversed()
            .thenComparing(EvolutionGenome::contentHash);
    }

    private static GenerationOutcome outcome(
        EvolutionStudyPlan plan,
        int generation,
        List<EvolutionGenome> previous,
        List<EvolutionGenome> selected,
        MutationRound mutations,
        int distinctStructures,
        BudgetLedger budget
    ) {
        if (selected.isEmpty()) {
            return GenerationOutcome.EXTINCT;
        }
        if (distinctStructures
                < plan.populationPolicy().minimumDistinctAlphaStructures()) {
            return GenerationOutcome.DIVERSITY_FLOOR_UNMET;
        }
        if (generation >= plan.populationPolicy().generationCount()) {
            return GenerationOutcome.COMPLETED;
        }
        if (budget.trainEvaluations >= plan.budget().maxTrainEvaluations()) {
            return GenerationOutcome.TRAIN_EVALUATION_BUDGET_EXHAUSTED;
        }
        if (budget.mutationAttempts >= plan.budget().maxMutationAttempts()) {
            return GenerationOutcome.MUTATION_BUDGET_EXHAUSTED;
        }
        List<String> oldHashes = previous.stream()
            .map(EvolutionGenome::contentHash)
            .toList();
        List<String> newHashes = selected.stream()
            .map(EvolutionGenome::contentHash)
            .toList();
        if (mutations.children().isEmpty() || oldHashes.equals(newHashes)) {
            return GenerationOutcome.STAGNATED;
        }
        if (selected.size() < plan.populationPolicy().populationSize()) {
            return GenerationOutcome.STAGNATED;
        }
        return GenerationOutcome.CONTINUE;
    }

    private static List<EvolutionGenome> merge(
        List<EvolutionGenome> current,
        List<EvolutionGenome> children
    ) {
        Map<String, EvolutionGenome> result = new LinkedHashMap<>();
        java.util.stream.Stream.concat(current.stream(), children.stream())
            .sorted(Comparator.comparing(EvolutionGenome::contentHash))
            .forEach(genome -> result.putIfAbsent(genome.contentHash(), genome));
        return List.copyOf(result.values());
    }

    private static List<CandidateEvaluation> candidateEvaluations(
        Collection<EvolutionGenome> candidates,
        Map<String, CandidateEvaluation> evaluations
    ) {
        return candidates.stream()
            .map(genome -> evaluations.get(genome.contentHash()))
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(CandidateEvaluation::genomeHash))
            .toList();
    }

    private static List<EvolutionGenome> validateSeeds(
        EvolutionStudyPlan plan,
        List<EvolutionGenome> seeds
    ) {
        Objects.requireNonNull(seeds, "seedGenomes");
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException("seedGenomes must not be empty");
        }
        List<EvolutionGenome> result = seeds.stream()
            .map(seed -> Objects.requireNonNull(seed, "seed genome"))
            .sorted(Comparator.comparing(EvolutionGenome::contentHash))
            .toList();
        if (result.size() > plan.populationPolicy().populationSize()) {
            throw new IllegalArgumentException(
                "seed population exceeds populationSize");
        }
        if (result.stream().anyMatch(seed ->
                seed.objective() != plan.objective())) {
            throw new IllegalArgumentException(
                "seed objective differs from study plan");
        }
        List<String> hashes = result.stream()
            .map(EvolutionGenome::contentHash)
            .toList();
        if (!hashes.equals(plan.seedGenomeHashes())) {
            throw new IllegalArgumentException(
                "seed genomes do not match preregistered seedGenomeHashes");
        }
        if (new HashSet<>(hashes).size() != hashes.size()) {
            throw new IllegalArgumentException("seed genomes must be unique");
        }
        if (result.stream().map(EvolutionGenome::alphaStructuralHash)
                .distinct().count() != result.size()) {
            throw new IllegalArgumentException(
                "seed genomes must be alpha-unique");
        }
        return List.copyOf(result);
    }

    private static long mutationSeed(
        String planHash,
        int generation,
        String parentHash
    ) {
        String hash = EvolutionGenome.hash(
            "regelsuche.evolution-mutation-seed/v1\nplan=" + planHash
                + "\ngeneration=" + generation
                + "\nparent=" + parentHash);
        return Long.parseUnsignedLong(
            hash.substring("sha256:".length(), 23), 16);
    }

    private static String stableFailure(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
            + (message == null || message.isBlank()
                ? ""
                : ":" + message.replaceAll("\\s+", " ").trim());
    }

    @FunctionalInterface
    public interface TrainFitnessEvaluator {
        TrainFitness evaluate(EvolutionGenome genome);
    }

    /** Raw named TRAIN components plus fail-closed blockers. */
    public record TrainFitness(
        Map<FitnessComponent, Integer> components,
        List<String> blockers
    ) {
        public TrainFitness {
            components = canonicalComponents(components);
            blockers = canonicalStrings(blockers);
        }

        public static TrainFitness scored(
            Map<FitnessComponent, Integer> components
        ) {
            return new TrainFitness(components, List.of());
        }

        public static TrainFitness blocked(
            Map<FitnessComponent, Integer> components,
            String... blockers
        ) {
            return new TrainFitness(components, List.of(blockers));
        }
    }

    public enum EvaluationStatus {
        ACCEPTED,
        BLOCKED
    }

    public record CandidateEvaluation(
        String genomeHash,
        String alphaStructuralHash,
        Map<FitnessComponent, Integer> rawComponents,
        int weightedScorePermille,
        EvaluationStatus status,
        List<String> blockers
    ) {
        public CandidateEvaluation {
            EvolutionGenome.requireSha256(genomeHash, "genomeHash");
            EvolutionGenome.requireSha256(
                alphaStructuralHash, "alphaStructuralHash");
            rawComponents = canonicalComponents(rawComponents);
            if (weightedScorePermille < -1000
                    || weightedScorePermille > 1000) {
                throw new IllegalArgumentException(
                    "weightedScorePermille must be in [-1000,1000]");
            }
            Objects.requireNonNull(status, "status");
            blockers = canonicalStrings(blockers);
            if (status == EvaluationStatus.ACCEPTED && !blockers.isEmpty()) {
                throw new IllegalArgumentException(
                    "accepted candidate cannot have blockers");
            }
            if (status == EvaluationStatus.BLOCKED && blockers.isEmpty()) {
                throw new IllegalArgumentException(
                    "blocked candidate requires blockers");
            }
        }

        static CandidateEvaluation accepted(
            EvolutionGenome genome,
            Map<FitnessComponent, Integer> components,
            int score
        ) {
            return new CandidateEvaluation(
                genome.contentHash(),
                genome.alphaStructuralHash(),
                components,
                score,
                EvaluationStatus.ACCEPTED,
                List.of());
        }

        static CandidateEvaluation blocked(
            EvolutionGenome genome,
            Map<FitnessComponent, Integer> components,
            List<String> blockers
        ) {
            return new CandidateEvaluation(
                genome.contentHash(),
                genome.alphaStructuralHash(),
                components,
                0,
                EvaluationStatus.BLOCKED,
                blockers);
        }

        public boolean accepted() {
            return status == EvaluationStatus.ACCEPTED;
        }
    }

    public record LineageEdge(
        String parentGenomeHash,
        String childGenomeHash,
        String childAlphaStructuralHash,
        EvolutionMutationKind mutationKind,
        String proposalKey
    ) {
        public LineageEdge {
            EvolutionGenome.requireSha256(
                parentGenomeHash, "parentGenomeHash");
            EvolutionGenome.requireSha256(childGenomeHash, "childGenomeHash");
            EvolutionGenome.requireSha256(
                childAlphaStructuralHash, "childAlphaStructuralHash");
            Objects.requireNonNull(mutationKind, "mutationKind");
            requireText(proposalKey, "proposalKey");
        }
    }

    public record MutationRejection(
        String parentGenomeHash,
        int ordinal,
        EvolutionMutationKind mutationKind,
        String proposalKey,
        String childGenomeHash,
        List<String> blockers
    ) {
        public MutationRejection {
            EvolutionGenome.requireSha256(
                parentGenomeHash, "parentGenomeHash");
            if (ordinal < 1) {
                throw new IllegalArgumentException("ordinal must be positive");
            }
            Objects.requireNonNull(mutationKind, "mutationKind");
            requireText(proposalKey, "proposalKey");
            if (childGenomeHash != null) {
                EvolutionGenome.requireSha256(
                    childGenomeHash, "childGenomeHash");
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
                attempt.ordinal(),
                attempt.kind(),
                attempt.proposalKey(),
                attempt.childGenomeHash(),
                blockers);
        }
    }

    public enum GenerationOutcome {
        CONTINUE,
        COMPLETED,
        EXTINCT,
        STAGNATED,
        DIVERSITY_FLOOR_UNMET,
        MUTATION_BUDGET_EXHAUSTED,
        TRAIN_EVALUATION_BUDGET_EXHAUSTED;

        TerminalOutcome terminal() {
            return switch (this) {
                case CONTINUE -> throw new IllegalStateException(
                    "CONTINUE is not terminal");
                case COMPLETED -> TerminalOutcome.COMPLETED;
                case EXTINCT -> TerminalOutcome.EXTINCT;
                case STAGNATED -> TerminalOutcome.STAGNATED;
                case DIVERSITY_FLOOR_UNMET ->
                    TerminalOutcome.DIVERSITY_FLOOR_UNMET;
                case MUTATION_BUDGET_EXHAUSTED ->
                    TerminalOutcome.MUTATION_BUDGET_EXHAUSTED;
                case TRAIN_EVALUATION_BUDGET_EXHAUSTED ->
                    TerminalOutcome.TRAIN_EVALUATION_BUDGET_EXHAUSTED;
            };
        }
    }

    public enum TerminalOutcome {
        COMPLETED,
        EXTINCT,
        STAGNATED,
        DIVERSITY_FLOOR_UNMET,
        MUTATION_BUDGET_EXHAUSTED,
        TRAIN_EVALUATION_BUDGET_EXHAUSTED
    }

    /** Canonical evidence for one generation. */
    public record GenerationReport(
        String schema,
        int generation,
        List<CandidateEvaluation> candidates,
        List<String> selectedGenomeHashes,
        List<LineageEdge> acceptedLineage,
        List<MutationRejection> rejectedMutations,
        int distinctAlphaStructures,
        int cumulativeMutationAttempts,
        int cumulativeTrainEvaluations,
        GenerationOutcome outcome,
        String contentHash
    ) {
        public GenerationReport {
            if (!GENERATION_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported generation-report schema");
            }
            if (generation < 1) {
                throw new IllegalArgumentException(
                    "generation must be positive");
            }
            candidates = canonicalCandidates(candidates);
            selectedGenomeHashes = canonicalHashes(
                selectedGenomeHashes, false);
            acceptedLineage = canonicalLineage(acceptedLineage);
            rejectedMutations = canonicalRejections(rejectedMutations);
            if (distinctAlphaStructures < 0
                    || cumulativeMutationAttempts < 0
                    || cumulativeTrainEvaluations < 0) {
                throw new IllegalArgumentException(
                    "generation counts must be non-negative");
            }
            if (distinctAlphaStructures != selectedGenomeHashes.size()) {
                throw new IllegalArgumentException(
                    "selected population must be alpha-structurally unique");
            }
            Objects.requireNonNull(outcome, "outcome");
            EvolutionGenome.requireSha256(contentHash, "contentHash");
            String expected = EvolutionGenome.hash(renderGeneration(
                generation,
                candidates,
                selectedGenomeHashes,
                acceptedLineage,
                rejectedMutations,
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
            List<CandidateEvaluation> candidates,
            List<String> selectedGenomeHashes,
            List<LineageEdge> lineage,
            List<MutationRejection> rejections,
            int distinctStructures,
            int mutationAttempts,
            int trainEvaluations,
            GenerationOutcome outcome
        ) {
            List<CandidateEvaluation> canonicalCandidates =
                canonicalCandidates(candidates);
            List<String> canonicalSelected = canonicalHashes(
                selectedGenomeHashes, false);
            List<LineageEdge> canonicalLineage = canonicalLineage(lineage);
            List<MutationRejection> canonicalRejections =
                canonicalRejections(rejections);
            String hash = EvolutionGenome.hash(renderGeneration(
                generation,
                canonicalCandidates,
                canonicalSelected,
                canonicalLineage,
                canonicalRejections,
                distinctStructures,
                mutationAttempts,
                trainEvaluations,
                outcome,
                null));
            return new GenerationReport(
                GENERATION_SCHEMA,
                generation,
                canonicalCandidates,
                canonicalSelected,
                canonicalLineage,
                canonicalRejections,
                distinctStructures,
                mutationAttempts,
                trainEvaluations,
                outcome,
                hash);
        }

        public String toCanonicalJson() {
            return renderGeneration(
                generation,
                candidates,
                selectedGenomeHashes,
                acceptedLineage,
                rejectedMutations,
                distinctAlphaStructures,
                cumulativeMutationAttempts,
                cumulativeTrainEvaluations,
                outcome,
                contentHash);
        }
    }

    /** Root identity for one TRAIN-only population execution. */
    public record PopulationRun(
        String schema,
        String studyPlanHash,
        List<String> seedGenomeHashes,
        List<GenerationReport> generationReports,
        List<EvolutionGenome> finalPopulation,
        TerminalOutcome terminalOutcome,
        int mutationAttempts,
        int trainEvaluations,
        String contentHash
    ) {
        public PopulationRun {
            if (!RUN_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported population-run schema");
            }
            EvolutionGenome.requireSha256(studyPlanHash, "studyPlanHash");
            seedGenomeHashes = canonicalHashes(seedGenomeHashes, true);
            generationReports = canonicalReports(generationReports);
            finalPopulation = canonicalPopulation(finalPopulation);
            if (mutationAttempts < 0 || trainEvaluations < 0) {
                throw new IllegalArgumentException(
                    "run counts must be non-negative");
            }
            Objects.requireNonNull(terminalOutcome, "terminalOutcome");
            if (finalPopulation.stream()
                    .map(EvolutionGenome::alphaStructuralHash)
                    .distinct().count() != finalPopulation.size()) {
                throw new IllegalArgumentException(
                    "final population must be alpha-structurally unique");
            }
            EvolutionGenome.requireSha256(contentHash, "contentHash");
            String expected = EvolutionGenome.hash(renderRun(
                studyPlanHash,
                seedGenomeHashes,
                generationReports,
                finalPopulation,
                terminalOutcome,
                mutationAttempts,
                trainEvaluations,
                null));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "population run contentHash mismatch");
            }
        }

        static PopulationRun create(
            EvolutionStudyPlan plan,
            List<EvolutionGenome> seeds,
            List<GenerationReport> reports,
            List<EvolutionGenome> finalPopulation,
            TerminalOutcome outcome,
            int mutationAttempts,
            int trainEvaluations
        ) {
            List<String> seedHashes = canonicalHashes(seeds.stream()
                .map(EvolutionGenome::contentHash).toList(), true);
            List<GenerationReport> canonicalReports = canonicalReports(reports);
            List<EvolutionGenome> canonicalPopulation =
                canonicalPopulation(finalPopulation);
            String hash = EvolutionGenome.hash(renderRun(
                plan.contentHash(),
                seedHashes,
                canonicalReports,
                canonicalPopulation,
                outcome,
                mutationAttempts,
                trainEvaluations,
                null));
            return new PopulationRun(
                RUN_SCHEMA,
                plan.contentHash(),
                seedHashes,
                canonicalReports,
                canonicalPopulation,
                outcome,
                mutationAttempts,
                trainEvaluations,
                hash);
        }

        public String toCanonicalJson() {
            return renderRun(
                studyPlanHash,
                seedGenomeHashes,
                generationReports,
                finalPopulation,
                terminalOutcome,
                mutationAttempts,
                trainEvaluations,
                contentHash);
        }
    }

    private static String renderGeneration(
        int generation,
        List<CandidateEvaluation> candidates,
        List<String> selectedHashes,
        List<LineageEdge> lineage,
        List<MutationRejection> rejections,
        int distinctStructures,
        int mutationAttempts,
        int trainEvaluations,
        GenerationOutcome outcome,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", GENERATION_SCHEMA)
            .property("generation", generation)
            .array("candidates", array -> candidates.forEach(candidate ->
                array.objectValue(object -> writeCandidate(object, candidate))))
            .stringArray("selectedGenomeHashes", selectedHashes)
            .array("acceptedLineage", array -> lineage.forEach(edge ->
                array.objectValue(object -> object
                    .property("parentGenomeHash", edge.parentGenomeHash())
                    .property("childGenomeHash", edge.childGenomeHash())
                    .property("childAlphaStructuralHash",
                        edge.childAlphaStructuralHash())
                    .property("mutationKind", edge.mutationKind().name())
                    .property("proposalKey", edge.proposalKey()))))
            .array("rejectedMutations", array -> rejections.forEach(rejection ->
                array.objectValue(object -> writeRejection(object, rejection))))
            .property("distinctAlphaStructures", distinctStructures)
            .object("budgetCumulative", object -> object
                .property("mutationAttempts", mutationAttempts)
                .property("trainEvaluations", trainEvaluations))
            .property("outcome", outcome.name());
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private static void writeCandidate(
        JsonWriter json,
        CandidateEvaluation candidate
    ) {
        json.property("genomeHash", candidate.genomeHash())
            .property("alphaStructuralHash", candidate.alphaStructuralHash())
            .object("rawComponents", object ->
                candidate.rawComponents().forEach((component, value) ->
                    object.property(component.name(), value)))
            .property("weightedScorePermille",
                candidate.weightedScorePermille())
            .property("status", candidate.status().name())
            .stringArray("blockers", candidate.blockers());
    }

    private static void writeRejection(
        JsonWriter json,
        MutationRejection rejection
    ) {
        json.property("parentGenomeHash", rejection.parentGenomeHash())
            .property("ordinal", rejection.ordinal())
            .property("mutationKind", rejection.mutationKind().name())
            .property("proposalKey", rejection.proposalKey());
        if (rejection.childGenomeHash() == null) {
            json.nullProperty("childGenomeHash");
        } else {
            json.property("childGenomeHash", rejection.childGenomeHash());
        }
        json.stringArray("blockers", rejection.blockers());
    }

    private static String renderRun(
        String studyPlanHash,
        List<String> seedHashes,
        List<GenerationReport> reports,
        List<EvolutionGenome> finalPopulation,
        TerminalOutcome outcome,
        int mutationAttempts,
        int trainEvaluations,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", RUN_SCHEMA)
            .property("studyPlanHash", studyPlanHash)
            .stringArray("seedGenomeHashes", seedHashes)
            .stringArray("generationReportHashes", reports.stream()
                .map(GenerationReport::contentHash).toList())
            .stringArray("finalPopulationGenomeHashes", finalPopulation.stream()
                .map(EvolutionGenome::contentHash).toList())
            .stringArray("finalPopulationAlphaStructuralHashes",
                finalPopulation.stream()
                    .map(EvolutionGenome::alphaStructuralHash).toList())
            .property("terminalOutcome", outcome.name())
            .object("budgetUsed", object -> object
                .property("mutationAttempts", mutationAttempts)
                .property("trainEvaluations", trainEvaluations))
            .property("validationStatus", "NOT_EVALUATED")
            .property("finalTestStatus", "NOT_EVALUATED")
            .property("proofStatus", "NOT_EVALUATED")
            .property("externalNoveltyStatus", "NOT_EVALUATED")
            .property("promotionStatus", "NOT_EVALUATED")
            .property("publicEvidenceStatus", "NOT_EVALUATED");
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private static Map<FitnessComponent, Integer> canonicalComponents(
        Map<FitnessComponent, Integer> components
    ) {
        if (components == null || components.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<FitnessComponent, Integer> result = new LinkedHashMap<>();
        components.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().name()))
            .forEach(entry -> {
                FitnessComponent component = Objects.requireNonNull(
                    entry.getKey(), "fitness component");
                Integer value = Objects.requireNonNull(
                    entry.getValue(), "fitness component value");
                if (value < -1000 || value > 1000) {
                    throw new IllegalArgumentException(
                        "fitness component must be in [-1000,1000]: "
                            + component);
                }
                result.put(component, value);
            });
        return Collections.unmodifiableMap(result);
    }

    private static List<String> canonicalStrings(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .map(value -> requireText(value, "blocker"))
            .distinct()
            .sorted()
            .toList();
    }

    private static List<String> canonicalHashes(
        List<String> values,
        boolean sort
    ) {
        if (values == null) {
            return List.of();
        }
        List<String> result = values.stream()
            .map(value -> {
                EvolutionGenome.requireSha256(value, "hash");
                return value;
            })
            .toList();
        if (new HashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException("hashes must be unique");
        }
        return sort ? result.stream().sorted().toList() : List.copyOf(result);
    }

    private static List<CandidateEvaluation> canonicalCandidates(
        List<CandidateEvaluation> values
    ) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .map(value -> Objects.requireNonNull(value, "candidate"))
            .sorted(Comparator.comparing(CandidateEvaluation::genomeHash))
            .toList();
    }

    private static List<LineageEdge> canonicalLineage(
        List<LineageEdge> values
    ) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .map(value -> Objects.requireNonNull(value, "lineage edge"))
            .sorted(Comparator.comparing(LineageEdge::parentGenomeHash)
                .thenComparing(LineageEdge::childGenomeHash))
            .toList();
    }

    private static List<MutationRejection> canonicalRejections(
        List<MutationRejection> values
    ) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .map(value -> Objects.requireNonNull(value, "rejection"))
            .sorted(Comparator.comparing(MutationRejection::parentGenomeHash)
                .thenComparingInt(MutationRejection::ordinal)
                .thenComparing(MutationRejection::proposalKey))
            .toList();
    }

    private static List<GenerationReport> canonicalReports(
        List<GenerationReport> values
    ) {
        if (values == null) {
            return List.of();
        }
        List<GenerationReport> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "generation report"))
            .sorted(Comparator.comparingInt(GenerationReport::generation))
            .toList();
        for (int index = 0; index < result.size(); index++) {
            if (result.get(index).generation() != index + 1) {
                throw new IllegalArgumentException(
                    "generation reports must be contiguous");
            }
        }
        return result;
    }

    private static List<EvolutionGenome> canonicalPopulation(
        List<EvolutionGenome> values
    ) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .map(value -> Objects.requireNonNull(value, "final genome"))
            .toList();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record MutationRound(
        List<EvolutionGenome> children,
        List<LineageEdge> lineage,
        List<MutationRejection> rejections
    ) {
        static MutationRound create(
            List<EvolutionGenome> children,
            List<LineageEdge> lineage,
            List<MutationRejection> rejections
        ) {
            return new MutationRound(
                children.stream()
                    .sorted(Comparator.comparing(EvolutionGenome::contentHash))
                    .toList(),
                canonicalLineage(lineage),
                canonicalRejections(rejections));
        }
    }

    private static final class ExecutionState {
        private List<EvolutionGenome> population;
        private final Map<String, CandidateEvaluation> evaluations;
        private final BudgetLedger budget;
        private final List<GenerationReport> reports;
        private TerminalOutcome terminal;

        private ExecutionState(
            List<EvolutionGenome> population,
            Map<String, CandidateEvaluation> evaluations,
            int mutationAttempts,
            int trainEvaluations,
            List<GenerationReport> reports
        ) {
            this.population = List.copyOf(population);
            this.evaluations = new HashMap<>(evaluations);
            this.budget = new BudgetLedger();
            this.budget.mutationAttempts = mutationAttempts;
            this.budget.trainEvaluations = trainEvaluations;
            this.reports = new ArrayList<>(reports);
        }
    }

    private static final class BudgetLedger {
        private int mutationAttempts;
        private int trainEvaluations;
    }
}
