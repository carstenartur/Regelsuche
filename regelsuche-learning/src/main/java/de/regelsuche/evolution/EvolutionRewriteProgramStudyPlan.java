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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Preregistered TRAIN population plan for combined genome/program candidates. */
public record EvolutionRewriteProgramStudyPlan(
    String schema,
    String studyId,
    Objective objective,
    String splitManifestHash,
    String trainSuiteHash,
    String trainEvaluationProtocolHash,
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
            trainEvaluationProtocolHash, "trainEvaluationProtocolHash");
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
            trainEvaluationProtocolHash,
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
        EvolutionRewriteProgramEvaluationProtocol evaluationProtocol,
        MutationCatalog mutationCatalog,
        List<EvolutionRewriteProgramCandidate> seedCandidates,
        List<EvolutionRewriteProgramMutationKind> mutationOperators,
        PopulationPolicy populationPolicy,
        List<FitnessWeight> fitnessWeights,
        StudyBudget budget
    ) {
        Objects.requireNonNull(splitManifest, "splitManifest");
        Objects.requireNonNull(trainSuite, "trainSuite");
        Objects.requireNonNull(evaluationProtocol, "evaluationProtocol");
        Objects.requireNonNull(mutationCatalog, "mutationCatalog");
        Objects.requireNonNull(seedCandidates, "seedCandidates");
        requireId(studyId, "studyId");
        if (!studyId.equals(splitManifest.studyId())) {
            throw new IllegalArgumentException(
                "studyId differs from split-manifest studyId");
        }
        requireExactTrainSurface(splitManifest, trainSuite);
        requireMatchingEvaluatorProfile(trainSuite, evaluationProtocol);
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
        requireCatalogSourcesInEverySeed(mutationCatalog, seedCandidates);
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
            evaluationProtocol.contentHash(),
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
            evaluationProtocol.contentHash(),
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
            trainEvaluationProtocolHash,
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
        EvolutionRewriteProgramEvaluationProtocol evaluationProtocol,
        MutationCatalog mutationCatalog,
        List<EvolutionRewriteProgramCandidate> seeds
    ) {
        Objects.requireNonNull(splitManifest, "splitManifest");
        Objects.requireNonNull(trainSuite, "trainSuite");
        Objects.requireNonNull(evaluationProtocol, "evaluationProtocol");
        Objects.requireNonNull(mutationCatalog, "mutationCatalog");
        Objects.requireNonNull(seeds, "seeds");
        if (!studyId.equals(splitManifest.studyId())) {
            throw new IllegalArgumentException(
                "rewrite-program studyId differs from split manifest");
        }
        requireExactTrainSurface(splitManifest, trainSuite);
        requireMatchingEvaluatorProfile(trainSuite, evaluationProtocol);
        requireCatalogSourcesInEverySeed(mutationCatalog, seeds);
        if (!splitManifest.contentHash().equals(splitManifestHash)
                || !trainSuite.contentHash().equals(trainSuiteHash)
                || !evaluationProtocol.contentHash().equals(
                    trainEvaluationProtocolHash)
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

    private static void requireExactTrainSurface(
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite trainSuite
    ) {
        Map<String, String> manifestCases = new LinkedHashMap<>();
        splitManifest.trainCases().forEach(reference ->
            manifestCases.put(reference.caseId(), reference.familyId()));
        Map<String, String> suiteCases = new LinkedHashMap<>();
        trainSuite.cases().forEach(trainCase ->
            suiteCases.put(trainCase.caseId(), trainCase.familyId()));
        if (!manifestCases.equals(suiteCases)) {
            throw new IllegalArgumentException(
                "TRAIN suite case/family surface differs from split manifest");
        }
    }

    private static void requireMatchingEvaluatorProfile(
        EvolutionRewriteProgramTrainSuite trainSuite,
        EvolutionRewriteProgramEvaluationProtocol evaluationProtocol
    ) {
        if (trainSuite.evaluatorProfile()
                != evaluationProtocol.evaluatorProfile()) {
            throw new IllegalArgumentException(
                "TRAIN suite evaluator profile differs from evaluation protocol");
        }
    }

    private static void requireCatalogSourcesInEverySeed(
        MutationCatalog mutationCatalog,
        List<EvolutionRewriteProgramCandidate> seedCandidates
    ) {
        Set<String> referenced = new HashSet<>(mutationCatalog.sourceGeneIds());
        mutationCatalog.priorities().forEach(priority ->
            referenced.addAll(priority.preferredGeneIds()));
        for (EvolutionRewriteProgramCandidate candidate : seedCandidates) {
            Set<String> available = candidate.genome().rewrites().stream()
                .map(EvolutionGenome.RewriteGene::geneId)
                .collect(java.util.stream.Collectors.toSet());
            Set<String> missing = new HashSet<>(referenced);
            missing.removeAll(available);
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException(
                    "mutation catalog references genes absent from seed "
                        + candidate.contentHash() + ": " + missing.stream()
                            .sorted().toList());
            }
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
        String trainEvaluationProtocolHash,
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
            .property(
                "trainEvaluationProtocolHash",
                trainEvaluationProtocolHash)
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
