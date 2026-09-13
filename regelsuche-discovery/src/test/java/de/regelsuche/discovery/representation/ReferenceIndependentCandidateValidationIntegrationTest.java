package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.ReferenceIndependentValidationFixtures.FREEZE;
import static de.regelsuche.discovery.representation.ReferenceIndependentValidationFixtures.FREEZE_HASH;
import static de.regelsuche.discovery.representation.ReferenceIndependentValidationFixtures.HISTORICAL_QUALIFICATION_HASH;
import static de.regelsuche.discovery.representation.ReferenceIndependentValidationFixtures.PLAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.validation.OracleValidator.OracleValidation;
import de.regelsuche.validation.SymPyOracleValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceIndependentCandidateValidationIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    void cliValidatesAndReverifiesAll144ArchivedRowsThroughTheRealWorker() throws Exception {
        Path plan = temporary.resolve("plan.json");
        Path freeze = temporary.resolve("freeze.json");
        Path result = temporary.resolve("validation.json");
        Files.writeString(plan, PLAN);
        Files.writeString(freeze, FREEZE);
        String revision = System.getenv("REGELSUCHE_AUTHORITY_GITHUB_SHA");
        if (revision == null || !revision.matches("[0-9a-f]{40}")) {
            revision = "0".repeat(40); // Local test receipt, never presented as a source commit.
        }
        ReferenceIndependentCandidateValidationRunner.main(new String[] {
            "run", plan.toString(), freeze.toString(), FREEZE_HASH, revision,
            "4316", "8192", "5000", result.toString()
        });
        ReferenceIndependentCandidateValidationRunner.main(new String[] {
            "verify", plan.toString(), freeze.toString(), FREEZE_HASH, revision,
            "4316", "8192", "5000", result.toString()
        });
        String json = Files.readString(result);
        var artifact = ReferenceIndependentCandidateValidation.Artifact.fromCanonicalJson(json);
        assertEquals(4316, artifact.content().summary().oracleCalls());
        assertEquals(144, artifact.content().summary().rows());
        var comparison = ReferenceIndependentValidationHistoricalComparison.compare(
            artifact, ReferenceIndependentValidationFixtures.read("post-freeze-qualification"),
            HISTORICAL_QUALIFICATION_HASH);
        assertTrue(comparison.nonReferenceOracleAgreements() > 0);
        assertEquals(4316, comparison.candidates());
        assertEquals(144, comparison.rows().size());
        assertEquals(FREEZE, Files.readString(freeze));
        Path report = Path.of("build/reports/reference-independent-candidate-validation/test");
        Files.createDirectories(report);
        Files.writeString(report.resolve("reference-independent-candidate-validation.json"), json);
        Files.writeString(report.resolve("historical-comparison.json"),
            TargetFreeHeldOutMatrixRunner.canonical(comparison));
    }

    @Test
    void changedHistoricalLabelsAffectOnlyTheSeparateComparison() throws Exception {
        var validation = ReferenceIndependentCandidateValidationTest.Evidence.ARTIFACT;
        String unchanged = validation.toCanonicalJson();
        String historical = ReferenceIndependentValidationFixtures.read("post-freeze-qualification");
        var comparison = ReferenceIndependentValidationHistoricalComparison.compare(
            validation, historical, HISTORICAL_QUALIFICATION_HASH);
        assertTrue(comparison.nonReferenceOracleAgreements() > 0);
        ObjectNode relabelled = (ObjectNode) ReferenceIndependentCandidateValidation.JSON.readTree(historical);
        for (var row : relabelled.path("content").path("rows")) {
            for (var candidate : row.path("candidates")) {
                ((ObjectNode) candidate).put("referenceMatched", true);
            }
        }
        relabelled.put("contentHash", ReferenceIndependentCandidateValidation.hash(relabelled.get("content")));
        var changed = ReferenceIndependentValidationHistoricalComparison.compare(validation,
            TargetFreeHeldOutMatrixRunner.canonical(relabelled), relabelled.path("contentHash").asText());
        assertEquals(0, changed.nonReferenceOracleAgreements());
        assertEquals(unchanged, validation.toCanonicalJson());
        assertEquals(validation, ReferenceIndependentCandidateValidationRunner.run(
            PLAN, FREEZE, FREEZE_HASH, validation.content().repositoryRevision(),
            validation.content().budget(), new SymPyOracleValidator()));
        assertFalse(unchanged.contains(comparison.historicalQualificationHash()));
        assertFalse(unchanged.contains(changed.historicalQualificationHash()));
    }

    @Test
    void replayRejectsSelfConsistentInventedOracleEvidence() {
        var invented = ReferenceIndependentCandidateValidationRunner.run(
            PLAN, FREEZE, FREEZE_HASH, ReferenceIndependentValidationFixtures.REVISION,
            ReferenceIndependentCandidateValidationTest.budget(4316),
            (left, right) -> OracleValidation.unavailable("invented backend result"));
        // Every count, status and hash balances; re-execution must still reject it.
        ReferenceIndependentCandidateValidationVerifier.verifyBindings(
            PLAN, FREEZE, FREEZE_HASH, invented.toCanonicalJson());
        assertThrows(IllegalArgumentException.class, () ->
            ReferenceIndependentCandidateValidationVerifier.verifyReplay(
                PLAN, FREEZE, FREEZE_HASH,
                ReferenceIndependentValidationFixtures.REVISION,
                ReferenceIndependentCandidateValidationTest.budget(4316),
                invented.toCanonicalJson(), new SymPyOracleValidator()));
    }
}
