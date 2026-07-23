package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.evolution.DeterministicGenomeMutator.MutationCatalog;
import de.regelsuche.evolution.EvolutionPopulationEngine.CandidateEvaluation;
import de.regelsuche.evolution.EvolutionPopulationEngine.GenerationReport;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Canonical TRAIN-only checkpoint for deterministic population resume. */
public record EvolutionPopulationCheckpoint(
    String schema,
    String studyPlanHash,
    String mutationCatalogHash,
    List<String> seedGenomeHashes,
    int completedGeneration,
    List<EvolutionGenome> population,
    List<CandidateEvaluation> evaluations,
    List<GenerationReport> generationReports,
    int mutationAttempts,
    int trainEvaluations,
    String validationStatus,
    String finalTestStatus,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-population-checkpoint/v1";
    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
        .findAndRegisterModules()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public EvolutionPopulationCheckpoint {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported checkpoint schema");
        }
        EvolutionGenome.requireSha256(studyPlanHash, "studyPlanHash");
        EvolutionGenome.requireSha256(
            mutationCatalogHash, "mutationCatalogHash");
        seedGenomeHashes = canonicalHashes(seedGenomeHashes);
        if (completedGeneration < 1) {
            throw new IllegalArgumentException(
                "completedGeneration must be positive");
        }
        population = canonicalPopulation(population);
        evaluations = canonicalEvaluations(evaluations);
        generationReports = canonicalReports(generationReports);
        if (generationReports.size() != completedGeneration) {
            throw new IllegalArgumentException(
                "generation report count must equal completedGeneration");
        }
        for (int index = 0; index < generationReports.size(); index++) {
            if (generationReports.get(index).generation() != index + 1) {
                throw new IllegalArgumentException(
                    "checkpoint generation sequence is not contiguous");
            }
        }
        if (mutationAttempts < 0 || trainEvaluations < 0) {
            throw new IllegalArgumentException(
                "checkpoint budgets must be non-negative");
        }
        GenerationReport last = generationReports.getLast();
        if (last.cumulativeMutationAttempts() != mutationAttempts
                || last.cumulativeTrainEvaluations() != trainEvaluations) {
            throw new IllegalArgumentException(
                "checkpoint budget totals differ from final retained generation");
        }
        List<String> selected = last.selectedGenomeHashes().stream()
            .sorted().toList();
        List<String> retained = population.stream()
            .map(EvolutionGenome::contentHash).sorted().toList();
        if (!selected.equals(retained)) {
            throw new IllegalArgumentException(
                "checkpoint population differs from selected generation");
        }
        Map<String, CandidateEvaluation> byHash = evaluations.stream()
            .collect(java.util.stream.Collectors.toMap(
                CandidateEvaluation::genomeHash,
                item -> item,
                (left, right) -> {
                    if (!left.equals(right)) {
                        throw new IllegalArgumentException(
                            "conflicting cached evaluation for "
                                + left.genomeHash());
                    }
                    return left;
                },
                TreeMap::new));
        for (EvolutionGenome genome : population) {
            CandidateEvaluation evaluation = byHash.get(genome.contentHash());
            if (evaluation == null
                    || !evaluation.alphaStructuralHash().equals(
                        genome.alphaStructuralHash())) {
                throw new IllegalArgumentException(
                    "selected genome lacks its exact cached TRAIN evaluation");
            }
        }
        if (!"NOT_EVALUATED".equals(validationStatus)
                || !"NOT_EVALUATED".equals(finalTestStatus)) {
            throw new IllegalArgumentException(
                "checkpoint must not contain VALIDATION or FINAL TEST results");
        }
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = hash(payload(
            studyPlanHash,
            mutationCatalogHash,
            seedGenomeHashes,
            completedGeneration,
            population,
            evaluations,
            generationReports,
            mutationAttempts,
            trainEvaluations,
            validationStatus,
            finalTestStatus));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "checkpoint contentHash mismatch");
        }
    }

    static EvolutionPopulationCheckpoint create(
        EvolutionStudyPlan plan,
        MutationCatalog catalog,
        List<EvolutionGenome> seeds,
        int completedGeneration,
        List<EvolutionGenome> population,
        Collection<CandidateEvaluation> evaluations,
        List<GenerationReport> reports,
        int mutationAttempts,
        int trainEvaluations
    ) {
        Objects.requireNonNull(plan, "plan");
        List<String> seedHashes = canonicalHashes(seeds.stream()
            .map(EvolutionGenome::contentHash).toList());
        List<EvolutionGenome> retainedPopulation =
            canonicalPopulation(population);
        List<CandidateEvaluation> retainedEvaluations =
            canonicalEvaluations(evaluations == null
                ? List.of()
                : List.copyOf(evaluations));
        List<GenerationReport> retainedReports = canonicalReports(reports);
        String catalogHash = mutationCatalogHash(catalog);
        String contentHash = hash(payload(
            plan.contentHash(),
            catalogHash,
            seedHashes,
            completedGeneration,
            retainedPopulation,
            retainedEvaluations,
            retainedReports,
            mutationAttempts,
            trainEvaluations,
            "NOT_EVALUATED",
            "NOT_EVALUATED"));
        return new EvolutionPopulationCheckpoint(
            SCHEMA,
            plan.contentHash(),
            catalogHash,
            seedHashes,
            completedGeneration,
            retainedPopulation,
            retainedEvaluations,
            retainedReports,
            mutationAttempts,
            trainEvaluations,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    public static EvolutionPopulationCheckpoint fromCanonicalJson(
        String json
    ) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(
                "checkpoint JSON must not be blank");
        }
        try {
            return JSON.readValue(json, EvolutionPopulationCheckpoint.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "invalid population checkpoint JSON", exception);
        }
    }

    public String toCanonicalJson() {
        try {
            Map<String, Object> value = payload(
                studyPlanHash,
                mutationCatalogHash,
                seedGenomeHashes,
                completedGeneration,
                population,
                evaluations,
                generationReports,
                mutationAttempts,
                trainEvaluations,
                validationStatus,
                finalTestStatus);
            value.put("schema", SCHEMA);
            value.put("contentHash", contentHash);
            return JSON.writeValueAsString(value) + "\n";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "cannot serialize population checkpoint", exception);
        }
    }

    public int nextGeneration() {
        return completedGeneration + 1;
    }

    Map<String, CandidateEvaluation> evaluationsByGenomeHash() {
        Map<String, CandidateEvaluation> result = new LinkedHashMap<>();
        evaluations.forEach(item -> result.put(item.genomeHash(), item));
        return Map.copyOf(result);
    }

    void requireCompatible(
        EvolutionStudyPlan plan,
        MutationCatalog catalog,
        List<EvolutionGenome> seeds
    ) {
        Objects.requireNonNull(plan, "plan");
        if (!studyPlanHash.equals(plan.contentHash())) {
            throw new IllegalArgumentException(
                "checkpoint study-plan identity mismatch");
        }
        if (!mutationCatalogHash.equals(mutationCatalogHash(catalog))) {
            throw new IllegalArgumentException(
                "checkpoint mutation-catalog identity mismatch");
        }
        List<String> actualSeeds = canonicalHashes(seeds.stream()
            .map(EvolutionGenome::contentHash).toList());
        if (!seedGenomeHashes.equals(actualSeeds)) {
            throw new IllegalArgumentException(
                "checkpoint seed identity mismatch");
        }
    }

    static String mutationCatalogHash(MutationCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        Map<String, Object> value = new TreeMap<>();
        value.put("schema", "regelsuche.evolution-mutation-catalog/v1");
        value.put("assumptions", catalog.assumptions().stream()
            .map(EvolutionGenome.AssumptionTemplate::canonicalMaterial)
            .toList());
        value.put("rankingFeatures", catalog.rankingFeatures().stream()
            .map(item -> Map.of(
                "signal", item.signal().name(),
                "weightPermille", item.weightPermille()))
            .toList());
        value.put(
            "specializationConstants", catalog.specializationConstants());
        return hash(value);
    }

    private static Map<String, Object> payload(
        String studyPlanHash,
        String mutationCatalogHash,
        List<String> seedGenomeHashes,
        int completedGeneration,
        List<EvolutionGenome> population,
        List<CandidateEvaluation> evaluations,
        List<GenerationReport> generationReports,
        int mutationAttempts,
        int trainEvaluations,
        String validationStatus,
        String finalTestStatus
    ) {
        Map<String, Object> value = new TreeMap<>();
        value.put("completedGeneration", completedGeneration);
        value.put("evaluations", evaluations);
        value.put("finalTestStatus", finalTestStatus);
        value.put("generationReports", generationReports);
        value.put("mutationAttempts", mutationAttempts);
        value.put("mutationCatalogHash", mutationCatalogHash);
        value.put("population", population);
        value.put("seedGenomeHashes", seedGenomeHashes);
        value.put("studyPlanHash", studyPlanHash);
        value.put("trainEvaluations", trainEvaluations);
        value.put("validationStatus", validationStatus);
        return value;
    }

    private static String hash(Map<String, Object> value) {
        try {
            return EvolutionGenome.hash(
                JSON.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "cannot hash population checkpoint", exception);
        }
    }

    private static List<String> canonicalHashes(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(
                "checkpoint requires seed genome hashes");
        }
        List<String> result = values.stream()
            .map(value -> {
                EvolutionGenome.requireSha256(value, "seedGenomeHash");
                return value;
            })
            .distinct().sorted().toList();
        if (result.size() != values.size()) {
            throw new IllegalArgumentException(
                "checkpoint contains duplicate seed genomes");
        }
        return result;
    }

    private static List<EvolutionGenome> canonicalPopulation(
        List<EvolutionGenome> values
    ) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(
                "checkpoint population must not be empty");
        }
        List<EvolutionGenome> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "population genome"))
            .toList();
        if (result.stream().map(EvolutionGenome::contentHash)
                .distinct().count() != result.size()
                || result.stream().map(EvolutionGenome::alphaStructuralHash)
                    .distinct().count() != result.size()) {
            throw new IllegalArgumentException(
                "checkpoint population is not content/structure unique");
        }
        return result;
    }

    private static List<CandidateEvaluation> canonicalEvaluations(
        List<CandidateEvaluation> values
    ) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(
                "checkpoint evaluation cache must not be empty");
        }
        List<CandidateEvaluation> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "cached evaluation"))
            .sorted(Comparator.comparing(CandidateEvaluation::genomeHash))
            .toList();
        if (result.stream().map(CandidateEvaluation::genomeHash)
                .distinct().count() != result.size()) {
            throw new IllegalArgumentException(
                "checkpoint contains duplicate cached evaluations");
        }
        return result;
    }

    private static List<GenerationReport> canonicalReports(
        List<GenerationReport> values
    ) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(
                "checkpoint requires generation reports");
        }
        return values.stream()
            .map(value -> Objects.requireNonNull(value, "generation report"))
            .sorted(Comparator.comparingInt(GenerationReport::generation))
            .toList();
    }
}
