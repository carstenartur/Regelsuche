package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.ReferenceIndependentValidationFixtures.HISTORICAL_QUALIFICATION_HASH;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceIndependentValidationHistoricalComparisonTest {
    @TempDir
    Path temporary;

    @Test
    void rejectsRelabelledAndRehashedHistoryUnderTheOriginalPin() throws Exception {
        var validation = ReferenceIndependentCandidateValidationTest.Evidence.ARTIFACT;
        String historical = ReferenceIndependentValidationFixtures.read("post-freeze-qualification");
        var original = ReferenceIndependentValidationHistoricalComparison.compare(
            validation, historical, HISTORICAL_QUALIFICATION_HASH);
        assertEquals(HISTORICAL_QUALIFICATION_HASH, original.historicalQualificationHash());
        String changed = relabelAndRehash(historical);
        assertThrows(IllegalArgumentException.class, () ->
            ReferenceIndependentValidationHistoricalComparison.compare(
                validation, changed, HISTORICAL_QUALIFICATION_HASH));
    }

    @Test
    void cliAcceptsPinnedHistoryAndRefusesChangedLabelsBeforeWritingAComparison() throws Exception {
        Path validationFile = temporary.resolve("validation.json");
        Path historyFile = temporary.resolve("history.json");
        Path comparisonFile = temporary.resolve("comparison.json");
        Path rejectedFile = temporary.resolve("rejected.json");
        String historical = ReferenceIndependentValidationFixtures.read("post-freeze-qualification");
        Files.writeString(validationFile,
            ReferenceIndependentCandidateValidationTest.Evidence.ARTIFACT.toCanonicalJson());
        Files.writeString(historyFile, historical);
        assertDoesNotThrow(() -> ReferenceIndependentValidationHistoricalComparison.main(new String[] {
            validationFile.toString(), historyFile.toString(), HISTORICAL_QUALIFICATION_HASH,
            comparisonFile.toString()
        }));
        var comparison = ReferenceIndependentCandidateValidation.JSON.readTree(
            Files.readString(comparisonFile));
        assertEquals(HISTORICAL_QUALIFICATION_HASH,
            comparison.path("historicalQualificationHash").asText());
        assertEquals(4316, comparison.path("candidates").asInt());
        Files.writeString(historyFile, relabelAndRehash(historical));
        assertThrows(IllegalArgumentException.class, () ->
            ReferenceIndependentValidationHistoricalComparison.main(new String[] {
                validationFile.toString(), historyFile.toString(), HISTORICAL_QUALIFICATION_HASH,
                rejectedFile.toString()
            }));
        assertFalse(Files.exists(rejectedFile));
    }

    private static String relabelAndRehash(String historical) throws Exception {
        ObjectNode changed = (ObjectNode) ReferenceIndependentCandidateValidation.JSON.readTree(historical);
        ObjectNode candidate = (ObjectNode) changed.path("content").path("rows").get(0)
            .path("candidates").get(0);
        candidate.put("referenceMatched", !candidate.path("referenceMatched").asBoolean());
        changed.put("contentHash", ReferenceIndependentCandidateValidation.hash(changed.path("content")));
        String result = TargetFreeHeldOutMatrixRunner.canonical(changed);
        // A fully self-consistent artifact with the same freeze must still fail the external pin.
        assertNotEquals(HISTORICAL_QUALIFICATION_HASH,
            QualificationArtifact.fromCanonicalJson(result).contentHash());
        return result;
    }
}
