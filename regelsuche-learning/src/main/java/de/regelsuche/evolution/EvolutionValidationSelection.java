package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable VALIDATION-only selection evidence for one completed evolutionary
 * TRAIN population.
 *
 * <p>The contract deliberately ends before FINAL TEST. It binds all candidate
 * configurations to the same frozen VALIDATION case matrix, retains every
 * terminal reason and deterministically freezes either one selected
 * configuration or a transparent null result. It contains no FINAL TEST input,
 * target, outcome or retry mechanism.</p>
 */
public record EvolutionValidationSelection(
    String schema,
    String studyPlanHash,
    String splitManifestHash,
    String trainPopulationRunHash,
    String validationSuiteHash,
    List<String> validationCaseIds,
    List<CandidateValidation> candidates,
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
    public static final String COMPLETED = "COMPLETED";
    public static final String NOT_EVALUATED = "NOT_EVALUATED";

    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
        .findAndRegisterModules()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final Comparator<CandidateValidation> VALIDATION_ORDER =
        Comparator.comparingInt(CandidateValidation::newlySolvedCases)
            .reversed()
            .thenComparing(
                Comparator.comparingInt(CandidateValidation::reachedCases)
                    .reversed())
            .thenComparingLong(CandidateValidation::exploredStates)
            .thenComparingLong(CandidateValidation::candidateEvaluations)
            .thenComparing(CandidateValidation::configurationHash);

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
        String expectedHash = hash(payload(
            studyPlanHash,
            splitManifestHash,
            trainPopulationRunHash,
            validationSuiteHash,
            validationCaseIds,
            candidates,
            selectionPolicy,
            selectionOutcome,
            selectedGenomeHash,
            selectedConfigurationHash,
            validationStatus,
            finalTestStatus,
            proofStatus,
            externalNoveltyStatus,
            promotionStatus,
            publicEvidenceStatus));
        if (!expectedHash.equals(contentHash)) {
            throw new IllegalArgumentException(
                "validation selection contentHash mismatch");
        }
    }

    /**
     * Freezes one deterministic selection from a complete VALIDATION matrix.
     */
    public static EvolutionValidationSelection create(
        String studyPlanHash,
        String splitManifestHash,
        String trainPopulationRunHash,
        String validationSuiteHash,
        List<String> validationCaseIds,
        List<CandidateValidation> candidates
    ) {
        List<String> retainedCaseIds = canonicalCaseIds(validationCaseIds);
        List<CandidateValidation> retainedCandidates = canonicalCandidates(
            candidates, retainedCaseIds);
        Selection selected = select(retainedCandidates);
        String contentHash = hash(payload(
            studyPlanHash,
            splitManifestHash,
            trainPopulationRunHash,
            validationSuiteHash,
            retainedCaseIds,
            retainedCandidates,
            SELECTION_POLICY,
            selected.outcome(),
            selected.genomeHash(),
            selected.configurationHash(),
            COMPLETED,
            NOT_EVALUATED,
            NOT_EVALUATED,
            NOT_EVALUATED,
            NOT_EVALUATED,
            NOT_EVALUATED));
        return new EvolutionValidationSelection(
            SCHEMA,
            studyPlanHash,
            splitManifestHash,
            trainPopulationRunHash,
            validationSuiteHash,
            retainedCaseIds,
            retainedCandidates,
            SELECTION_POLICY,
            selected.outcome(),
            selected.genomeHash(),
            selected.configurationHash(),
            COMPLETED,
            NOT_EVALUATED,
            NOT_EVALUATED,
            NOT_EVALUATED,
            NOT_EVALUATED,
            NOT_EVALUATED,
            contentHash);
    }

    public static EvolutionValidationSelection fromCanonicalJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(
                "validation selection JSON must not be blank");
        }
        try {
            return JSON.readValue(json, EvolutionValidationSelection.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "invalid validation selection JSON", exception);
        }
    }

    public String toCanonicalJson() {
        try {
            Map<String, Object> value = payload(
                studyPlanHash,
                splitManifestHash,
                trainPopulationRunHash,
                validationSuiteHash,
                validationCaseIds,
                candidates,
                selectionPolicy,
                selectionOutcome,
                selectedGenomeHash,
                selectedConfigurationHash,
                validationStatus,
                finalTestStatus,
                proofStatus,
                externalNoveltyStatus,
                promotionStatus,
                publicEvidenceStatus);
            value.put("schema", SCHEMA);
            value.put("contentHash", contentHash);
            return JSON.writeValueAsString(value) + "\n";
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

    /** Frozen bounded search parameters selected together with a genome. */
    public record SearchConfiguration(
        int maxDepth,
        int maxExpandedStates,
        int maxCandidatesPerState
    ) {
        public SearchConfiguration {
            if (maxDepth < 1 || maxExpandedStates < 1
                    || maxCandidatesPerState < 1) {
                throw new IllegalArgumentException(
                    "search configuration limits must be positive");
            }
        }

        Map<String, Object> canonicalMaterial() {
            Map<String, Object> value = new TreeMap<>();
            value.put("maxCandidatesPerState", maxCandidatesPerState);
            value.put("maxDepth", maxDepth);
            value.put("maxExpandedStates", maxExpandedStates);
            return value;
        }
    }

    /** Complete paired evidence for one frozen VALIDATION case. */
    public record ValidationCaseEvidence(
        String caseId,
        String family,
        boolean baselineReached,
        boolean candidateReached,
        String baselineTerminalReason,
        String candidateTerminalReason,
        int baselineDepth,
        int candidateDepth,
        long baselineExploredStates,
        long candidateExploredStates,
        long baselineCandidateEvaluations,
        long candidateCandidateEvaluations,
        boolean newlySolved,
        boolean correctnessRegression
    ) {
        public ValidationCaseEvidence {
            requireText(caseId, "caseId");
            requireText(family, "family");
            requireText(baselineTerminalReason, "baselineTerminalReason");
            requireText(candidateTerminalReason, "candidateTerminalReason");
            if (baselineDepth < -1 || candidateDepth < -1
                    || baselineExploredStates < 0
                    || candidateExploredStates < 0
                    || baselineCandidateEvaluations < 0
                    || candidateCandidateEvaluations < 0) {
                throw new IllegalArgumentException(
                    "validation case measurements are outside bounded ranges");
            }
            if (newlySolved != (!baselineReached && candidateReached)) {
                throw new IllegalArgumentException(
                    "newlySolved differs from retained reachability");
            }
            if (correctnessRegression
                    != (baselineReached && !candidateReached)) {
                throw new IllegalArgumentException(
                    "correctnessRegression differs from retained reachability");
            }
        }
    }

    /**
     * Complete VALIDATION evidence for one genome/search-parameter
     * configuration. Aggregate values are independently recomputed.
     */
    public record CandidateValidation(
        String genomeHash,
        String alphaStructuralHash,
        SearchConfiguration searchConfiguration,
        String configurationHash,
        List<ValidationCaseEvidence> cases,
        int reachedCases,
        int newlySolvedCases,
        int correctnessRegressions,
        long exploredStates,
        long candidateEvaluations,
        List<String> blockers
    ) {
        public CandidateValidation {
            EvolutionGenome.requireSha256(genomeHash, "genomeHash");
            EvolutionGenome.requireSha256(
                alphaStructuralHash, "alphaStructuralHash");
            Objects.requireNonNull(
                searchConfiguration, "searchConfiguration");
            EvolutionGenome.requireSha256(
                configurationHash, "configurationHash");
            String expectedConfigurationHash =
                EvolutionValidationSelection.configurationHash(
                    genomeHash, alphaStructuralHash, searchConfiguration);
            if (!expectedConfigurationHash.equals(configurationHash)) {
                throw new IllegalArgumentException(
                    "candidate configurationHash mismatch");
            }
            if (cases == null || cases.isEmpty()) {
                throw new IllegalArgumentException(
                    "candidate validation requires case evidence");
            }
            cases = List.copyOf(cases);
            if (cases.stream().map(ValidationCaseEvidence::caseId)
                    .distinct().count() != cases.size()) {
                throw new IllegalArgumentException(
                    "candidate validation contains duplicate case ids");
            }
            int actualReached = Math.toIntExact(cases.stream()
                .filter(ValidationCaseEvidence::candidateReached).count());
            int actualNewlySolved = Math.toIntExact(cases.stream()
                .filter(ValidationCaseEvidence::newlySolved).count());
            int actualRegressions = Math.toIntExact(cases.stream()
                .filter(ValidationCaseEvidence::correctnessRegression).count());
            long actualExplored = cases.stream().mapToLong(
                ValidationCaseEvidence::candidateExploredStates).sum();
            long actualEvaluations = cases.stream().mapToLong(
                ValidationCaseEvidence::candidateCandidateEvaluations).sum();
            if (reachedCases != actualReached
                    || newlySolvedCases != actualNewlySolved
                    || correctnessRegressions != actualRegressions
                    || exploredStates != actualExplored
                    || candidateEvaluations != actualEvaluations) {
                throw new IllegalArgumentException(
                    "candidate validation aggregates differ from case evidence");
            }
            blockers = canonicalStrings(blockers);
        }

        public static CandidateValidation create(
            String genomeHash,
            String alphaStructuralHash,
            SearchConfiguration searchConfiguration,
            List<ValidationCaseEvidence> cases,
            List<String> blockers
        ) {
            List<ValidationCaseEvidence> retained = List.copyOf(cases);
            return new CandidateValidation(
                genomeHash,
                alphaStructuralHash,
                searchConfiguration,
                EvolutionValidationSelection.configurationHash(
                    genomeHash,
                    alphaStructuralHash,
                    searchConfiguration),
                retained,
                Math.toIntExact(retained.stream()
                    .filter(ValidationCaseEvidence::candidateReached).count()),
                Math.toIntExact(retained.stream()
                    .filter(ValidationCaseEvidence::newlySolved).count()),
                Math.toIntExact(retained.stream()
                    .filter(ValidationCaseEvidence::correctnessRegression)
                    .count()),
                retained.stream().mapToLong(
                    ValidationCaseEvidence::candidateExploredStates).sum(),
                retained.stream().mapToLong(
                    ValidationCaseEvidence::candidateCandidateEvaluations).sum(),
                blockers);
        }

        public boolean eligible() {
            return blockers.isEmpty() && correctnessRegressions == 0;
        }
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

    private static Selection select(List<CandidateValidation> candidates) {
        return candidates.stream()
            .filter(CandidateValidation::eligible)
            .min(VALIDATION_ORDER)
            .map(candidate -> new Selection(
                SelectionOutcome.SELECTED,
                candidate.genomeHash(),
                candidate.configurationHash()))
            .orElseGet(Selection::none);
    }

    private static List<String> canonicalCaseIds(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(
                "validation suite requires case ids");
        }
        List<String> result = values.stream()
            .map(value -> requireText(value, "validationCaseId"))
            .toList();
        if (result.stream().distinct().count() != result.size()) {
            throw new IllegalArgumentException(
                "validation suite contains duplicate case ids");
        }
        return result;
    }

    private static List<CandidateValidation> canonicalCandidates(
        List<CandidateValidation> values,
        List<String> validationCaseIds
    ) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(
                "validation selection requires candidate configurations");
        }
        List<CandidateValidation> result = values.stream()
            .map(value -> Objects.requireNonNull(
                value, "candidate validation"))
            .sorted(Comparator.comparing(
                CandidateValidation::configurationHash))
            .toList();
        if (result.stream().map(CandidateValidation::configurationHash)
                .distinct().count() != result.size()) {
            throw new IllegalArgumentException(
                "duplicate validation configuration");
        }
        for (CandidateValidation candidate : result) {
            List<String> actual = candidate.cases().stream()
                .map(ValidationCaseEvidence::caseId).toList();
            if (!validationCaseIds.equals(actual)) {
                throw new IllegalArgumentException(
                    "candidate validation does not retain the complete frozen case order");
            }
        }
        return result;
    }

    private static String configurationHash(
        String genomeHash,
        String alphaStructuralHash,
        SearchConfiguration configuration
    ) {
        EvolutionGenome.requireSha256(genomeHash, "genomeHash");
        EvolutionGenome.requireSha256(
            alphaStructuralHash, "alphaStructuralHash");
        Objects.requireNonNull(configuration, "searchConfiguration");
        Map<String, Object> value = new TreeMap<>();
        value.put("alphaStructuralHash", alphaStructuralHash);
        value.put("genomeHash", genomeHash);
        value.put("searchConfiguration", configuration.canonicalMaterial());
        return hash(value);
    }

    private static Map<String, Object> payload(
        String studyPlanHash,
        String splitManifestHash,
        String trainPopulationRunHash,
        String validationSuiteHash,
        List<String> validationCaseIds,
        List<CandidateValidation> candidates,
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

    private static String hash(Map<String, Object> value) {
        try {
            return EvolutionGenome.hash(JSON.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "cannot hash validation selection", exception);
        }
    }

    private static List<String> canonicalStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            result.add(requireText(value, "blocker"));
        }
        return result.stream().distinct().sorted().toList();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static void requireNotEvaluated(
        String value, String field
    ) {
        if (!NOT_EVALUATED.equals(value)) {
            throw new IllegalArgumentException(
                field + " must remain NOT_EVALUATED");
        }
    }
}
