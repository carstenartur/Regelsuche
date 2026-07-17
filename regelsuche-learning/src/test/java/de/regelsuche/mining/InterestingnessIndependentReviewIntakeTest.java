package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.InterestingnessIndependentReviewIntake.CandidateCollectionStatus;
import de.regelsuche.mining.InterestingnessIndependentReviewIntake.EvidenceStatus;
import de.regelsuche.mining.InterestingnessIndependentReviewIntake.IntakeOutcome;
import de.regelsuche.mining.InterestingnessIndependentReviewIntake.IntakeReport;
import de.regelsuche.mining.InterestingnessIndependentReviewIntake.ReviewOrigin;
import de.regelsuche.mining.InterestingnessIndependentReviewIntake.ReviewSubmission;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.StudyPlan;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InterestingnessIndependentReviewIntakeTest {
    @Test
    void developmentFixturesRemainDevelopmentOnlyAndCannotClaimConsensus() {
        StudyPlan plan = IndependentReviewProtocolFixtures.plan();
        IntakeReport report = new InterestingnessIndependentReviewIntake().evaluate(
            plan,
            IndependentReviewProtocolFixtures.developmentSubmissions(plan),
            1,
            ""
        );

        assertEquals(EvidenceStatus.DEVELOPMENT_ONLY, report.evidenceStatus());
        assertEquals(0, report.countedExpertReviews());
        assertFalse(report.eligibleForEmpiricalConsensus());
        assertTrue(report.decisions().stream()
            .allMatch(item -> item.outcome() == IntakeOutcome.DEVELOPMENT_ONLY));
        assertTrue(report.candidateStatuses().stream()
            .allMatch(item -> item.status() == CandidateCollectionStatus.DEVELOPMENT_ONLY));
        assertEquals(plan.predictiveCorpusHash(), report.predictiveCorpusHash());
        assertEquals(plan.thresholdLockHash(), report.thresholdLockHash());
        assertEquals("NOT_EVALUATED", report.promotionStatus());
        assertEquals("NOT_EVALUATED", report.publicEvidenceStatus());
    }

    @Test
    void twoIndependentBlindExpertReviewsPerCandidateBecomeConsensusEligible() {
        StudyPlan plan = IndependentReviewProtocolFixtures.plan();
        IntakeReport report = new InterestingnessIndependentReviewIntake().evaluate(
            plan,
            IndependentReviewProtocolFixtures.externalSubmissions(plan),
            1,
            ""
        );

        assertEquals(EvidenceStatus.EXTERNAL_REVIEW_COLLECTION, report.evidenceStatus());
        assertEquals(8, report.countedExpertReviews());
        assertTrue(report.eligibleForEmpiricalConsensus());
        assertTrue(report.candidateStatuses().stream().allMatch(item ->
            item.status() == CandidateCollectionStatus.READY_FOR_CONSENSUS
                && item.countedExpertReviews() == 2
                && item.blindExpertReviews() == 2));
    }

    @Test
    void duplicateReviewerNonBlindAndUndeclaredScaleAreRejected() {
        StudyPlan plan = IndependentReviewProtocolFixtures.plan();
        ReviewSubmission valid = IndependentReviewProtocolFixtures
            .externalSubmissions(plan).getFirst();
        ReviewSubmission duplicate = new ReviewSubmission(
            "duplicate-review",
            valid.studyPlanHash(),
            valid.caseId(),
            valid.candidateId(),
            valid.reviewerHash(),
            ReviewOrigin.EXTERNAL_EXPERT,
            false,
            333,
            333,
            List.of("not-predeclared"),
            valid.qualificationEvidenceHash(),
            valid.independenceAttestationHash()
        );

        IntakeReport report = new InterestingnessIndependentReviewIntake().evaluate(
            plan,
            List.of(valid, duplicate),
            1,
            ""
        );

        assertEquals(0, report.countedExpertReviews());
        assertTrue(report.decisions().stream()
            .allMatch(item -> item.outcome() == IntakeOutcome.REJECTED));
        ReviewSubmission ignored = duplicate;
        assertTrue(report.decisions().stream().flatMap(item -> item.blockers().stream())
            .anyMatch("DUPLICATE_REVIEWER_FOR_CANDIDATE"::equals));
        assertTrue(report.decisions().stream().flatMap(item -> item.blockers().stream())
            .anyMatch("REVIEW_NOT_BLIND"::equals));
        assertTrue(report.decisions().stream().flatMap(item -> item.blockers().stream())
            .anyMatch("RELEVANCE_OUTSIDE_PREDECLARED_SCALE"::equals));
        assertTrue(report.decisions().stream().flatMap(item -> item.blockers().stream())
            .anyMatch("UNDECLARED_RATIONALE_CODE"::equals));
    }

    @Test
    void mixedFixtureAndExternalBatchIsNotPureEmpiricalEvidence() {
        StudyPlan plan = IndependentReviewProtocolFixtures.plan();
        List<ReviewSubmission> mixed = new ArrayList<>();
        mixed.addAll(IndependentReviewProtocolFixtures.externalSubmissions(plan));
        ReviewSubmission fixture = IndependentReviewProtocolFixtures
            .developmentSubmissions(plan).getFirst();
        mixed.add(fixture);

        IntakeReport report = new InterestingnessIndependentReviewIntake().evaluate(
            plan,
            mixed,
            1,
            ""
        );

        assertEquals(
            EvidenceStatus.MIXED_WITH_DEVELOPMENT_FIXTURES,
            report.evidenceStatus()
        );
        assertFalse(report.eligibleForEmpiricalConsensus());
    }

    @Test
    void correctedLabelsCreateNewLabeledIdentityWithoutChangingPredictiveIdentity() {
        StudyPlan plan = IndependentReviewProtocolFixtures.plan();
        InterestingnessIndependentReviewIntake intake =
            new InterestingnessIndependentReviewIntake();
        List<ReviewSubmission> revisionOneLabels =
            IndependentReviewProtocolFixtures.developmentSubmissions(plan);
        IntakeReport revisionOne = intake.evaluate(plan, revisionOneLabels, 1, "");

        List<ReviewSubmission> corrected = new ArrayList<>(revisionOneLabels);
        ReviewSubmission first = corrected.getFirst();
        corrected.set(0, new ReviewSubmission(
            first.reviewId(),
            first.studyPlanHash(),
            first.caseId(),
            first.candidateId(),
            first.reviewerHash(),
            first.origin(),
            first.blindReview(),
            1000,
            first.confidencePermille(),
            first.rationaleCodes(),
            first.qualificationEvidenceHash(),
            first.independenceAttestationHash()
        ));
        IntakeReport revisionTwo = intake.evaluate(
            plan,
            corrected,
            2,
            revisionOne.labeledEvaluationHash()
        );

        assertEquals(
            revisionOne.predictiveCorpusHash(),
            revisionTwo.predictiveCorpusHash()
        );
        assertEquals(
            revisionOne.labeledEvaluationHash(),
            revisionTwo.priorLabeledEvaluationHash()
        );
        assertNotEquals(
            revisionOne.labeledEvaluationHash(),
            revisionTwo.labeledEvaluationHash()
        );
        assertNotEquals(revisionOne.contentHash(), revisionTwo.contentHash());
        assertThrows(IllegalArgumentException.class, () -> intake.evaluate(
            plan,
            corrected,
            2,
            ""
        ));
    }

    @Test
    void tamperedDecisionOrReportHashFailsClosed() {
        StudyPlan plan = IndependentReviewProtocolFixtures.plan();
        IntakeReport report = new InterestingnessIndependentReviewIntake().evaluate(
            plan,
            IndependentReviewProtocolFixtures.developmentSubmissions(plan),
            1,
            ""
        );
        var decision = report.decisions().getFirst();
        assertThrows(IllegalArgumentException.class, () ->
            new InterestingnessIndependentReviewIntake.IntakeDecision(
                decision.reviewId(),
                decision.studyPlanHash(),
                decision.caseId(),
                decision.candidateId(),
                decision.reviewerHash(),
                decision.origin(),
                decision.blindReview(),
                decision.relevancePermille(),
                decision.confidencePermille(),
                decision.rationaleCodes(),
                decision.qualificationEvidenceHash(),
                decision.independenceAttestationHash(),
                decision.outcome(),
                decision.blockers(),
                IndependentReviewProtocolFixtures.hash('0')
            ));
        assertThrows(IllegalArgumentException.class, () -> new IntakeReport(
            report.schema(),
            report.studyPlanHash(),
            report.predictiveCorpusHash(),
            report.thresholdLockHash(),
            report.minimumIndependentExpertReviews(),
            report.revision(),
            report.priorLabeledEvaluationHash(),
            report.evidenceStatus(),
            report.decisions(),
            report.candidateStatuses(),
            report.labeledEvaluationHash(),
            report.promotionStatus(),
            report.publicEvidenceStatus(),
            IndependentReviewProtocolFixtures.hash('0')
        ));
    }
}
