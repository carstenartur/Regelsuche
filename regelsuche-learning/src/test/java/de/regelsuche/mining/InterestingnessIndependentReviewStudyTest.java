package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.InterestingnessAcceptanceGate.Thresholds;
import de.regelsuche.mining.InterestingnessCalibrationCorpus.CorpusSplit;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.CandidateCase;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.ExposureKind;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.ExposureRecord;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.StudyPlan;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InterestingnessIndependentReviewStudyTest {
    @Test
    void freezesCandidatesProtocolAndThresholdsBeforeLabels() {
        StudyPlan first = IndependentReviewProtocolFixtures.plan();
        StudyPlan second = IndependentReviewProtocolFixtures.plan();

        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertEquals(first.contentHash(), second.contentHash());
        assertEquals("NOT_COLLECTED", first.labelsStatus());
        assertEquals("NOT_EVALUATED", first.promotionStatus());
        assertEquals("NOT_EVALUATED", first.publicEvidenceStatus());
        assertFalse(first.toCanonicalJson().contains("relevancePermille"));
        assertTrue(first.toCanonicalJson().contains("thresholdLockHash"));
        assertEquals(2, first.cases().stream()
            .filter(item -> item.split() == CorpusSplit.CALIBRATION).count());
        assertEquals(2, first.cases().stream()
            .filter(item -> item.split() == CorpusSplit.TEST).count());
    }

    @Test
    void predictiveIdentityDoesNotChangeWithProtocolOrThresholdPolicy() {
        InterestingnessIndependentReviewStudy service =
            new InterestingnessIndependentReviewStudy();
        StudyPlan baseline = IndependentReviewProtocolFixtures.plan();
        StudyPlan changed = service.freeze(
            "interestingness-study-2026-alternative",
            IndependentReviewProtocolFixtures.cases(),
            InterestingnessIndependentReviewStudy.ReviewProtocol.conservative(
                IndependentReviewProtocolFixtures.hash('x'),
                IndependentReviewProtocolFixtures.hash('y'),
                List.of("doctoral-mathematics"),
                List.of("conceptual-depth", "technical-interest")
            ),
            new Thresholds(3, 700, 650, 750, 650, true, true),
            List.of()
        );

        assertEquals(baseline.predictiveCorpusHash(), changed.predictiveCorpusHash());
        assertNotEquals(baseline.thresholdLockHash(), changed.thresholdLockHash());
        assertNotEquals(baseline.contentHash(), changed.contentHash());
    }

    @Test
    void rejectsFamilyAndStructuralSignatureLeakageAcrossSplits() {
        List<CandidateCase> familyOverlap = new ArrayList<>(
            IndependentReviewProtocolFixtures.cases());
        CandidateCase test = familyOverlap.get(2);
        familyOverlap.set(2, new CandidateCase(
            test.caseId(),
            test.candidateId(),
            test.split(),
            familyOverlap.getFirst().candidateFamily(),
            test.structuralSignatureHash(),
            test.assessmentContentHash(),
            test.candidateArtifactHash(),
            test.blindedPresentationHash()
        ));
        assertThrows(IllegalArgumentException.class, () -> freeze(familyOverlap, List.of()));

        List<CandidateCase> signatureOverlap = new ArrayList<>(
            IndependentReviewProtocolFixtures.cases());
        CandidateCase secondTest = signatureOverlap.get(3);
        signatureOverlap.set(3, new CandidateCase(
            secondTest.caseId(),
            secondTest.candidateId(),
            secondTest.split(),
            secondTest.candidateFamily(),
            signatureOverlap.get(1).structuralSignatureHash(),
            secondTest.assessmentContentHash(),
            secondTest.candidateArtifactHash(),
            secondTest.blindedPresentationHash()
        ));
        assertThrows(IllegalArgumentException.class, () -> freeze(signatureOverlap, List.of()));
    }

    @Test
    void previouslyExposedArtifactCannotReturnAsFreshTestEvidence() {
        CandidateCase exposedTest = IndependentReviewProtocolFixtures.cases().stream()
            .filter(item -> item.split() == CorpusSplit.TEST)
            .findFirst()
            .orElseThrow();
        ExposureRecord exposure = new ExposureRecord(
            exposedTest.candidateArtifactHash(),
            ExposureKind.PRIOR_EXPERT_REVIEW,
            IndependentReviewProtocolFixtures.hash('z')
        );

        assertThrows(IllegalArgumentException.class, () -> freeze(
            IndependentReviewProtocolFixtures.cases(),
            List.of(exposure)
        ));
    }

    @Test
    void tamperedPlanHashesFailClosed() {
        StudyPlan plan = IndependentReviewProtocolFixtures.plan();
        assertThrows(IllegalArgumentException.class, () -> new StudyPlan(
            plan.schema(),
            plan.studyId(),
            plan.status(),
            plan.cases(),
            plan.reviewProtocol(),
            plan.acceptanceThresholds(),
            plan.thresholdLockHash(),
            plan.historicalExposures(),
            plan.predictiveCorpusHash(),
            plan.labelsStatus(),
            plan.promotionStatus(),
            plan.publicEvidenceStatus(),
            IndependentReviewProtocolFixtures.hash('0')
        ));
    }

    private StudyPlan freeze(
        List<CandidateCase> cases,
        List<ExposureRecord> exposures
    ) {
        return new InterestingnessIndependentReviewStudy().freeze(
            "interestingness-study-2026",
            cases,
            IndependentReviewProtocolFixtures.protocol(),
            Thresholds.conservativeDefault(),
            exposures
        );
    }
}
