package de.regelsuche.evolution;

import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationCatalog;
import de.regelsuche.evolution.EvolutionGenome.Objective;
import de.regelsuche.evolution.EvolutionStudyPlan.FinalTestPolicy;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessWeight;
import de.regelsuche.evolution.EvolutionStudyPlan.GateStatus;
import de.regelsuche.evolution.EvolutionStudyPlan.PopulationPolicy;
import de.regelsuche.evolution.EvolutionStudyPlan.StudyBudget;
import de.regelsuche.evolution.EvolutionStudyPlan.StudyStatus;
import de.regelsuche.json.JsonWriter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Preregistered TRAIN population plan for combined genome/program candidates. */
public record EvolutionRewriteProgramStudyPlan(
    String schema,
    String studyId,
    Objective objective,
    String splitManifestHash,
    String trainSuiteHash,
    String mutationCatalogHash,
    List<String> seedCandidateHashes,
    List<EvolutionRewriteProgramMutationKind> mutationOperators,
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
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-study-plan/v1";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{2,127}");

    public EvolutionRewriteProgramStudyPlan {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported rewrite-program study-plan schema");
        }
        requireId(studyId, "studyId");
        Objects.requireNonNull(objective, "objective");
        EvolutionGenome.requireSha256(splitManifestHash, "splitManifestHash");
        EvolutionGenome.requireSha256(trainSuiteHash, "trainSuiteHash");
        EvolutionGenome.requireSha256(
            mutationCatalogHash, "mutationCatalogHash");
        seedCandidateHashes = canonicalHashes(
            seedCandidateHashes, "seedCandidateHashes");
        mutationOperators = canonicalMutations(mutationOperators);
        Objects.requireNonNull(populationPolicy, "populationPolicy");
        fitnessWeights = canonicalWeights(fitnessWeights);
        Objects.requireNonNull(budget, "budget");
        if (status != StudyStatus.NOT_STARTED
                || finalTestPolicy
                    != FinalTestPolicy.ONE_TIME_AFTER_FROZEN_VALIDATION_SELECTION
                || proofStatus != GateStatus.NOT_EVALUATED
                || externalNoveltyStatus != GateStatus.NOT_EVALUATED
                || promotionStatus != GateStatus.NOT_EVALUATED
                || publicEvidenceStatus != GateStatus.NOT_EVALUATED) {
            throw new IllegalArgumentException(
                "rewrite-program study must remain pre-execution and fail closed");
        }
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            studyId,
            objective,
            splitManifestHash,
            trainSuiteHash,
            mutationCatalogHash,
            seedCandidateHashes,
            mutationOperators,
            populationPolicy,
            fitnessWeights,
            budget,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "rewrite-program study-plan contentHash mismatch");
        }
    }

    public static EvolutionRewriteProgramStudyPlan create(
        String studyId,
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite trainSuite,
        MutationCatalog mutationCatalog,
        List<EvolutionRewriteProgramCandidate> seedCandidates,
        List<EvolutionRewriteProgramMutationKind> mutationOperators,
        PopulationPolicy populationPolicy,
        List<FitnessWeight> fitnessWeights,
        StudyBudget budget
    ) {
        Objects.requireNonNull(splitManifest, "splitManifest");
        Objects.requireNonNull(trainSuite, "trainSuite");
        Objects.requireNonNull(mutationCatalog, "mutationCatalog");
        Objects.requireNonNull(seedCandidates, "seedCandidates");
        if (seedCandidates.isEmpty()) {
            throw new IllegalArgumentException(
                "seedCandidates must not be empty");
        }
        Objective objective = seedCandidates.getFirst().genome().objective();
        for (EvolutionRewriteProgramCandidate candidate : seedCandidates) {
            Objects.requireNonNull(candidate, "seed candidate");
            if (candidate.genome().objective() != objective) {
                throw new IllegalArgumentException(
                    "seed candidate objectives differ");
            }
            if (!candidate.genome().trainingScope().equals(
                    splitManifest.trainingScope())) {
                throw new IllegalArgumentException(
                    "seed candidate is outside split-manifest TRAIN scope");
            }
        }
        List<String> seedHashes = canonicalHashes(
            seedCandidates.stream()
                .map(EvolutionRewriteProgramCandidate::contentHash)
                .toList(),
            "seedCandidateHashes");
        List<EvolutionRewriteProgramMutationKind> mutations =
            canonicalMutations(mutationOperators);
        List<FitnessWeight> weights = canonicalWeights(fitnessWeights);
        String hash = EvolutionGenome.hash(render(
            studyId,
            objective,
            splitManifest.contentHash(),
            trainSuite.contentHash(),
            mutationCatalog.contentHash(),
            seedHashes,
            mutations,
            populationPolicy,
            weights,
            budget,
            null));
        return new EvolutionRewriteProgramStudyPlan(
            SCHEMA,
            studyId,
            objective,
            splitManifest.contentHash(),
            trainSuite.contentHash(),
            mutationCatalog.contentHash(),
            seedHashes,
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
            hash);
    }

    public String toCanonicalJson() {
        return render(
            studyId,
            objective,
            splitManifestHash,
            trainSuiteHash,
            mutationCatalogHash,
            seedCandidateHashes,
            mutationOperators,
            populationPolicy,
            fitnessWeights,
            budget,
            contentHash);
    }

    public void requireInputs(
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite trainSuite,
        MutationCatalog mutationCatalog,
        List<EvolutionRewriteProgramCandidate> seeds
    ) {
        Objects.requireNonNull(splitManifest, "splitManifest");
        Objects.requireNonNull(trainSuite, "trainSuite");
        Objects.requireNonNull(mutationCatalog, "mutationCatalog");
        if (!splitManifest.contentHash().equals(splitManifestHash)
                || !trainSuite.contentHash().equals(trainSuiteHash)
                || !mutationCatalog.contentHash().equals(mutationCatalogHash)) {
            throw new IllegalArgumentException(
                "rewrite-program study input identity mismatch");
        }
        List<String> actualSeeds = canonicalHashes(
            seeds.stream()
                .map(EvolutionRewriteProgramCandidate::contentHash)
                .toList(),
            "seedCandidateHashes");
        if (!seedCandidateHashes.equals(actualSeeds)) {
            throw new IllegalArgumentException(
                "rewrite-program study seed candidates differ");
        }
    }

    private static List<String> canonicalHashes(
        List<String> values,
        String name
    ) {
        Objects.requireNonNull(values, name);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        List<String> result = values.stream()
            .peek(value -> EvolutionGenome.requireSha256(value, name))
            .sorted()
            .toList();
        if (new HashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException(name + " must be unique");
        }
        return result;
    }

    private static List<EvolutionRewriteProgramMutationKind> canonicalMutations(
        List<EvolutionRewriteProgramMutationKind> values
    ) {
        Objects.requireNonNull(values, "mutationOperators");
        if (values.isEmpty()) {
            throw new IllegalArgumentException(
                "mutationOperators must not be empty");
        }
        List<EvolutionRewriteProgramMutationKind> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "mutation operator"))
            .distinct()
            .sorted(Comparator.comparing(Enum::name))
            .toList();
        if (result.size() != values.size()) {
            throw new IllegalArgumentException(
                "mutationOperators must be unique");
        }
        return result;
    }

    private static List<FitnessWeight> canonicalWeights(
        List<FitnessWeight> values
    ) {
        Objects.requireNonNull(values, "fitnessWeights");
        if (values.isEmpty()) {
            throw new IllegalArgumentException(
                "fitnessWeights must not be empty");
        }
        List<FitnessWeight> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "fitness weight"))
            .sorted(Comparator.comparing(value -> value.component().name()))
            .toList();
        if (new HashSet<>(result.stream()
                .map(FitnessWeight::component).toList()).size() != result.size()) {
            throw new IllegalArgumentException(
                "fitness components must be unique");
        }
        if (result.stream().mapToInt(FitnessWeight::weightPermille).sum() != 1000) {
            throw new IllegalArgumentException(
                "fitness weights must sum to 1000 permille");
        }
        return result;
    }

    private static String render(
        String studyId,
        Objective objective,
        String splitManifestHash,
        String trainSuiteHash,
        String mutationCatalogHash,
        List<String> seedCandidateHashes,
        List<EvolutionRewriteProgramMutationKind> mutationOperators,
        PopulationPolicy populationPolicy,
        List<FitnessWeight> fitnessWeights,
        StudyBudget budget,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("studyId", studyId)
            .property("objective", objective.name())
            .property("splitManifestHash", splitManifestHash)
            .property("trainSuiteHash", trainSuiteHash)
            .property("mutationCatalogHash", mutationCatalogHash)
            .stringArray("seedCandidateHashes", seedCandidateHashes)
            .stringArray("mutationOperators", mutationOperators.stream()
                .map(Enum::name).toList())
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
                .property("maxValidationEvaluations",
                    budget.maxValidationEvaluations())
                .property("maxFinalTestEvaluations",
                    budget.maxFinalTestEvaluations())
                .property("maxCheckpoints", budget.maxCheckpoints()))
            .property("status", StudyStatus.NOT_STARTED.name())
            .property("finalTestPolicy",
                FinalTestPolicy.ONE_TIME_AFTER_FROZEN_VALIDATION_SELECTION.name())
            .property("proofStatus", GateStatus.NOT_EVALUATED.name())
            .property("externalNoveltyStatus", GateStatus.NOT_EVALUATED.name())
            .property("promotionStatus", GateStatus.NOT_EVALUATED.name())
            .property("publicEvidenceStatus", GateStatus.NOT_EVALUATED.name());
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private static void requireId(String value, String name) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has invalid syntax");
        }
    }
}
