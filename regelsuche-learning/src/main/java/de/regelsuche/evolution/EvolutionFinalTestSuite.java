package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable, content-addressed FINAL TEST suite bound to a preregistered split. */
public record EvolutionFinalTestSuite(
    String schema,
    String studyPlanHash,
    String splitManifestHash,
    String baselineProfileHash,
    String evaluationSplit,
    List<CaseDefinition> cases,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-final-test-suite/v1";
    public static final String FINAL_TEST = "FINAL_TEST";

    public EvolutionFinalTestSuite {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported FINAL TEST suite schema");
        }
        EvolutionGenome.requireSha256(studyPlanHash, "studyPlanHash");
        EvolutionGenome.requireSha256(splitManifestHash, "splitManifestHash");
        EvolutionGenome.requireSha256(
            baselineProfileHash, "baselineProfileHash");
        if (!FINAL_TEST.equals(evaluationSplit)) {
            throw new IllegalArgumentException(
                "FINAL TEST suite must use evaluationSplit=FINAL_TEST");
        }
        cases = canonicalCases(cases);
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        if (!EvolutionValidationArtifactSupport.hash(payload(
                studyPlanHash, splitManifestHash, baselineProfileHash,
                evaluationSplit, cases)).equals(contentHash)) {
            throw new IllegalArgumentException(
                "FINAL TEST suite contentHash mismatch");
        }
    }

    public static EvolutionFinalTestSuite create(
        String studyPlanHash,
        String splitManifestHash,
        String baselineProfileHash,
        List<CaseDefinition> cases
    ) {
        List<CaseDefinition> retained = canonicalCases(cases);
        Map<String, Object> payload = payload(
            studyPlanHash, splitManifestHash, baselineProfileHash,
            FINAL_TEST, retained);
        return new EvolutionFinalTestSuite(
            SCHEMA, studyPlanHash, splitManifestHash, baselineProfileHash,
            FINAL_TEST, retained,
            EvolutionValidationArtifactSupport.hash(payload));
    }

    public static EvolutionFinalTestSuite fromCanonicalJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(
                "FINAL TEST suite JSON must not be blank");
        }
        try {
            return EvolutionValidationArtifactSupport.JSON.readValue(
                json, EvolutionFinalTestSuite.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "invalid FINAL TEST suite JSON", exception);
        }
    }

    public String toCanonicalJson() {
        try {
            Map<String, Object> value = payload(
                studyPlanHash, splitManifestHash, baselineProfileHash,
                evaluationSplit, cases);
            value.put("schema", SCHEMA);
            value.put("contentHash", contentHash);
            return EvolutionValidationArtifactSupport.JSON
                .writeValueAsString(value) + "\n";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "cannot serialize FINAL TEST suite", exception);
        }
    }

    public record CaseDefinition(
        String caseId,
        String family,
        String caseMaterialHash
    ) {
        public CaseDefinition {
            EvolutionValidationArtifactSupport.requireText(caseId, "caseId");
            EvolutionValidationArtifactSupport.requireText(family, "family");
            EvolutionGenome.requireSha256(
                caseMaterialHash, "caseMaterialHash");
        }
    }

    private static List<CaseDefinition> canonicalCases(
        List<CaseDefinition> values
    ) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("FINAL TEST suite requires cases");
        }
        List<CaseDefinition> retained = values.stream()
            .map(value -> Objects.requireNonNull(value, "FINAL TEST case"))
            .toList();
        if (retained.stream().map(CaseDefinition::caseId).distinct().count()
                != retained.size()) {
            throw new IllegalArgumentException(
                "FINAL TEST suite contains duplicate case ids");
        }
        return retained;
    }

    private static Map<String, Object> payload(
        String studyPlanHash,
        String splitManifestHash,
        String baselineProfileHash,
        String evaluationSplit,
        List<CaseDefinition> cases
    ) {
        Map<String, Object> value = new TreeMap<>();
        value.put("baselineProfileHash", baselineProfileHash);
        value.put("cases", cases);
        value.put("evaluationSplit", evaluationSplit);
        value.put("splitManifestHash", splitManifestHash);
        value.put("studyPlanHash", studyPlanHash);
        return value;
    }
}
