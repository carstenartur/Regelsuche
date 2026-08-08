package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.TerminalOutcome;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.json.JsonWriter;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ProofCarryingShowcaseCandidateSelection(
    String schema,
    String showcaseId,
    String planContentHash,
    String retainedPopulationRunHash,
    String populationRunHash,
    String finalGenerationReportHash,
    TerminalOutcome populationTerminalOutcome,
    String selectionPolicy,
    List<Alternative> alternatives,
    String selectedCandidateHash,
    String selectedCandidateAlphaStructuralHash,
    String selectedPlanHash,
    String status,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.proof-carrying-showcase-candidate-selection/v1";
    public static final String STATUS =
        "TRAIN_SELECTION_FROZEN_FINAL_TEST_UNSEEN";
    public static final String SELECTION_POLICY =
        "MAX_TRAIN_SCALAR_THEN_MIN_PROGRAM_NODES_THEN_CANDIDATE_HASH_"
            + "AMONG_NON_SEED_COMPOSITE_DECISION_CANDIDATES";

    public ProofCarryingShowcaseCandidateSelection {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported showcase candidate-selection schema");
        }
        requireText(showcaseId, "showcaseId");
        EvolutionGenome.requireSha256(planContentHash, "planContentHash");
        EvolutionGenome.requireSha256(
            retainedPopulationRunHash, "retainedPopulationRunHash");
        EvolutionGenome.requireSha256(
            populationRunHash, "populationRunHash");
        EvolutionGenome.requireSha256(
            finalGenerationReportHash, "finalGenerationReportHash");
        Objects.requireNonNull(
            populationTerminalOutcome, "populationTerminalOutcome");
        if (populationTerminalOutcome == TerminalOutcome.EXTINCT) {
            throw new IllegalArgumentException(
                "an extinct TRAIN population cannot freeze a candidate");
        }
        if (!SELECTION_POLICY.equals(selectionPolicy)) {
            throw new IllegalArgumentException(
                "unsupported showcase candidate-selection policy");
        }
        alternatives = canonicalAlternatives(alternatives);
        EvolutionGenome.requireSha256(
            selectedCandidateHash, "selectedCandidateHash");
        EvolutionGenome.requireSha256(
            selectedCandidateAlphaStructuralHash,
            "selectedCandidateAlphaStructuralHash");
        EvolutionGenome.requireSha256(selectedPlanHash, "selectedPlanHash");
        if (!STATUS.equals(status)) {
            throw new IllegalArgumentException(
                "candidate selection must keep FINAL TEST unseen");
        }
        Alternative selected = alternatives.stream()
            .filter(Alternative::eligibleForFreeze)
            .sorted(ranking())
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "candidate selection requires an eligible TRAIN alternative"));
        if (!selected.candidateHash().equals(selectedCandidateHash)
                || !selected.alphaStructuralHash().equals(
                    selectedCandidateAlphaStructuralHash)
                || !selected.planHash().equals(selectedPlanHash)) {
            throw new IllegalArgumentException(
                "selected candidate differs from frozen deterministic ranking");
        }
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            showcaseId,
            planContentHash,
            retainedPopulationRunHash,
            populationRunHash,
            finalGenerationReportHash,
            populationTerminalOutcome,
            alternatives,
            selectedCandidateHash,
            selectedCandidateAlphaStructuralHash,
            selectedPlanHash,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "showcase candidate-selection contentHash mismatch");
        }
    }

    static ProofCarryingShowcaseCandidateSelection create(
        String showcaseId,
        String planContentHash,
        RetainedEvolutionRewriteProgramPopulationRun retained,
        List<Alternative> alternatives
    ) {
        Objects.requireNonNull(retained, "retained");
        List<Alternative> canonical = canonicalAlternatives(alternatives);
        Alternative selected = canonical.stream()
            .filter(Alternative::eligibleForFreeze)
            .sorted(ranking())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "TRAIN population contains no showcase-freezable candidate"));
        var run = retained.populationRun();
        String finalGenerationHash = run.generationReports().getLast()
            .contentHash();
        String hash = EvolutionGenome.hash(render(
            showcaseId,
            planContentHash,
            retained.contentHash(),
            run.contentHash(),
            finalGenerationHash,
            run.terminalOutcome(),
            canonical,
            selected.candidateHash(),
            selected.alphaStructuralHash(),
            selected.planHash(),
            null));
        return new ProofCarryingShowcaseCandidateSelection(
            SCHEMA,
            showcaseId,
            planContentHash,
            retained.contentHash(),
            run.contentHash(),
            finalGenerationHash,
            run.terminalOutcome(),
            SELECTION_POLICY,
            canonical,
            selected.candidateHash(),
            selected.alphaStructuralHash(),
            selected.planHash(),
            STATUS,
            hash);
    }

    public String toCanonicalJson() {
        return render(
            showcaseId,
            planContentHash,
            retainedPopulationRunHash,
            populationRunHash,
            finalGenerationReportHash,
            populationTerminalOutcome,
            alternatives,
            selectedCandidateHash,
            selectedCandidateAlphaStructuralHash,
            selectedPlanHash,
            contentHash);
    }

    private static Comparator<Alternative> ranking() {
        return Comparator
            .comparingInt(Alternative::scalarFitness)
            .reversed()
            .thenComparingInt(Alternative::programNodeCount)
            .thenComparing(Alternative::candidateHash);
    }

    private static List<Alternative> canonicalAlternatives(
        List<Alternative> values
    ) {
        Objects.requireNonNull(values, "alternatives");
        List<Alternative> result = values.stream()
            .map(value -> Objects.requireNonNull(
                value, "candidate alternative"))
            .sorted(Comparator.comparing(Alternative::candidateHash))
            .toList();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                "candidate selection requires terminal alternatives");
        }
        if (new HashSet<>(result.stream()
                .map(Alternative::candidateHash).toList()).size()
                != result.size()) {
            throw new IllegalArgumentException(
                "candidate alternatives require unique identities");
        }
        return List.copyOf(result);
    }

    private static String render(
        String showcaseId,
        String planContentHash,
        String retainedPopulationRunHash,
        String populationRunHash,
        String finalGenerationReportHash,
        TerminalOutcome terminalOutcome,
        List<Alternative> alternatives,
        String selectedCandidateHash,
        String selectedCandidateAlphaStructuralHash,
        String selectedPlanHash,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("showcaseId", showcaseId)
            .property("planContentHash", planContentHash)
            .property(
                "retainedPopulationRunHash", retainedPopulationRunHash)
            .property("populationRunHash", populationRunHash)
            .property(
                "finalGenerationReportHash", finalGenerationReportHash)
            .property(
                "populationTerminalOutcome", terminalOutcome.name())
            .property("selectionPolicy", SELECTION_POLICY)
            .array("alternatives", array -> alternatives.forEach(value ->
                array.objectValue(object -> {
                    object.property("candidateHash", value.candidateHash())
                        .property(
                            "alphaStructuralHash",
                            value.alphaStructuralHash())
                        .property("genomeHash", value.genomeHash())
                        .property("planHash", value.planHash())
                        .property("evaluationHash", value.evaluationHash())
                        .property("scalarFitness", value.scalarFitness())
                        .object("rawComponents", components -> {
                            for (FitnessComponent component
                                    : FitnessComponent.values()) {
                                Integer componentValue = value.rawComponents()
                                    .get(component);
                                if (componentValue != null) {
                                    components.property(
                                        component.name(), componentValue);
                                }
                            }
                        })
                        .stringArray("trainBlockers", value.trainBlockers())
                        .property(
                            "seedExactEquivalent",
                            value.seedExactEquivalent())
                        .property(
                            "seedAlphaEquivalent",
                            value.seedAlphaEquivalent())
                        .property(
                            "programNodeCount", value.programNodeCount())
                        .property(
                            "containsCompositionTopology",
                            value.containsCompositionTopology())
                        .property(
                            "containsDecisionTopology",
                            value.containsDecisionTopology())
                        .property(
                            "minimumStructuralPrimitivePathSteps",
                            value.minimumStructuralPrimitivePathSteps())
                        .stringArray(
                            "freezeBlockers", value.freezeBlockers())
                        .property(
                            "eligibleForFreeze",
                            value.eligibleForFreeze());
                })))
            .property("selectedCandidateHash", selectedCandidateHash)
            .property(
                "selectedCandidateAlphaStructuralHash",
                selectedCandidateAlphaStructuralHash)
            .property("selectedPlanHash", selectedPlanHash)
            .property("status", STATUS);
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    public record Alternative(
        String candidateHash,
        String alphaStructuralHash,
        String genomeHash,
        String planHash,
        String evaluationHash,
        int scalarFitness,
        Map<FitnessComponent, Integer> rawComponents,
        List<String> trainBlockers,
        boolean seedExactEquivalent,
        boolean seedAlphaEquivalent,
        int programNodeCount,
        boolean containsCompositionTopology,
        boolean containsDecisionTopology,
        int minimumStructuralPrimitivePathSteps,
        List<String> freezeBlockers,
        boolean eligibleForFreeze
    ) {
        public Alternative {
            EvolutionGenome.requireSha256(candidateHash, "candidateHash");
            EvolutionGenome.requireSha256(
                alphaStructuralHash, "alphaStructuralHash");
            EvolutionGenome.requireSha256(genomeHash, "genomeHash");
            EvolutionGenome.requireSha256(planHash, "planHash");
            EvolutionGenome.requireSha256(
                evaluationHash, "evaluationHash");
            if (scalarFitness < -1000 || scalarFitness > 1000) {
                throw new IllegalArgumentException(
                    "scalarFitness must be in [-1000,1000]");
            }
            rawComponents = canonicalComponents(rawComponents);
            trainBlockers = canonicalStrings(trainBlockers);
            if (programNodeCount < 1
                    || minimumStructuralPrimitivePathSteps < 1) {
                throw new IllegalArgumentException(
                    "candidate structural counters must be positive");
            }
            freezeBlockers = canonicalStrings(freezeBlockers);
            boolean expectedEligible = trainBlockers.isEmpty()
                && freezeBlockers.isEmpty();
            if (eligibleForFreeze != expectedEligible) {
                throw new IllegalArgumentException(
                    "eligibleForFreeze differs from retained blockers");
            }
        }

        private static Map<FitnessComponent, Integer> canonicalComponents(
            Map<FitnessComponent, Integer> values
        ) {
            EnumMap<FitnessComponent, Integer> result =
                new EnumMap<>(FitnessComponent.class);
            if (values != null) {
                values.forEach((component, value) -> {
                    Objects.requireNonNull(
                        component, "fitness component");
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

        private static List<String> canonicalStrings(List<String> values) {
            return values == null
                ? List.of()
                : values.stream()
                    .map(value -> requireText(value, "blocker"))
                    .distinct()
                    .sorted()
                    .toList();
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim().replaceAll("\\s+", " ");
    }
}
