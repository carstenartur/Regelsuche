package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.InterestingnessAcceptanceGate.AcceptanceStatus;
import de.regelsuche.mining.InterestingnessAcceptanceGate.Thresholds;
import de.regelsuche.mining.InterestingnessAssessment.Eligibility;
import de.regelsuche.mining.InterestingnessProfileCalibration.CalibrationReport;
import de.regelsuche.mining.InterestingnessProfileCalibration.ProfileMetric;
import de.regelsuche.mining.InterestingnessProfileCalibration.RankedCase;
import de.regelsuche.mining.InterestingnessProfileCalibration.Sensitivity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InterestingnessAcceptanceGateTest {
    private final InterestingnessAcceptanceGate gate =
        new InterestingnessAcceptanceGate();

    @Test
    void acceptsOnlyWhenPredeclaredHeldOutThresholdsPass() {
        CalibrationReport report = report(750, 700, 850, 750, true, 4);

        var decision = gate.evaluate(report, Thresholds.conservativeDefault());

        assertTrue(decision.accepted(), decision.blockers().toString());
        assertEquals(AcceptanceStatus.ACCEPTED, decision.status());
        assertEquals("NOT_EVALUATED", decision.promotionStatus());
        assertEquals("NOT_EVALUATED", decision.publicEvidenceStatus());
        assertTrue(decision.toCanonicalJson().contains(
            "\"minimumTestAgreementPermille\":600"));
    }

    @Test
    void rejectsWeakTestAgreementWithoutChangingTheCalibrationReport() {
        CalibrationReport report = report(800, 500, 900, 900, true, 4);

        var decision = gate.evaluate(report, Thresholds.conservativeDefault());

        assertFalse(decision.accepted());
        assertEquals(AcceptanceStatus.REJECTED, decision.status());
        assertTrue(decision.blockers().contains("test-agreement=500<600"));
        assertEquals(hash('r'), decision.calibrationReportHash());
    }

    @Test
    void rejectsSmallOrUnstableEvidenceEvenWhenAgreementLooksHigh() {
        CalibrationReport report = report(900, 900, 650, 500, false, 2);
        Thresholds strict = new Thresholds(4, 650, 600, 700, 600, true, true);

        var decision = gate.evaluate(report, strict);

        assertFalse(decision.accepted());
        assertTrue(decision.blockers().contains("test-case-count=2<4"));
        assertTrue(decision.blockers().contains("profile-order-agreement=650<700"));
        assertTrue(decision.blockers().contains(
            "leave-one-out-selection-stability=500<600"));
        assertTrue(decision.blockers().contains(
            "top-candidate-is-not-stable-across-profiles"));
    }

    @Test
    void decisionIsDeterministicAndDoesNotPerformPromotion() {
        CalibrationReport report = report(750, 700, 850, 750, true, 4);
        Thresholds thresholds = Thresholds.conservativeDefault();

        var first = gate.evaluate(report, thresholds);
        var second = gate.evaluate(report, thresholds);

        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertEquals("NOT_EVALUATED", first.promotionStatus());
        assertEquals("NOT_EVALUATED", first.publicEvidenceStatus());
    }

    private static CalibrationReport report(
        int calibrationAgreement,
        int testAgreement,
        int profileOrderAgreement,
        int leaveOneOut,
        boolean topStable,
        int testCases
    ) {
        List<RankedCase> ranking = java.util.stream.IntStream.range(0, testCases)
            .mapToObj(index -> new RankedCase(
                index + 1,
                "test-case-" + index,
                "candidate-" + index,
                800 - index * 50,
                Eligibility.RANKABLE_COMPLETE,
                700 - index * 25,
                hash((char) ('a' + index)),
                Map.of(
                    "structuralSurprise", 800 - index * 20,
                    "crossFamilyTransfer", 900,
                    "pairedUtility", 750,
                    "reusability", 650)))
            .toList();
        return new CalibrationReport(
            InterestingnessProfileCalibration.SCHEMA,
            hash('p'),
            hash('l'),
            InterestingnessProfile.THEORY_DISCOVERY,
            List.of(
                new ProfileMetric(
                    InterestingnessProfile.SEARCH_REUSE,
                    calibrationAgreement - 50,
                    Math.max(0, testAgreement - 50)),
                new ProfileMetric(
                    InterestingnessProfile.THEORY_DISCOVERY,
                    calibrationAgreement,
                    testAgreement)),
            ranking,
            List.of("candidate-0"),
            new Sensitivity(profileOrderAgreement, leaveOneOut, topStable),
            hash('s'),
            hash('r'));
    }

    private static String hash(char character) {
        char normalized = Character.toLowerCase(character);
        if (normalized < 'a' || normalized > 'f') {
            normalized = 'a';
        }
        return "sha256:" + String.valueOf(normalized).repeat(64);
    }
}
