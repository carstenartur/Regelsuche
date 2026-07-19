package de.regelsuche.evolution;

import de.regelsuche.evolution.DeterministicGenomeMutator.MutationAttempt;
import de.regelsuche.evolution.DeterministicGenomeMutator.MutationBatch;
import de.regelsuche.evolution.DeterministicGenomeMutator.MutationCatalog;
import de.regelsuche.evolution.DeterministicGenomeMutator.MutationLimits;
import de.regelsuche.evolution.DeterministicGenomeMutator.MutationStatus;
import de.regelsuche.evolution.EvolutionPopulationEngine.CandidateEvaluation;
import de.regelsuche.evolution.EvolutionPopulationEngine.GenerationOutcome;
import de.regelsuche.evolution.EvolutionPopulationEngine.GenerationReport;
import de.regelsuche.evolution.EvolutionPopulationEngine.LineageEdge;
import de.regelsuche.evolution.EvolutionPopulationEngine.MutationRejection;
import de.regelsuche.evolution.EvolutionPopulationEngine.PopulationRun;
import de.regelsuche.evolution.EvolutionPopulationEngine.TerminalOutcome;
import de.regelsuche.evolution.EvolutionPopulationEngine.TrainFitness;
import de.regelsuche.evolution.EvolutionPopulationEngine.TrainFitnessEvaluator;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessWeight;
import java.util.ArrayList;
import java.util.Collection;
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
 * Generation-boundary state machine equivalent to {@link EvolutionPopulationEngine}.
 *
 * <p>Checkpoints are permitted only after a generation whose outcome is
 * {@code CONTINUE}. Resume binds the exact study, seeds, mutation catalog and
 * evaluator configuration before any further TRAIN evaluation occurs.</p>
 */
public final class ResumableEvolutionPopulationEngine {
    private static final int MAX_GENERATED_POOL_MULTIPLIER = 2;

    private final DeterministicGenomeMutator mutator;

    public ResumableEvolutionPopulationEngine() {
        this(new DeterministicGenomeMutator());
    }

    public ResumableEvolutionPopulationEngine(
        DeterministicGenomeMutator mutator
    ) {
        this.mutator = Objects.requireNonNull(mutator, "mutator");
    }

    /** Executes a complete run through the resumable state machine. */
    public PopulationRun run(
        EvolutionStudyPlan plan,
        List<EvolutionGenome> seedGenomes,
        MutationCatalog mutationCatalog,
        TrainFitnessEvaluator evaluator
    ) {
        State state = initialState(plan, seedGenomes);
        return execute(
            plan,
            seedGenomes,
            mutationCatalog,
            evaluator,
            null,
            null,
            state).run();
    }

    /**
     * Executes through the requested generation boundary and returns a checkpoint.
     * Generation zero is the preregistered seed state before any evaluation.
     */
    public EvolutionPopulationCheckpoint checkpointAfter(
        EvolutionStudyPlan plan,
        List<EvolutionGenome> seedGenomes,
        MutationCatalog mutationCatalog,
        TrainFitnessEvaluator evaluator,
        String evaluatorConfigurationHash,
        int completedGenerations
    ) {
        Objects.requireNonNull(plan, "plan");
        if (completedGenerations < 0
                || completedGenerations >= plan.populationPolicy().generationCount()) {
            throw new IllegalArgumentException(
                "checkpoint generation must be in [0,generationCount)");
        }
        EvolutionGenome.requireSha256(
            evaluatorConfigurationHash, "evaluatorConfigurationHash");
        State state = initialState(plan, seedGenomes);
        if (completedGenerations == 0) {
            return checkpoint(
                plan,
                seedGenomes,
                mutationCatalog,
                evaluatorConfigurationHash,
                state);
        }
        Segment segment = execute(
            plan,
            seedGenomes,
            mutationCatalog,
            evaluator,
            evaluatorConfigurationHash,
            completedGenerations,
            state);
        if (segment.checkpoint() == null) {
            throw new IllegalStateException(
                "population terminated before checkpoint generation");
        }
        return segment.checkpoint();
    }

    /** Resumes from a verified generation-boundary checkpoint. */
    public PopulationRun resume(
        EvolutionStudyPlan plan,
        List<EvolutionGenome> seedGenomes,
        MutationCatalog mutationCatalog,
        TrainFitnessEvaluator evaluator,
        String evaluatorConfigurationHash,
        EvolutionPopulationCheckpoint checkpoint
    ) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        validateResumeBindings(
            plan,
            seedGenomes,
            mutationCatalog,
            evaluatorConfigurationHash,
            checkpoint);
        State state = new State(
            checkpoint.completedGenerations() + 1,
            checkpoint.currentPopulation(),
            evaluationMap(checkpoint.evaluationCache()),
            checkpoint.generationReports(),
            checkpoint.mutationAttempts(),
            checkpoint.trainEvaluations());
        return execute(
            plan,
            seedGenomes,
            mutationCatalog,
            evaluator,
            null,
            null,
            state).run();
    }

    private Segment execute(
        EvolutionStudyPlan plan,
        List<EvolutionGenome> seedGenomes,
        MutationCatalog mutationCatalog,
        TrainFitnessEvaluator evaluator,
        String checkpointEvaluatorHash,
        Integer checkpointGeneration,
        State initial
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(mutationCatalog, "mutationCatalog");
        Objects.requireNonNull(evaluator, "evaluator");

        List<EvolutionGenome> population = new ArrayList<>(initial.population());
        Map<String, CandidateEvaluation> evaluations =
            new HashMap<>(initial.evaluations());
        BudgetLedger budget = new BudgetLedger(
            initial.mutationAttempts(), initial.trainEvaluations());
        List<GenerationReport> reports = new ArrayList<>(initial.reports());
        TerminalOutcome terminal = null;

        for (int generation = initial.nextGeneration();
                generation <= plan.populationPolicy().generationCount();
                generation++) {
            evaluate(plan, population, evaluator, evaluations, budget);
            List<EvolutionGenome> parents = eligible(population, evaluations);
            if (parents.isEmpty()) {
                reports.add(GenerationReport.create(
                    generation,
                    candidateEvaluations(population, evaluations),
                    List.of(),
                    List.of(),
                    List.of(),
                    0,
                    budget.mutationAttempts,
                    budget.trainEvaluations,
                    GenerationOutcome.EXTINCT));
                population = List.of();
                terminal = TerminalOutcome.EXTINCT;
                break;
            }

            MutationRound mutations = mutate(
                plan,
                generation,
                parents,
                population,
                mutationCatalog,
                budget);
            evaluate(plan, mutations.children(), evaluator, evaluations, budget);

            List<EvolutionGenome> pool = merge(population, mutations.children());
            List<EvolutionGenome> selected = select(
                plan, population, pool, evaluations);
            int distinctStructures = Math.toIntExact(selected.stream()
                .map(EvolutionGenome::alphaStructuralHash)
                .distinct()
                .count());
            GenerationOutcome outcome = outcome(
                plan,
                generation,
                population,
                selected,
                mutations,
                distinctStructures,
                budget);

            reports.add(GenerationReport.create(
                generation,
                candidateEvaluations(pool, evaluations),
                selected.stream().map(EvolutionGenome::contentHash).toList(),
                mutations.lineage(),
                mutations.rejections(),
                distinctStructures,
                budget.mutationAttempts,
                budget.trainEvaluations,
                outcome));
            population = selected;

            if (checkpointGeneration != null
                    && generation == checkpointGeneration) {
                if (outcome != GenerationOutcome.CONTINUE) {
                    return new Segment(null, completedRun(
                        plan,
                        seedGenomes,
                        reports,
                        population,
                        outcome.terminal(),
                        budget));
                }
                return new Segment(checkpoint(
                    plan,
                    seedGenomes,
                    mutationCatalog,
                    checkpointEvaluatorHash,
                    new State(
                        generation + 1,
                        population,
                        evaluations,
                        reports,
                        budget.mutationAttempts,
                        budget.trainEvaluations)), null);
            }

            if (outcome != GenerationOutcome.CONTINUE) {
                terminal = outcome.terminal();
                break;
            }
        }

        if (terminal == null) {
            terminal = TerminalOutcome.COMPLETED;
        }
        return new Segment(null, completedRun(
            plan,
            seedGenomes,
            reports,
            population,
            terminal,
            budget));
    }

    private static PopulationRun completedRun(
        EvolutionStudyPlan plan,
        List<EvolutionGenome> seeds,
        List<GenerationReport> reports,
        List<EvolutionGenome> population,
        TerminalOutcome terminal,
        BudgetLedger budget
    ) {
        return PopulationRun.create(
            plan,
            seeds,
            reports,
            population,
            terminal,
            budget.mutationAttempts,
            budget.trainEvaluations);
    }

    private static EvolutionPopulationCheckpoint checkpoint(
        EvolutionStudyPlan plan,
        List<EvolutionGenome> seeds,
        MutationCatalog catalog,
        String evaluatorConfigurationHash,
        State state
    ) {
        return EvolutionPopulationCheckpoint.create(
            plan,
            seeds,
            catalog,
            evaluatorConfigurationHash,
            state.reports(),
            state.population(),
            state.evaluations(),
            state.mutationAttempts(),
            state.trainEvaluations());
    }

    private static State initialState(
        EvolutionStudyPlan plan,
        List<EvolutionGenome> seeds
    ) {
        return new State(
            1,
            validateSeeds(plan, seeds),
            Map.of(),
            List.of(),
            0,
            0);
    }

    private static void validateResumeBindings(
        EvolutionStudyPlan plan,
        List<EvolutionGenome> seeds,
        MutationCatalog catalog,
        String evaluatorConfigurationHash,
        EvolutionPopulationCheckpoint checkpoint
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(catalog, "catalog");
        EvolutionGenome.requireSha256(
            evaluatorConfigurationHash, "evaluatorConfigurationHash");
        List<EvolutionGenome> canonicalSeeds = validateSeeds(plan, seeds);
        if (!checkpoint.studyPlanHash().equals(plan.contentHash())) {
            throw new IllegalArgumentException("checkpoint study plan mismatch");
        }
        if (!checkpoint.seedGenomeHashes().equals(canonicalSeeds.stream()
                .map(EvolutionGenome::contentHash).toList())) {
            throw new IllegalArgumentException("checkpoint seed mismatch");
        }
        if (!checkpoint.mutationCatalogHash().equals(
                EvolutionPopulationCheckpoint.mutationCatalogHash(catalog))) {
            throw new IllegalArgumentException(
                "checkpoint mutation catalog mismatch");
        }
        if (!checkpoint.evaluatorConfigurationHash().equals(
                evaluatorConfigurationHash)) {
            throw new IllegalArgumentException(
                "checkpoint evaluator configuration mismatch");
        }
        if (checkpoint.completedGenerations()
                >= plan.populationPolicy().generationCount()) {
            throw new IllegalArgumentException(
                "checkpoint has no remaining generation");
        }
        if (checkpoint.mutationAttempts()
                > plan.budget().maxMutationAttempts()
                || checkpoint.trainEvaluations()
                    > plan.budget().maxTrainEvaluations()) {
            throw new IllegalArgumentException(
                "checkpoint exceeds preregistered budget");
        }
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
        budget.trainEvaluations = Math.addExact(
            budget.trainEvaluations, count);

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

    private static Map<String, CandidateEvaluation> evaluationMap(
        List<CandidateEvaluation> evaluations
    ) {
        Map<String, CandidateEvaluation> result = new HashMap<>();
        for (CandidateEvaluation evaluation : evaluations) {
            if (result.put(evaluation.genomeHash(), evaluation) != null) {
                throw new IllegalArgumentException(
                    "checkpoint evaluation cache contains duplicates");
            }
        }
        return result;
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

    private static List<String> canonicalStrings(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .map(value -> {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException(
                        "blocker must not be blank");
                }
                return value;
            })
            .distinct()
            .sorted()
            .toList();
    }

    private record State(
        int nextGeneration,
        List<EvolutionGenome> population,
        Map<String, CandidateEvaluation> evaluations,
        List<GenerationReport> reports,
        int mutationAttempts,
        int trainEvaluations
    ) {
        private State {
            if (nextGeneration < 1
                    || mutationAttempts < 0
                    || trainEvaluations < 0) {
                throw new IllegalArgumentException("invalid population state");
            }
            population = List.copyOf(population);
            evaluations = Map.copyOf(evaluations);
            reports = List.copyOf(reports);
        }
    }

    private record Segment(
        EvolutionPopulationCheckpoint checkpoint,
        PopulationRun run
    ) {
        private Segment {
            if ((checkpoint == null) == (run == null)) {
                throw new IllegalArgumentException(
                    "segment must contain exactly one result");
            }
        }
    }

    private record MutationRound(
        List<EvolutionGenome> children,
        List<LineageEdge> lineage,
        List<MutationRejection> rejections
    ) {
        private static MutationRound create(
            List<EvolutionGenome> children,
            List<LineageEdge> lineage,
            List<MutationRejection> rejections
        ) {
            return new MutationRound(
                List.copyOf(children),
                List.copyOf(lineage),
                List.copyOf(rejections));
        }
    }

    private static final class BudgetLedger {
        private int mutationAttempts;
        private int trainEvaluations;

        private BudgetLedger(int mutationAttempts, int trainEvaluations) {
            this.mutationAttempts = mutationAttempts;
            this.trainEvaluations = trainEvaluations;
        }
    }
}
