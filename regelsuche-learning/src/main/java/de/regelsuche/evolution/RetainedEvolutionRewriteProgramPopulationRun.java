package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.CandidateEvaluation;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.PopulationRun;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.json.JsonWriter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Self-contained terminal TRAIN population state.
 *
 * <p>{@link PopulationRun} intentionally remains a compact result ledger. This
 * artifact retains the complete immutable genome/plan payload for every final
 * candidate and the corresponding final-generation evaluation. It is therefore
 * sufficient for later candidate freezing without consulting transient engine
 * objects or any VALIDATION/FINAL TEST data.</p>
 */
public record RetainedEvolutionRewriteProgramPopulationRun(
    String schema,
    PopulationRun populationRun,
    List<RetainedCandidate> finalCandidates,
    List<CandidateEvaluation> finalEvaluations,
    String validationStatus,
    String finalTestStatus,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-retained-population-run/v1";
    private static final EvolutionGenomeCodec GENOME_CODEC =
        new EvolutionGenomeCodec();
    private static final EvolutionRewriteProgramPlanCodec PLAN_CODEC =
        new EvolutionRewriteProgramPlanCodec();

    public RetainedEvolutionRewriteProgramPopulationRun {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported retained rewrite-program population schema");
        }
        Objects.requireNonNull(populationRun, "populationRun");
        finalCandidates = canonicalCandidates(finalCandidates);
        finalEvaluations = canonicalEvaluations(finalEvaluations);
        if (!"NOT_EVALUATED".equals(validationStatus)
                || !"NOT_EVALUATED".equals(finalTestStatus)) {
            throw new IllegalArgumentException(
                "retained TRAIN population cannot contain later-stage outcomes");
        }
        requireBindings(populationRun, finalCandidates, finalEvaluations);
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            populationRun,
            finalCandidates,
            finalEvaluations,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "retained population contentHash mismatch");
        }
    }

    public static RetainedEvolutionRewriteProgramPopulationRun create(
        PopulationRun populationRun,
        List<EvolutionRewriteProgramCandidate> finalCandidates
    ) {
        Objects.requireNonNull(populationRun, "populationRun");
        List<RetainedCandidate> retained = finalCandidates == null
            ? List.of()
            : finalCandidates.stream()
                .map(RetainedCandidate::from)
                .sorted(Comparator.comparing(
                    RetainedCandidate::candidateHash))
                .toList();
        Map<String, CandidateEvaluation> finalEvaluationByCandidate =
            new HashMap<>();
        populationRun.generationReports().getLast().evaluations()
            .forEach(evaluation ->
                finalEvaluationByCandidate.put(
                    evaluation.candidateHash(), evaluation));
        List<CandidateEvaluation> evaluations =
            populationRun.finalCandidateHashes().stream()
                .map(hash -> {
                    CandidateEvaluation evaluation =
                        finalEvaluationByCandidate.get(hash);
                    if (evaluation == null) {
                        throw new IllegalArgumentException(
                            "final population candidate lacks final evaluation: "
                                + hash);
                    }
                    return evaluation;
                })
                .sorted(Comparator.comparing(
                    CandidateEvaluation::candidateHash))
                .toList();
        String hash = EvolutionGenome.hash(render(
            populationRun,
            retained,
            evaluations,
            null));
        return new RetainedEvolutionRewriteProgramPopulationRun(
            SCHEMA,
            populationRun,
            retained,
            evaluations,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            hash);
    }

    public List<EvolutionRewriteProgramCandidate> candidates() {
        return finalCandidates.stream()
            .map(RetainedCandidate::candidate)
            .toList();
    }

    public String toCanonicalJson() {
        return render(
            populationRun,
            finalCandidates,
            finalEvaluations,
            contentHash);
    }

    private static void requireBindings(
        PopulationRun run,
        List<RetainedCandidate> candidates,
        List<CandidateEvaluation> evaluations
    ) {
        List<String> expectedCandidates = run.finalCandidateHashes();
        List<String> actualCandidates = candidates.stream()
            .map(RetainedCandidate::candidateHash)
            .toList();
        if (!expectedCandidates.equals(actualCandidates)) {
            throw new IllegalArgumentException(
                "retained candidates differ from population-run final roots");
        }
        List<String> expectedSelected = run.generationReports().getLast()
            .selectedCandidateHashes();
        if (!expectedCandidates.equals(expectedSelected)) {
            throw new IllegalArgumentException(
                "population run final roots differ from final generation");
        }
        List<String> actualEvaluations = evaluations.stream()
            .map(CandidateEvaluation::candidateHash)
            .toList();
        if (!expectedCandidates.equals(actualEvaluations)) {
            throw new IllegalArgumentException(
                "retained final evaluations differ from final candidates");
        }
        Map<String, RetainedCandidate> byHash = new HashMap<>();
        candidates.forEach(candidate ->
            byHash.put(candidate.candidateHash(), candidate));
        for (CandidateEvaluation evaluation : evaluations) {
            RetainedCandidate candidate = byHash.get(
                evaluation.candidateHash());
            if (candidate == null
                    || !candidate.alphaStructuralHash().equals(
                        evaluation.alphaStructuralHash())) {
                throw new IllegalArgumentException(
                    "retained evaluation identity differs from candidate");
            }
        }
    }

    private static List<RetainedCandidate> canonicalCandidates(
        List<RetainedCandidate> values
    ) {
        List<RetainedCandidate> result = values == null
            ? List.of()
            : values.stream()
                .map(value -> Objects.requireNonNull(
                    value, "retained candidate"))
                .sorted(Comparator.comparing(
                    RetainedCandidate::candidateHash))
                .toList();
        if (new HashSet<>(result.stream()
                .map(RetainedCandidate::candidateHash).toList()).size()
                != result.size()) {
            throw new IllegalArgumentException(
                "retained candidates require unique identities");
        }
        return List.copyOf(result);
    }

    private static List<CandidateEvaluation> canonicalEvaluations(
        List<CandidateEvaluation> values
    ) {
        List<CandidateEvaluation> result = values == null
            ? List.of()
            : values.stream()
                .map(value -> Objects.requireNonNull(
                    value, "candidate evaluation"))
                .sorted(Comparator.comparing(
                    CandidateEvaluation::candidateHash))
                .toList();
        if (new HashSet<>(result.stream()
                .map(CandidateEvaluation::candidateHash).toList()).size()
                != result.size()) {
            throw new IllegalArgumentException(
                "retained evaluations require unique candidates");
        }
        return List.copyOf(result);
    }

    private static String render(
        PopulationRun run,
        List<RetainedCandidate> candidates,
        List<CandidateEvaluation> evaluations,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("populationRunHash", run.contentHash())
            .property("populationRunJson", run.toCanonicalJson())
            .array("finalCandidates", array -> candidates.forEach(candidate ->
                array.objectValue(object -> object
                    .property("candidateHash", candidate.candidateHash())
                    .property(
                        "alphaStructuralHash",
                        candidate.alphaStructuralHash())
                    .property("genomeJson", candidate.genomeJson())
                    .property("planJson", candidate.planJson())
                    .property(
                        "humanReadableProgram",
                        candidate.humanReadableProgram())
                    .property(
                        "humanReadableProgramHash",
                        candidate.humanReadableProgramHash()))))
            .array("finalEvaluations", array -> evaluations.forEach(
                evaluation -> array.objectValue(object -> {
                    object.property(
                            "candidateHash", evaluation.candidateHash())
                        .property(
                            "alphaStructuralHash",
                            evaluation.alphaStructuralHash())
                        .object("rawComponents", components -> {
                            for (FitnessComponent component
                                    : FitnessComponent.values()) {
                                Integer value = evaluation.rawComponents()
                                    .get(component);
                                if (value != null) {
                                    components.property(
                                        component.name(), value);
                                }
                            }
                        })
                        .stringArray("blockers", evaluation.blockers())
                        .property(
                            "scalarFitness", evaluation.scalarFitness());
                    if (evaluation.evidenceHash() == null) {
                        object.nullProperty("evidenceHash");
                    } else {
                        object.property(
                            "evidenceHash", evaluation.evidenceHash());
                    }
                    object.property(
                        "contentHash", evaluation.contentHash());
                })))
            .property("validationStatus", "NOT_EVALUATED")
            .property("finalTestStatus", "NOT_EVALUATED");
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    public record RetainedCandidate(
        String candidateHash,
        String alphaStructuralHash,
        String genomeJson,
        String planJson,
        String humanReadableProgram,
        String humanReadableProgramHash
    ) {
        public RetainedCandidate {
            EvolutionGenome.requireSha256(
                candidateHash, "candidateHash");
            EvolutionGenome.requireSha256(
                alphaStructuralHash, "alphaStructuralHash");
            if (genomeJson == null || genomeJson.isBlank()
                    || planJson == null || planJson.isBlank()
                    || humanReadableProgram == null
                    || humanReadableProgram.isBlank()) {
                throw new IllegalArgumentException(
                    "retained candidate requires complete payloads");
            }
            EvolutionGenome.requireSha256(
                humanReadableProgramHash,
                "humanReadableProgramHash");
            EvolutionGenome genome = GENOME_CODEC.read(genomeJson);
            EvolutionRewriteProgramPlan plan = PLAN_CODEC.read(planJson);
            if (!GENOME_CODEC.write(genome).equals(genomeJson)
                    || !PLAN_CODEC.write(plan).equals(planJson)) {
                throw new IllegalArgumentException(
                    "retained candidate payload is not canonical");
            }
            EvolutionRewriteProgramCandidate reconstructed =
                EvolutionRewriteProgramCandidate.create(genome, plan);
            if (!candidateHash.equals(reconstructed.contentHash())
                    || !alphaStructuralHash.equals(
                        reconstructed.alphaStructuralHash())) {
                throw new IllegalArgumentException(
                    "retained candidate identities differ from payload");
            }
            if (!humanReadableProgram.equals(plan.toReadableProgram())
                    || !humanReadableProgramHash.equals(
                        EvolutionGenome.hash(humanReadableProgram))) {
                throw new IllegalArgumentException(
                    "retained readable program differs from program plan");
            }
        }

        static RetainedCandidate from(
            EvolutionRewriteProgramCandidate candidate
        ) {
            Objects.requireNonNull(candidate, "candidate");
            String readable = candidate.plan().toReadableProgram();
            return new RetainedCandidate(
                candidate.contentHash(),
                candidate.alphaStructuralHash(),
                GENOME_CODEC.write(candidate.genome()),
                PLAN_CODEC.write(candidate.plan()),
                readable,
                EvolutionGenome.hash(readable));
        }

        EvolutionRewriteProgramCandidate candidate() {
            return EvolutionRewriteProgramCandidate.create(
                GENOME_CODEC.read(genomeJson),
                PLAN_CODEC.read(planJson));
        }
    }
}
