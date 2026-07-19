package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionGenome.Objective;
import de.regelsuche.json.JsonWriter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Preregistered configuration for one bounded evolutionary study.
 *
 * <p>The plan is intentionally pre-execution only. It contains no VALIDATION or
 * FINAL TEST case payloads, outcomes, selected configurations or promotion
 * decisions. Those stages require separate versioned artifacts.</p>
 */
public record EvolutionStudyPlan(
    String schema,
    String studyId,
    Objective objective,
    String splitManifestHash,
    List<String> seedGenomeHashes,
    List<EvolutionMutationKind> mutationOperators,
    PopulationPolicy populationPolicy,
    List<FitnessWeight> fitnessWeights,
    StudyBudget budget,
    StudyStatus status,
    FinalTestPolicy finalTestPolicy,
    GateStatus proofStatus,
    GateStatus externalNoveltyStatus,
    GateStatus promotionStatus,
    GateStatus publicEvidenceStatus,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.evolution-study-plan/v1";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{2,127}");
    private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

    public EvolutionStudyPlan {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported evolution study-plan schema");
        }
        requireId(studyId, "studyId");
        Objects.requireNonNull(objective, "objective");
        requireHash(splitManifestHash, "splitManifestHash");
        seedGenomeHashes = normalizeHashes(seedGenomeHashes, "seedGenomeHashes");
        mutationOperators = normalizeMutations(mutationOperators);
        Objects.requireNonNull(populationPolicy, "populationPolicy");
        fitnessWeights = normalizeFitnessWeights(fitnessWeights);
        Objects.requireNonNull(budget, "budget");
        if (status != StudyStatus.NOT_STARTED) {
            throw new IllegalArgumentException("v1 study plan must remain NOT_STARTED");
        }
        if (finalTestPolicy
                != FinalTestPolicy.ONE_TIME_AFTER_FROZEN_VALIDATION_SELECTION) {
            throw new IllegalArgumentException("unsupported final-test policy");
        }
        requireNotEvaluated(proofStatus, "proofStatus");
        requireNotEvaluated(externalNoveltyStatus, "externalNoveltyStatus");
        requireNotEvaluated(promotionStatus, "promotionStatus");
        requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
        requireHash(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            studyId,
            objective,
            splitManifestHash,
            seedGenomeHashes,
            mutationOperators,
            populationPolicy,
            fitnessWeights,
            budget,
            status,
            finalTestPolicy,
            proofStatus,
            externalNoveltyStatus,
            promotionStatus,
            publicEvidenceStatus,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException("contentHash does not match study plan");
        }
    }

    public static EvolutionStudyPlan create(
        String studyId,
        Objective objective,
        EvolutionSplitManifest splitManifest,
        List<EvolutionGenome> seedGenomes,
        List<EvolutionMutationKind> mutationOperators,
        PopulationPolicy populationPolicy,
        List<FitnessWeight> fitnessWeights,
        StudyBudget budget
    ) {
        Objects.requireNonNull(splitManifest, "splitManifest");
        Objects.requireNonNull(seedGenomes, "seedGenomes");
        if (seedGenomes.isEmpty()) {
            throw new IllegalArgumentException("seedGenomes must not be empty");
        }
        for (EvolutionGenome genome : seedGenomes) {
            Objects.requireNonNull(genome, "seed genome");
            if (genome.objective() != objective) {
                throw new IllegalArgumentException("seed genome objective differs from study");
            }
            if (!genome.trainingScope().equals(splitManifest.trainingScope())) {
                throw new IllegalArgumentException(
                    "seed genome is not bound to the manifest TRAIN scope");
            }
        }
        List<String> seeds = normalizeHashes(
            seedGenomes.stream().map(EvolutionGenome::contentHash).toList(),
            "seedGenomeHashes");
        List<EvolutionMutationKind> mutations = normalizeMutations(mutationOperators);
        List<FitnessWeight> weights = normalizeFitnessWeights(fitnessWeights);
        String content = EvolutionGenome.hash(render(
            studyId,
            objective,
            splitManifest.contentHash(),
            seeds,
            mutations,
            populationPolicy,
            weights,
            budget,
            StudyStatus.NOT_STARTED,
            FinalTestPolicy.ONE_TIME_AFTER_FROZEN_VALIDATION_SELECTION,
            GateStatus.NOT_EVALUATED,
            GateStatus.NOT_EVALUATED,
            GateStatus.NOT_EVALUATED,
            GateStatus.NOT_EVALUATED,
            null));
        return new EvolutionStudyPlan(
            SCHEMA,
            studyId,
            objective,
            splitManifest.contentHash(),
            seeds,
            mutations,
            populationPolicy,
            weights,
            budget,
            StudyStatus.NOT_STARTED,
            FinalTestPolicy.ONE_TIME_AFTER_FROZEN_VALIDATION_SELECTION,
            GateStatus.NOT_EVALUATED,
            GateStatus.NOT_EVALUATED,
            GateStatus.NOT_EVALUATED,
            GateStatus.NOT_EVALUATED,
            content);
    }

    public String toCanonicalJson() {
        return render(
            studyId,
            objective,
            splitManifestHash,
            seedGenomeHashes,
            mutationOperators,
            populationPolicy,
            fitnessWeights,
            budget,
            status,
            finalTestPolicy,
            proofStatus,
            externalNoveltyStatus,
            promotionStatus,
            publicEvidenceStatus,
            contentHash);
    }

    private static List<String> normalizeHashes(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        List<String> result = values.stream().peek(value -> requireHash(value, name))
            .sorted()
            .toList();
        if (new HashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException(name + " must be unique");
        }
        return List.copyOf(result);
    }

    private static List<EvolutionMutationKind> normalizeMutations(
        List<EvolutionMutationKind> values
    ) {
        Objects.requireNonNull(values, "mutationOperators");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("mutationOperators must not be empty");
        }
        List<EvolutionMutationKind> result = values.stream()
            .map(item -> Objects.requireNonNull(item, "mutation operator"))
            .distinct()
            .sorted(Comparator.comparing(Enum::name))
            .toList();
        if (result.size() != values.size()) {
            throw new IllegalArgumentException("mutationOperators must be unique");
        }
        return List.copyOf(result);
    }

    private static List<FitnessWeight> normalizeFitnessWeights(
        List<FitnessWeight> values
    ) {
        Objects.requireNonNull(values, "fitnessWeights");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("fitnessWeights must not be empty");
        }
        List<FitnessWeight> result = values.stream()
            .map(item -> Objects.requireNonNull(item, "fitness weight"))
            .sorted(Comparator.comparing(item -> item.component().name()))
            .toList();
        if (new HashSet<>(result.stream().map(FitnessWeight::component).toList()).size()
                != result.size()) {
            throw new IllegalArgumentException("fitness components must be unique");
        }
        int total = result.stream().mapToInt(FitnessWeight::weightPermille).sum();
        if (total != 1000) {
            throw new IllegalArgumentException("fitness weights must sum to 1000 permille");
        }
        return List.copyOf(result);
    }

    private static void requireNotEvaluated(GateStatus value, String name) {
        if (value != GateStatus.NOT_EVALUATED) {
            throw new IllegalArgumentException(name + " must remain NOT_EVALUATED");
        }
    }

    private static String render(
        String studyId,
        Objective objective,
        String splitManifestHash,
        List<String> seedGenomeHashes,
        List<EvolutionMutationKind> mutationOperators,
        PopulationPolicy populationPolicy,
        List<FitnessWeight> fitnessWeights,
        StudyBudget budget,
        StudyStatus status,
        FinalTestPolicy finalTestPolicy,
        GateStatus proofStatus,
        GateStatus externalNoveltyStatus,
        GateStatus promotionStatus,
        GateStatus publicEvidenceStatus,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("studyId", studyId)
            .property("objective", objective.name())
            .property("splitManifestHash", splitManifestHash)
            .stringArray("seedGenomeHashes", seedGenomeHashes)
            .stringArray("mutationOperators", mutationOperators.stream()
                .map(Enum::name)
                .toList())
            .object("populationPolicy", object -> object
                .property("populationSize", populationPolicy.populationSize())
                .property("generationCount", populationPolicy.generationCount())
                .property("eliteCount", populationPolicy.eliteCount())
                .property("minimumDistinctAlphaStructures",
                    populationPolicy.minimumDistinctAlphaStructures())
                .property("maxOffspringPerLineage",
                    populationPolicy.maxOffspringPerLineage())
                .property("parallelism", populationPolicy.parallelism())
                .property("randomSeed", populationPolicy.randomSeed()))
            .array("fitnessWeights", array -> fitnessWeights.forEach(weight ->
                array.objectValue(object -> object
                    .property("component", weight.component().name())
                    .property("weightPermille", weight.weightPermille()))))
            .object("budget", object -> object
                .property("maxMutationAttempts", budget.maxMutationAttempts())
                .property("maxTrainEvaluations", budget.maxTrainEvaluations())
                .property("maxValidationEvaluations", budget.maxValidationEvaluations())
                .property("maxFinalTestEvaluations", budget.maxFinalTestEvaluations())
                .property("maxCheckpoints", budget.maxCheckpoints()))
            .property("status", status.name())
            .property("finalTestPolicy", finalTestPolicy.name())
            .property("proofStatus", proofStatus.name())
            .property("externalNoveltyStatus", externalNoveltyStatus.name())
            .property("promotionStatus", promotionStatus.name())
            .property("publicEvidenceStatus", publicEvidenceStatus.name());
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private static void requireId(String value, String name) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has invalid identifier syntax");
        }
    }

    private static void requireHash(String value, String name) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }

    public record PopulationPolicy(
        int populationSize,
        int generationCount,
        int eliteCount,
        int minimumDistinctAlphaStructures,
        int maxOffspringPerLineage,
        int parallelism,
        long randomSeed
    ) {
        public PopulationPolicy {
            if (populationSize < 2 || generationCount < 1) {
                throw new IllegalArgumentException("population and generations must be positive");
            }
            if (eliteCount < 1 || eliteCount >= populationSize) {
                throw new IllegalArgumentException("eliteCount must be within population");
            }
            if (minimumDistinctAlphaStructures < 2
                    || minimumDistinctAlphaStructures > populationSize) {
                throw new IllegalArgumentException(
                    "minimum structural diversity must be within population");
            }
            if (maxOffspringPerLineage < 1 || parallelism < 1) {
                throw new IllegalArgumentException("offspring and parallelism must be positive");
            }
        }
    }

    public record FitnessWeight(FitnessComponent component, int weightPermille) {
        public FitnessWeight {
            Objects.requireNonNull(component, "component");
            if (weightPermille < 0 || weightPermille > 1000) {
                throw new IllegalArgumentException("weightPermille must be in [0,1000]");
            }
        }
    }

    public record StudyBudget(
        int maxMutationAttempts,
        int maxTrainEvaluations,
        int maxValidationEvaluations,
        int maxFinalTestEvaluations,
        int maxCheckpoints
    ) {
        public StudyBudget {
            if (maxMutationAttempts < 1
                    || maxTrainEvaluations < 1
                    || maxValidationEvaluations < 1
                    || maxFinalTestEvaluations < 1
                    || maxCheckpoints < 1) {
                throw new IllegalArgumentException("study budgets must be positive");
            }
        }
    }

    public enum FitnessComponent {
        TRAIN_CASES_NEWLY_SOLVED,
        TRAIN_PATH_LENGTH_REDUCTION,
        TRAIN_EXPLORED_STATE_REDUCTION,
        SUPPORT,
        STRUCTURAL_DIVERSITY,
        PROJECT_NOVELTY,
        ASSUMPTION_SIMPLICITY,
        CANDIDATE_COMPLEXITY,
        COUNTEREXAMPLE_RISK,
        PROOF_COST_PROXY
    }

    public enum StudyStatus {
        NOT_STARTED
    }

    public enum FinalTestPolicy {
        ONE_TIME_AFTER_FROZEN_VALIDATION_SELECTION
    }

    public enum GateStatus {
        NOT_EVALUATED
    }
}
