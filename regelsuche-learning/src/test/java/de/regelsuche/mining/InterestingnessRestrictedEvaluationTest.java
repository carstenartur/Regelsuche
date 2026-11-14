package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.mining.InterestingnessIndependentReviewIntake.ReviewOrigin;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.CandidateCase;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.ExposureKind;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.ExposureRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InterestingnessRestrictedEvaluationTest {
    @Test void authenticatesPredictionInputsAndEvaluatesFrozenFixtureLabelsOnce(@TempDir Path temp) throws Exception {
        exercisePanel(temp, false);
    }

    @Test void blockedAnalysisIsRetainedAndConsumesOnlyOneTestExposure(@TempDir Path temp) throws Exception {
        exercisePanel(temp, true);
    }

    private void exercisePanel(Path temp, boolean withholdOneAssignment) throws Exception {
        var json = new ObjectMapper();
        var data = new InterestingnessProfileCalibrationTest().dataset();
        var candidates = new ArrayList<CandidateCase>();
        var material = new HashMap<String, String>();
        char marker = 'A';
        for (var item : data.cases()) {
            var presentation = String.valueOf(marker);
            var hash = IndependentReviewProtocolFixtures.hash(marker++);
            material.put(hash, presentation);
            candidates.add(new CandidateCase(item.caseId(), item.candidateId(), item.split(), item.candidateFamily(),
                item.structuralSignatureHash(), item.assessmentContentHash(), IndependentReviewProtocolFixtures.hash(marker++), hash));
        }
        var exposures = List.of(new ExposureRecord(candidates.getFirst().candidateArtifactHash(),
            ExposureKind.DEVELOPMENT_EVALUATION, IndependentReviewProtocolFixtures.hash('z')));
        var plan = new InterestingnessIndependentReviewStudy().freeze("qualification-fixture", candidates,
            IndependentReviewProtocolFixtures.protocol(), InterestingnessAcceptanceGate.Thresholds.conservativeDefault(), exposures);
        var planFile = temp.resolve("plan.json");
        var profileFile = temp.resolve("profiles.json");
        var export = temp.resolve("exchange");
        var vault = temp.resolve("vault");
        var policy = temp.resolve("policy");
        Files.writeString(policy, "Synthetic policy.");
        Files.writeString(planFile, plan.toCanonicalJson());
        Files.writeString(profileFile, json.writeValueAsString(data.profiles()));
        new InterestingnessBlindReviewPacketExporter().exportToDirectory(plan,
            InterestingnessBlindReviewPacketExporterTest.assignments(plan), material, "f", export);
        String registration = InterestingnessRestrictedCollection.init(vault, planFile, export, policy,
            ReviewOrigin.DEVELOPMENT_FIXTURE, profileFile);
        assertThrows(IllegalStateException.class, () -> InterestingnessRestrictedCollection.evaluate(vault));
        var assignments = json.readTree(export.resolve("private/assignment-manifest.json").toFile()).required("assignments");
        for (var slot : assignments) {
            if (withholdOneAssignment && slot == assignments.get(0)) continue;
            String candidate = slot.required("candidateId").asText();
            var form = json.createObjectNode();
            form.put("registrationHash", registration);
            form.set("packetId", slot.required("packetId"));
            form.set("packetContentHash", slot.required("packetContentHash"));
            form.put("revision", 1).put("disposition", "RATED").put("blind", true).put("independent", true);
            form.put("relevancePermille", candidate.contains("control") ? 0 : candidate.contains("reuse") ? 1000 : 500);
            form.put("confidencePermille", 750);
            form.putArray("rationaleCodes").add("technical-interest");
            InterestingnessRestrictedCollection.submit(vault, slot.required("reviewerHash").asText(), form.toString());
        }
        String frozen = InterestingnessRestrictedCollection.freeze(vault);
        Files.writeString(profileFile, "[]");
        String result = InterestingnessRestrictedCollection.evaluate(vault);
        assertEquals(result, InterestingnessRestrictedCollection.evaluate(vault));
        var audit = json.readTree(InterestingnessRestrictedCollection.audit(vault));
        var evaluation = audit.required("evaluation");
        assertEquals("DEVELOPMENT_ONLY", evaluation.required("evidenceClass").asText());
        assertEquals(withholdOneAssignment ? "INSUFFICIENT_CONSENSUS" : "FIXTURE_DIAGNOSTICS",
            evaluation.required("analysisStatus").asText());
        assertEquals("NOT_EVALUATED", evaluation.required("empiricalConsensusStatus").asText());
        assertEquals("NOT_EVALUATED", evaluation.required("publicEvidenceStatus").asText());
        assertEquals(1, audit.required("historicalExposures").size());
        assertEquals(3, evaluation.required("testExposureLedger").size());
        if (withholdOneAssignment) {
            assertEquals("MISSING", audit.required("slots").get(0).required("disposition").asText());
            assertTrue(evaluation.required("blockers").toString().contains("insufficient-reviews:"));
            assertFalse(evaluation.has("calibration"));
        } else {
            assertEquals(3, evaluation.required("calibration").required("testRanking").size());
            assertEquals(2, evaluation.required("calibration").required("profileMetrics").size());
            assertFalse(evaluation.required("calibration").required("paretoCandidateIds").isEmpty());
            assertTrue(evaluation.required("calibration").required("sensitivity").has("leaveOneOutSelectionStabilityPermille"));
            for (var row : evaluation.required("corpus").required("cases")) assertEquals(0, row.required("countedExpertReviews").asInt());
            for (var row : evaluation.required("consensus").required("candidates")) assertEquals("DEVELOPMENT_ONLY", row.required("status").asText());
            assertEquals(evaluation.required("calibration").required("contentHash"), evaluation.required("acceptance").required("calibrationReportHash"));
        }
        InterestingnessRestrictedCollection.revise(vault, frozen, "TRANSCRIPTION");
        InterestingnessRestrictedCollection.freeze(vault);
        assertThrows(IllegalStateException.class, () -> InterestingnessRestrictedCollection.evaluate(vault));
        assertEquals(result, json.readTree(InterestingnessRestrictedCollection.audit(vault, 1))
            .required("evaluation").required("evaluationHash").asText());
    }
}
