package de.regelsuche.evolution;

import de.regelsuche.evolution.DeterministicGenomeMutator.MutationCatalog;
import de.regelsuche.evolution.EvolutionPopulationEngine.CandidateEvaluation;
import de.regelsuche.evolution.EvolutionPopulationEngine.EvaluationStatus;
import de.regelsuche.evolution.EvolutionPopulationEngine.GenerationOutcome;
import de.regelsuche.evolution.EvolutionPopulationEngine.GenerationReport;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.json.JsonWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Complete generation-boundary state for deterministic TRAIN population resume.
 *
 * <p>The checkpoint deliberately carries the full evaluation cache. A genome
 * rejected from the selected population can be generated again in a later
 * generation; the uninterrupted engine would reuse its cached TRAIN result
 * rather than consume another evaluation budget entry.</p>
 */
public record EvolutionPopulationCheckpoint(
    String schema,
    String studyPlanHash,
    List<String> seedGenomeHashes,
    String mutationCatalogHash,
    String evaluatorConfigurationHash,
    int completedGenerations,
    List<GenerationReport> generationReports,
    List<EvolutionGenome> currentPopulation,
    List<CandidateEvaluation> evaluationCache,
    int mutationAttempts,
    int trainEvaluations,
    String validationStatus,
    String finalTestStatus,
    String proofStatus,
    String externalNoveltyStatus,
    String promotionStatus,
    String publicEvidenceStatus,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-population-checkpoint/v1";
    private static final String NOT_EVALUATED = "NOT_EVALUATED";

    public EvolutionPopulationCheckpoint {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported population-checkpoint schema");
        }
        EvolutionGenome.requireSha256(studyPlanHash, "studyPlanHash");
        seedGenomeHashes = canonicalHashes(seedGenomeHashes);
        EvolutionGenome.requireSha256(
            mutationCatalogHash, "mutationCatalogHash");
        EvolutionGenome.requireSha256(
            evaluatorConfigurationHash, "evaluatorConfigurationHash");
        generationReports = canonicalReports(generationReports);
        if (completedGenerations != generationReports.size()) {
            throw new IllegalArgumentException(
                "completedGenerations must equal generation report count");
        }
        currentPopulation = canonicalPopulation(currentPopulation);
        evaluationCache = canonicalEvaluations(evaluationCache);
        if (mutationAttempts < 0 || trainEvaluations < 0) {
            throw new IllegalArgumentException(
                "checkpoint budget counts must be non-negative");
        }
        requireNotEvaluated(validationStatus, "validationStatus");
        requireNotEvaluated(finalTestStatus, "finalTestStatus");
        requireNotEvaluated(proofStatus, "proofStatus");
        requireNotEvaluated(externalNoveltyStatus, "externalNoveltyStatus");
        requireNotEvaluated(promotionStatus, "promotionStatus");
        requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");

        List<String> populationHashes = currentPopulation.stream()
            .map(EvolutionGenome::contentHash)
            .toList();
        if (completedGenerations == 0) {
            if (!populationHashes.equals(seedGenomeHashes)) {
                throw new IllegalArgumentException(
                    "generation-zero checkpoint population must equal seeds");
            }
            if (mutationAttempts != 0 || trainEvaluations != 0
                    || !evaluationCache.isEmpty()) {
                throw new IllegalArgumentException(
                    "generation-zero checkpoint must have empty execution state");
            }
        } else {
            GenerationReport last = generationReports.getLast();
            if (last.outcome() != GenerationOutcome.CONTINUE) {
                throw new IllegalArgumentException(
                    "checkpoint requires a CONTINUE generation boundary");
            }
            if (!last.selectedGenomeHashes().equals(populationHashes)) {
                throw new IllegalArgumentException(
                    "checkpoint population differs from last selection");
            }
            if (last.cumulativeMutationAttempts() != mutationAttempts
                    || last.cumulativeTrainEvaluations() != trainEvaluations) {
                throw new IllegalArgumentException(
                    "checkpoint budgets differ from last generation report");
            }
        }

        Map<String, CandidateEvaluation> byGenome = new LinkedHashMap<>();
        for (CandidateEvaluation evaluation : evaluationCache) {
            if (byGenome.put(evaluation.genomeHash(), evaluation) != null) {
                throw new IllegalArgumentException(
                    "evaluation cache genome hashes must be unique");
            }
        }
        for (EvolutionGenome genome : currentPopulation) {
            CandidateEvaluation evaluation = byGenome.get(genome.contentHash());
            if (evaluation == null) {
                throw new IllegalArgumentException(
                    "current population is missing cached evaluation");
            }
            if (!evaluation.alphaStructuralHash().equals(
                    genome.alphaStructuralHash())) {
                throw new IllegalArgumentException(
                    "cached evaluation structural identity mismatch");
            }
        }

        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            studyPlanHash,
            seedGenomeHashes,
            mutationCatalogHash,
            evaluatorConfigurationHash,
            completedGenerations,
            generationReports,
            currentPopulation,
            evaluationCache,
            mutationAttempts,
            trainEvaluations,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "population checkpoint contentHash mismatch");
        }
    }

    static EvolutionPopulationCheckpoint create(
        EvolutionStudyPlan plan,
        List<EvolutionGenome> seeds,
        MutationCatalog catalog,
        String evaluatorConfigurationHash,
        List<GenerationReport> reports,
        List<EvolutionGenome> population,
        Map<String, CandidateEvaluation> evaluations,
        int mutationAttempts,
        int trainEvaluations
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(seeds, "seeds");
        Objects.requireNonNull(catalog, "catalog");
        EvolutionGenome.requireSha256(
            evaluatorConfigurationHash, "evaluatorConfigurationHash");
        List<String> seedHashes = canonicalHashes(seeds.stream()
            .map(EvolutionGenome::contentHash)
            .toList());
        List<GenerationReport> canonicalReports = canonicalReports(reports);
        List<EvolutionGenome> canonicalPopulation =
            canonicalPopulation(population);
        List<CandidateEvaluation> canonicalEvaluations =
            canonicalEvaluations(new ArrayList<>(evaluations.values()));
        String catalogHash = mutationCatalogHash(catalog);
        String hash = EvolutionGenome.hash(render(
            plan.contentHash(),
            seedHashes,
            catalogHash,
            evaluatorConfigurationHash,
            canonicalReports.size(),
            canonicalReports,
            canonicalPopulation,
            canonicalEvaluations,
            mutationAttempts,
            trainEvaluations,
            null));
        return new EvolutionPopulationCheckpoint(
            SCHEMA,
            plan.contentHash(),
            seedHashes,
            catalogHash,
            evaluatorConfigurationHash,
            canonicalReports.size(),
            canonicalReports,
            canonicalPopulation,
            canonicalEvaluations,
            mutationAttempts,
            trainEvaluations,
            NOT_EVALUATED,
            NOT_EVALUATED,
            NOT_EVALUATED,
            NOT_EVALUATED,
            NOT_EVALUATED,
            NOT_EVALUATED,
            hash);
    }

    public String toCanonicalJson() {
        return render(
            studyPlanHash,
            seedGenomeHashes,
            mutationCatalogHash,
            evaluatorConfigurationHash,
            completedGenerations,
            generationReports,
            currentPopulation,
            evaluationCache,
            mutationAttempts,
            trainEvaluations,
            contentHash);
    }

    public static String mutationCatalogHash(MutationCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", "regelsuche.evolution-mutation-catalog/v1")
            .stringArray("assumptions", catalog.assumptions().stream()
                .map(EvolutionGenome.AssumptionTemplate::canonicalMaterial)
                .toList())
            .array("rankingFeatures", array ->
                catalog.rankingFeatures().forEach(feature ->
                    array.objectValue(object -> object
                        .property("signal", feature.signal().name())
                        .property("weightPermille", feature.weightPermille()))))
            .array("specializationConstants", array ->
                catalog.specializationConstants().forEach(array::value));
        return EvolutionGenome.hash(json.endObject().toString());
    }

    private static String render(
        String planHash,
        List<String> seedHashes,
        String catalogHash,
        String evaluatorHash,
        int completed,
        List<GenerationReport> reports,
        List<EvolutionGenome> population,
        List<CandidateEvaluation> evaluations,
        int mutationAttempts,
        int trainEvaluations,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("studyPlanHash", planHash)
            .stringArray("seedGenomeHashes", seedHashes)
            .property("mutationCatalogHash", catalogHash)
            .property("evaluatorConfigurationHash", evaluatorHash)
            .property("completedGenerations", completed)
            .stringArray("generationReportDocuments", reports.stream()
                .map(GenerationReport::toCanonicalJson)
                .toList())
            .stringArray("currentPopulationDocuments", population.stream()
                .map(EvolutionGenome::toCanonicalJson)
                .toList())
            .array("evaluationCache", array -> evaluations.forEach(evaluation ->
                array.objectValue(object -> writeEvaluation(object, evaluation))))
            .object("budgetUsed", object -> object
                .property("mutationAttempts", mutationAttempts)
                .property("trainEvaluations", trainEvaluations))
            .property("validationStatus", NOT_EVALUATED)
            .property("finalTestStatus", NOT_EVALUATED)
            .property("proofStatus", NOT_EVALUATED)
            .property("externalNoveltyStatus", NOT_EVALUATED)
            .property("promotionStatus", NOT_EVALUATED)
            .property("publicEvidenceStatus", NOT_EVALUATED);
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private static void writeEvaluation(
        JsonWriter json,
        CandidateEvaluation evaluation
    ) {
        json.property("genomeHash", evaluation.genomeHash())
            .property("alphaStructuralHash", evaluation.alphaStructuralHash())
            .object("rawComponents", object -> {
                for (FitnessComponent component : FitnessComponent.values()) {
                    Integer value = evaluation.rawComponents().get(component);
                    if (value != null) {
                        object.property(component.name(), value);
                    }
                }
            })
            .property("weightedScorePermille",
                evaluation.weightedScorePermille())
            .property("status", evaluation.status().name())
            .stringArray("blockers", evaluation.blockers());
    }

    private static List<String> canonicalHashes(List<String> values) {
        Objects.requireNonNull(values, "seedGenomeHashes");
        List<String> result = values.stream()
            .map(value -> {
                EvolutionGenome.requireSha256(value, "seedGenomeHash");
                return value;
            })
            .sorted()
            .toList();
        if (result.isEmpty() || new HashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException(
                "seedGenomeHashes must be non-empty and unique");
        }
        return result;
    }

    private static List<GenerationReport> canonicalReports(
        List<GenerationReport> values
    ) {
        List<GenerationReport> result = values == null
            ? List.of()
            : values.stream()
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
        List<EvolutionGenome> result = values == null
            ? List.of()
            : values.stream()
                .map(value -> Objects.requireNonNull(value, "population genome"))
                .toList();
        if (result.stream().map(EvolutionGenome::contentHash).distinct().count()
                != result.size()
                || result.stream().map(EvolutionGenome::alphaStructuralHash)
                    .distinct().count() != result.size()) {
            throw new IllegalArgumentException(
                "checkpoint population must be content- and alpha-unique");
        }
        return result;
    }

    private static List<CandidateEvaluation> canonicalEvaluations(
        List<CandidateEvaluation> values
    ) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .map(value -> Objects.requireNonNull(value, "cached evaluation"))
            .sorted(Comparator.comparing(CandidateEvaluation::genomeHash))
            .toList();
    }

    private static void requireNotEvaluated(String value, String name) {
        if (!NOT_EVALUATED.equals(value)) {
            throw new IllegalArgumentException(name + " must be NOT_EVALUATED");
        }
    }
}
