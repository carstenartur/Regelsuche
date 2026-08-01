package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Map;
import java.util.TreeMap;

/** Durable claim that consumes one preregistered FINAL TEST attempt. */
public record EvolutionFinalTestReservation(
    String schema,
    String runIdentity,
    String studyPlanHash,
    String splitManifestHash,
    String validationSelectionHash,
    String finalTestSuiteHash,
    String selectedGenomeHash,
    String selectedConfigurationHash,
    String finalTestStatus,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-final-test-reservation/v1";
    public static final String RESERVED = "RESERVED";

    public EvolutionFinalTestReservation {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported FINAL TEST reservation schema");
        }
        EvolutionGenome.requireSha256(runIdentity, "runIdentity");
        EvolutionGenome.requireSha256(studyPlanHash, "studyPlanHash");
        EvolutionGenome.requireSha256(splitManifestHash, "splitManifestHash");
        EvolutionGenome.requireSha256(
            validationSelectionHash, "validationSelectionHash");
        EvolutionGenome.requireSha256(finalTestSuiteHash, "finalTestSuiteHash");
        EvolutionGenome.requireSha256(selectedGenomeHash, "selectedGenomeHash");
        EvolutionGenome.requireSha256(
            selectedConfigurationHash, "selectedConfigurationHash");
        if (!RESERVED.equals(finalTestStatus)) {
            throw new IllegalArgumentException(
                "FINAL TEST reservation must be RESERVED");
        }
        String expectedIdentity = runIdentity(studyPlanHash, splitManifestHash);
        if (!expectedIdentity.equals(runIdentity)) {
            throw new IllegalArgumentException(
                "FINAL TEST runIdentity mismatch");
        }
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        if (!EvolutionValidationArtifactSupport.hash(payload(
                runIdentity, studyPlanHash, splitManifestHash,
                validationSelectionHash, finalTestSuiteHash,
                selectedGenomeHash, selectedConfigurationHash,
                finalTestStatus)).equals(contentHash)) {
            throw new IllegalArgumentException(
                "FINAL TEST reservation contentHash mismatch");
        }
    }

    public static EvolutionFinalTestReservation create(
        EvolutionValidationSelection selection,
        EvolutionFinalTestSuite suite
    ) {
        if (!selection.hasSelection()) {
            throw new IllegalArgumentException(
                "FINAL TEST requires one frozen VALIDATION selection");
        }
        if (!selection.studyPlanHash().equals(suite.studyPlanHash())
                || !selection.splitManifestHash().equals(
                    suite.splitManifestHash())) {
            throw new IllegalArgumentException(
                "FINAL TEST suite does not match the frozen study/split");
        }
        String identity = runIdentity(
            selection.studyPlanHash(), selection.splitManifestHash());
        Map<String, Object> payload = payload(
            identity, selection.studyPlanHash(), selection.splitManifestHash(),
            selection.contentHash(), suite.contentHash(),
            selection.selectedGenomeHash(),
            selection.selectedConfigurationHash(), RESERVED);
        return new EvolutionFinalTestReservation(
            SCHEMA, identity, selection.studyPlanHash(),
            selection.splitManifestHash(), selection.contentHash(),
            suite.contentHash(), selection.selectedGenomeHash(),
            selection.selectedConfigurationHash(), RESERVED,
            EvolutionValidationArtifactSupport.hash(payload));
    }

    public static EvolutionFinalTestReservation fromCanonicalJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(
                "FINAL TEST reservation JSON must not be blank");
        }
        try {
            return EvolutionValidationArtifactSupport.JSON.readValue(
                json, EvolutionFinalTestReservation.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "invalid FINAL TEST reservation JSON", exception);
        }
    }

    public String toCanonicalJson() {
        try {
            Map<String, Object> value = payload(
                runIdentity, studyPlanHash, splitManifestHash,
                validationSelectionHash, finalTestSuiteHash,
                selectedGenomeHash, selectedConfigurationHash,
                finalTestStatus);
            value.put("schema", SCHEMA);
            value.put("contentHash", contentHash);
            return EvolutionValidationArtifactSupport.JSON
                .writeValueAsString(value) + "\n";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "cannot serialize FINAL TEST reservation", exception);
        }
    }

    static String runIdentity(
        String studyPlanHash,
        String splitManifestHash
    ) {
        EvolutionGenome.requireSha256(studyPlanHash, "studyPlanHash");
        EvolutionGenome.requireSha256(splitManifestHash, "splitManifestHash");
        Map<String, Object> value = new TreeMap<>();
        value.put("schema", "regelsuche.evolution-final-test-run-identity/v1");
        value.put("splitManifestHash", splitManifestHash);
        value.put("studyPlanHash", studyPlanHash);
        return EvolutionValidationArtifactSupport.hash(value);
    }

    private static Map<String, Object> payload(
        String runIdentity,
        String studyPlanHash,
        String splitManifestHash,
        String validationSelectionHash,
        String finalTestSuiteHash,
        String selectedGenomeHash,
        String selectedConfigurationHash,
        String finalTestStatus
    ) {
        Map<String, Object> value = new TreeMap<>();
        value.put("finalTestStatus", finalTestStatus);
        value.put("finalTestSuiteHash", finalTestSuiteHash);
        value.put("runIdentity", runIdentity);
        value.put("selectedConfigurationHash", selectedConfigurationHash);
        value.put("selectedGenomeHash", selectedGenomeHash);
        value.put("splitManifestHash", splitManifestHash);
        value.put("studyPlanHash", studyPlanHash);
        value.put("validationSelectionHash", validationSelectionHash);
        return value;
    }
}
