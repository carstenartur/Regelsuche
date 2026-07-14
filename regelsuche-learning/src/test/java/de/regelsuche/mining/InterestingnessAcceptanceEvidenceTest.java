package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.InterestingnessAcceptanceGate.Thresholds;
import de.regelsuche.mining.InterestingnessAssessment.Eligibility;
import de.regelsuche.mining.InterestingnessProfileCalibration.CalibrationReport;
import de.regelsuche.mining.InterestingnessProfileCalibration.ProfileMetric;
import de.regelsuche.mining.InterestingnessProfileCalibration.RankedCase;
import de.regelsuche.mining.InterestingnessProfileCalibration.Sensitivity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InterestingnessAcceptanceEvidenceTest {
    @Test
    void writesByteStablePredeclaredAcceptanceDecision() throws Exception {
        CalibrationReport calibration = new CalibrationReport(
            InterestingnessProfileCalibration.SCHEMA,
            hash('a'),
            hash('b'),
            InterestingnessProfile.THEORY_DISCOVERY,
            List.of(
                new ProfileMetric(InterestingnessProfile.SEARCH_REUSE, 700, 650),
                new ProfileMetric(InterestingnessProfile.THEORY_DISCOVERY, 800, 750)),
            List.of(
                ranked(1, "test-1", "candidate-1", 850, hash('c')),
                ranked(2, "test-2", "candidate-2", 700, hash('d')),
                ranked(3, "test-3", "candidate-3", 450, hash('e')),
                ranked(4, "test-4", "candidate-4", 200, hash('f'))),
            List.of("candidate-1", "candidate-2"),
            new Sensitivity(850, 750, true),
            hash('a'),
            hash('b'));
        InterestingnessAcceptanceGate gate = new InterestingnessAcceptanceGate();

        var first = gate.evaluate(calibration, Thresholds.conservativeDefault());
        var second = gate.evaluate(calibration, Thresholds.conservativeDefault());
        Path output = Path.of(
            "build", "reports", "interestingness-calibration", "acceptance.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, first.toCanonicalJson(), StandardCharsets.UTF_8);

        assertTrue(first.accepted(), first.blockers().toString());
        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertEquals(first.toCanonicalJson(), Files.readString(output));
    }

    private static RankedCase ranked(
        int rank,
        String caseId,
        String candidateId,
        int relevance,
        String assessmentHash
    ) {
        return new RankedCase(
            rank,
            caseId,
            candidateId,
            relevance,
            Eligibility.RANKABLE_COMPLETE,
            900 - rank * 100,
            assessmentHash,
            Map.of(
                "structuralSurprise", 900 - rank * 50,
                "crossFamilyTransfer", 900,
                "pairedUtility", 800,
                "reusability", 700));
    }

    private static String hash(char character) {
        return "sha256:" + String.valueOf(character).repeat(64);
    }
}
