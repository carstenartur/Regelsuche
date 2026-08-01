package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable VALIDATION-only selection evidence for one completed evolutionary
 * TRAIN population. The contract ends before FINAL TEST.
 */
public record EvolutionValidationSelection(
    String schema,
    String studyPlanHash,
    String splitManifestHash,
    String trainPopulationRunHash,
    String validationSuiteHash,
    String evaluationSplit,
    List<String> validationCaseIds,
    List<EvolutionValidationCandidate> candidates,
    String selectionPolicy,
    SelectionOutcome selectionOutcome,
    String selectedGenomeHash,
    String selectedConfigurationHash,
    String validationStatus,
    String finalTestStatus,
    String proofStatus,
    String externalNoveltyStatus,
    String promotionStatus,
    String publicEvidenceStatus,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-validation-selection/v1";
    public static final String SELECTION_POLICY =
        "MAX_NEWLY_SOLVED_THEN_REACHED_THEN_MIN_RESOURCES_THEN_CONFIGURATION_HASH";
    public static final String VALIDATION = "VALIDATION";
    public static final String COMPLETED = "COMPLETED";
    public static final String NOT_EVALUATED = "NOT_EVALUATED";

    private static final Comparator<EvolutionValidationCandidate> ORDER =
        Comparator.comparingInt(EvolutionValidationCandidate::newlySolvedCases)
            .reversed()
            .thenComparing(Comparator.comparingInt(
                EvolutionValidationCandidate::reachedCases).reversed())
            .thenComparingLong(EvolutionValidationCandidate::exploredStates)
            .thenComparingLong(
                EvolutionValidationCandidate::candidateEvaluations)
            .thenComparing(EvolutionValidationCandidate::configurationHash);

    public EvolutionValidationSelection {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported validation-selection schema");
        }
        EvolutionGenome.requireSha256(studyPlanHash, "studyPlanHash");
        EvolutionGenome.requireSha256(splitManifestHash, "splitManifestHash");
        EvolutionGenome.requireSha256(
            trainPopulationRunHash, "trainPopulationRunHash");
        EvolutionGenome.requireSha256(
            validationSuiteHash, "validationSuiteHash");
        if (!VALIDATION.equals(evaluationSplit)) {
            throw new IllegalArgumentException(
                "selection evidence must come from the VALIDATION split");
        }
        validationCaseIds = canonicalCaseIds(validationCaseIds);
        candidates = canonicalCandidates(candidates, validationCaseIds);
        if (!SELECTION_POLICY.equals(selectionPolicy)) {
            throw new IllegalArgumentException(
                "unsupported validation selection policy");
        }
        Objects.requireNonNull(selectionOutcome, "selectionOutcome");
        selectedGenomeHash = selectedGenomeHash == null
            ? "" : selectedGenomeHash;
        selectedConfigurationHash = selectedConfigurationHash == null
            ? "" : selectedConfigurationHash;
        Selection expected = select(candidates);
        if (selectionOutcome != expected.outcome()
                || !selectedGenomeHash.equals(expected.genomeHash())
                || !selectedConfigurationHash.equals(
                    expected.configurationHash())) {
            throw new IllegalArgumentException(
                "frozen selection differs from deterministic VALIDATION result");
        }
        if (!COMPLETED.equals(validationStatus)) {
            throw new IllegalArgumentException(
                "validation selection must be complete");
        }
        requireNotEvaluated(finalTestStatus, "finalTestStatus");
        requireNotEvaluated(proofStatus, "proofStatus");
        requireNotEvaluated(
            externalNoveltyStatus, "externalNoveltyStatus");
        requireNotEvaluated(promotionStatus, "promotionStatus");
        requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expectedHash = EvolutionValidationArtifactSupport.hash(payload(
            studyPlanHash, splitManifestHash, trainPopulationRunHash,
            validationSuiteHash, evaluationSplit, validationCaseIds,
            candidates, selectionPolicy, selectionOutcome,
            selectedGenomeHash, selectedConfigurationHash, validationStatus,
            finalTestStatus, proofStatus, externalNoveltyStatus,
            promotionStatus, publicEvidenceStatus));
        if (!expectedHash.equals(contentHash)) {
            throw new IllegalArgumentException(
                "validation selection contentHash mismatch");
        }
    }

    public static EvolutionValidationSelection create(
        String studyPlanHash,
        String splitManifestHash,
        String trainPopulationRunHash,
        String validationSuiteHash,
        List<String> validationCaseIds,
        List<EvolutionValidationCandidate> candidates
    ) {
        List<String> retainedCaseIds = canonicalCaseIds(validationCaseIds);
        List<EvolutionValidationCandidate> retainedCandidates =
            canonicalCandidates(candidates, retainedCaseIds);
        Selection selected = select(retainedCandidates);
        Map<String, Object> payload = payload(
            studyPlanHash, splitManifestHash, trainPopulationRunHash,
            validationSuiteHash, VALIDATION, retainedCaseIds,
            retainedCandidates, SELECTION_POLICY, selected.outcome(),
            selected.genomeHash(), selected.configurationHash(), COMPLETED,
            NOT_EVALUATED, NOT_EVALUATED, NOT_EVALUATED,
            NOT_EVALUATED, NOT_EVALUATED);
        return new EvolutionValidationSelection(
            SCHEMA, studyPlanHash, splitManifestHash, trainPopulationRunHash,
            validationSuiteHash, VALIDATION, retainedCaseIds,
            retainedCandidates, SELECTION_POLICY, selected.outcome(),
            selected.genomeHash(), selected.configurationHash(), COMPLETED,
            NOT_EVALUATED, NOT_EVALUATED, NOT_EVALUATED,
            NOT_EVALUATED, NOT_EVALUATED,
            EvolutionValidationArtifactSupport.hash(payload));
    }

    public static EvolutionValidationSelection fromCanonicalJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(
                "validation selection JSON must not be blank");
        }
        try {
            return EvolutionValidationArtifactSupport.JSON.readValue(
                json, EvolutionValidationSelection.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "invalid validation selection JSON", exception);
        }
    }

    public String toCanonicalJson() {
        try {
            Map<String, Object> value = payload(
                studyPlanHash, splitManifestHash, trainPopulationRunHash,
                validationSuiteHash, evaluationSplit, validationCaseIds,
                candidates, selectionPolicy, selectionOutcome,
                selectedGenomeHash, selectedConfigurationHash,
                validationStatus, finalTestStatus, proofStatus,
                externalNoveltyStatus, promotionStatus, publicEvidenceStatus);
            value.put("schema", SCHEMA);
            value.put("contentHash", contentHash);
            return EvolutionValidationArtifactSupport.JSON
                .writeValueAsString(value) + "\n";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "cannot serialize validation selection", exception);
        }
    }

    public boolean hasSelection() {
        return selectionOutcome == SelectionOutcome.SELECTED;
    }

    public enum SelectionOutcome {
        SELECTED,
        NO_ELIGIBLE_CANDIDATE
    }

    private record Selection(
        SelectionOutcome outcome,
        String genomeHash,
        String configurationHash
    ) {
        static Selection none() {
            return new Selection(
                SelectionOutcome.NO_ELIGIBLE_CANDIDATE, "", "");
        }
    }

    private static Selection select(
        List<EvolutionValidationCandidate> candidates
    ) {
        return candidates.stream()
            .filter(EvolutionValidationCandidate::eligible)
            .min(ORDER)
            .map(candidate -> new Selection(
                SelectionOutcome.SELECTED,
                candidate.genomeHash(), candidate.configurationHash()))
            .orElseGet(Selection::none);
    }

    private static List<String> canonicalCaseIds(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(
                "validation suite requires case ids");
        }
        List<String> retained = values.stream()
            .map(value -> EvolutionValidationArtifactSupport.requireText(
                value, "validationCaseId"))
            .toList();
        if (retained.stream().distinct().count() != retained.size()) {
            throw new IllegalArgumentException(
                "validation suite contains duplicate case ids");
        }
        return retained;
    }

    private static List<EvolutionValidationCandidate> canonicalCandidates(
        List<EvolutionValidationCandidate> values,
        List<String> validationCaseIds
    ) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(
                "validation selection requires candidate configurations");
        }
        List<EvolutionValidationCandidate> retained = values.stream()
            .map(value -> Objects.requireNonNull(value, "candidate validation"))
            .sorted(Comparator.comparing(
                EvolutionValidationCandidate::configurationHash))
            .toList();
        if (retained.stream()
                .map(EvolutionValidationCandidate::configurationHash)
                .distinct().count() != retained.size()) {
            throw new IllegalArgumentException(
                "duplicate validation configuration");
        }
        for (EvolutionValidationCandidate candidate : retained) {
            List<String> actual = candidate.cases().stream()
                .map(EvolutionValidationCaseEvidence::caseId).toList();
            if (!validationCaseIds.equals(actual)) {
                throw new IllegalArgumentException(
                    "candidate validation does not retain the complete frozen case order");
            }
        }
        return retained;
    }

    private static Map<String, Object> payload(
        String studyPlanHash,
        String splitManifestHash,
        String trainPopulationRunHash,
        String validationSuiteHash,
        String evaluationSplit,
        List<String> validationCaseIds,
        List<EvolutionValidationCandidate> candidates,
        String selectionPolicy,
        SelectionOutcome selectionOutcome,
        String selectedGenomeHash,
        String selectedConfigurationHash,
        String validationStatus,
        String finalTestStatus,
        String proofStatus,
        String externalNoveltyStatus,
        String promotionStatus,
        String publicEvidenceStatus
    ) {
        Map<String, Object> value = new TreeMap<>();
        value.put("candidates", candidates);
        value.put("evaluationSplit", evaluationSplit);
        value.put("externalNoveltyStatus", externalNoveltyStatus);
        value.put("finalTestStatus", finalTestStatus);
        value.put("promotionStatus", promotionStatus);
        value.put("proofStatus", proofStatus);
        value.put("publicEvidenceStatus", publicEvidenceStatus);
        value.put("selectedConfigurationHash", selectedConfigurationHash);
        value.put("selectedGenomeHash", selectedGenomeHash);
        value.put("selectionOutcome", selectionOutcome);
        value.put("selectionPolicy", selectionPolicy);
        value.put("splitManifestHash", splitManifestHash);
        value.put("studyPlanHash", studyPlanHash);
        value.put("trainPopulationRunHash", trainPopulationRunHash);
        value.put("validationCaseIds", validationCaseIds);
        value.put("validationStatus", validationStatus);
        value.put("validationSuiteHash", validationSuiteHash);
        return value;
    }

    private static void requireNotEvaluated(String value, String field) {
        if (!NOT_EVALUATED.equals(value)) {
            throw new IllegalArgumentException(
                field + " must remain NOT_EVALUATED");
        }
    }
}
