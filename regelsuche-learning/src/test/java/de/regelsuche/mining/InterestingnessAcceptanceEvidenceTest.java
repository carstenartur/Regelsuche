package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.InterestingnessAcceptanceGate.Thresholds;
import de.regelsuche.mining.InterestingnessAssessment.Eligibility;
import de.regelsuche.mining.InterestingnessProfileCalibration.CalibrationReport;
import de.regelsuche.mining.InterestingnessProfileCalibration.ProfileMetric;
import de.regelsuche.mining.InterestingnessProfileCalibration.RankedCase;
import de.regelsuche.mining.InterestingnessProfileCalibration.Sensitivity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Retains one internally consistent profile/acceptance evidence pair for CI. */
class InterestingnessAcceptanceEvidenceTest {
    @Test
    void retainedAcceptanceReferencesTheSimultaneouslyWrittenProfileReport()
            throws Exception {
        CalibrationReport profileReport = profileReport();
        var decision = new InterestingnessAcceptanceGate().evaluate(
            profileReport, Thresholds.conservativeDefault());
        Path directory = Path.of(
            "build", "reports", "interestingness-calibration");
        Path profileOutput = directory.resolve("profile-report.json");
        Path acceptanceOutput = directory.resolve("acceptance.json");
        Files.createDirectories(directory);
        profileReport.write(profileOutput);
        Files.writeString(acceptanceOutput, decision.toCanonicalJson());

        assertEquals(profileReport.contentHash(), decision.calibrationReportHash());
        assertTrue(Files.readString(profileOutput).contains(
            "\"contentHash\":\"" + decision.calibrationReportHash() + "\""));
        assertEquals(decision.toCanonicalJson(), Files.readString(acceptanceOutput));
        assertFalse(decision.accepted());
        assertTrue(decision.blockers().contains("test-case-count=3<4"));
    }

    private static CalibrationReport profileReport() {
        return new CalibrationReport(
            InterestingnessProfileCalibration.SCHEMA,
            hash('p'),
            hash('l'),
            InterestingnessProfile.THEORY_DISCOVERY,
            List.of(
                new ProfileMetric(
                    InterestingnessProfile.SEARCH_REUSE,
                    666,
                    666),
                new ProfileMetric(
                    InterestingnessProfile.THEORY_DISCOVERY,
                    666,
                    666)),
            List.of(
                ranked(1, "test-reuse", 700, 760, 'a'),
                ranked(2, "test-surprise", 820, 720, 'b'),
                ranked(3, "test-control", 100, -200, 'c')),
            List.of("test-reuse", "test-surprise"),
            new Sensitivity(1000, 1000, true),
            hash('s'),
            hash('r'));
    }

    private static RankedCase ranked(
        int rank,
        String candidateId,
        int relevance,
        int total,
        char hashCharacter
    ) {
        return new RankedCase(
            rank,
            candidateId + "-case",
            candidateId,
            relevance,
            Eligibility.RANKABLE_COMPLETE,
            total,
            hash(hashCharacter),
            Map.of(
                "structuralSurprise", candidateId.contains("control") ? 50 : 800,
                "crossFamilyTransfer", candidateId.contains("control") ? 0 : 1000,
                "pairedUtility", candidateId.contains("reuse") ? 900 : 650,
                "reusability", candidateId.contains("reuse") ? 950 : 700));
    }

    private static String hash(char character) {
        char normalized = Character.toLowerCase(character);
        if (normalized < 'a' || normalized > 'f') {
            normalized = 'a';
        }
        return "sha256:" + String.valueOf(normalized).repeat(64);
    }
}
