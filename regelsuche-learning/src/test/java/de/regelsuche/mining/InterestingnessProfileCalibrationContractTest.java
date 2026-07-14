package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.mining.InterestingnessAssessment.Eligibility;
import de.regelsuche.mining.InterestingnessEvidence.ControlClassification;
import de.regelsuche.mining.InterestingnessEvidence.ProjectNoveltyStatus;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import java.util.List;
import org.junit.jupiter.api.Test;

class InterestingnessProfileCalibrationContractTest {
    @Test
    void calibrationAgreementUsesTheSameEligibilityFirstOrderAsRanking() {
        InterestingnessAssessment completeLowScore = assessment(
            "complete", Eligibility.RANKABLE_COMPLETE, 100, 1000);
        InterestingnessAssessment incompleteHighScore = assessment(
            "incomplete", Eligibility.RANKABLE_INCOMPLETE, 900, 700);

        assertEquals(1, InterestingnessProfileCalibration.rankingDirection(
            completeLowScore, incompleteHighScore));
        assertEquals(-1, InterestingnessProfileCalibration.rankingDirection(
            incompleteHighScore, completeLowScore));
    }

    @Test
    void versionOneKeepsExactlyItsTwoDeclaredProfiles() {
        assertEquals(
            List.of(
                InterestingnessProfile.THEORY_DISCOVERY,
                InterestingnessProfile.SEARCH_REUSE),
            InterestingnessProfileCalibration.supportedProfilesV1());
    }

    private static InterestingnessAssessment assessment(
        String candidateId,
        Eligibility eligibility,
        int totalPermille,
        int completenessPermille
    ) {
        InterestingnessEvidence evidence = new InterestingnessEvidence(
            hash('e'),
            1,
            1,
            0,
            0,
            1,
            1,
            0,
            0,
            CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
            1,
            false,
            ProjectNoveltyStatus.NOVEL_WITHIN_PROJECT,
            1,
            false,
            0,
            0,
            true,
            500,
            ControlClassification.NONE);
        return new InterestingnessAssessment(
            InterestingnessAssessment.SCHEMA,
            candidateId,
            InterestingnessProfile.THEORY_DISCOVERY,
            eligibility,
            evidence,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            0,
            completenessPermille,
            List.of(),
            0,
            0,
            totalPermille,
            List.of(),
            List.of(),
            hash(candidateId.charAt(0)));
    }

    private static String hash(char value) {
        return "sha256:" + String.valueOf(
            Character.toLowerCase(Character.forDigit(value % 16, 16))).repeat(64);
    }
}
