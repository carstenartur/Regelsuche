package de.regelsuche.mining;

import de.regelsuche.mining.InterestingnessAcceptanceGate.Thresholds;
import de.regelsuche.mining.InterestingnessCalibrationCorpus.CorpusSplit;
import de.regelsuche.mining.InterestingnessIndependentReviewIntake.ReviewOrigin;
import de.regelsuche.mining.InterestingnessIndependentReviewIntake.ReviewSubmission;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.CandidateCase;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.ReviewProtocol;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.StudyPlan;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class IndependentReviewProtocolFixtures {
    private IndependentReviewProtocolFixtures() {
    }

    static StudyPlan plan() {
        return new InterestingnessIndependentReviewStudy().freeze(
            "interestingness-study-2026",
            cases(),
            protocol(),
            Thresholds.conservativeDefault(),
            List.of()
        );
    }

    static List<CandidateCase> cases() {
        return List.of(
            candidate("calibration-case-a", "candidate-cal-a", CorpusSplit.CALIBRATION,
                "family-cal-a", 'a'),
            candidate("calibration-case-b", "candidate-cal-b", CorpusSplit.CALIBRATION,
                "family-cal-b", 'b'),
            candidate("test-case-a", "candidate-test-a", CorpusSplit.TEST,
                "family-test-a", 'c'),
            candidate("test-case-b", "candidate-test-b", CorpusSplit.TEST,
                "family-test-b", 'd')
        );
    }

    static ReviewProtocol protocol() {
        return ReviewProtocol.conservative(
            hash('e'),
            hash('f'),
            List.of("doctoral-mathematics", "research-publications"),
            List.of("conceptual-depth", "cross-domain-reuse", "technical-interest")
        );
    }

    static List<ReviewSubmission> developmentSubmissions(StudyPlan plan) {
        List<ReviewSubmission> submissions = new ArrayList<>();
        int index = 0;
        for (CandidateCase candidateCase : plan.cases()) {
            submissions.add(new ReviewSubmission(
                "fixture-review-" + index,
                plan.contentHash(),
                candidateCase.caseId(),
                candidateCase.candidateId(),
                hash((char) ('1' + index)),
                ReviewOrigin.DEVELOPMENT_FIXTURE,
                true,
                index % 2 == 0 ? 750 : 250,
                750,
                List.of("technical-interest"),
                "",
                ""
            ));
            index++;
        }
        return List.copyOf(submissions);
    }

    static List<ReviewSubmission> externalSubmissions(StudyPlan plan) {
        List<ReviewSubmission> submissions = new ArrayList<>();
        int index = 0;
        for (CandidateCase candidateCase : plan.cases()) {
            for (int reviewer = 0; reviewer < 2; reviewer++) {
                submissions.add(new ReviewSubmission(
                    "external-review-" + index,
                    plan.contentHash(),
                    candidateCase.caseId(),
                    candidateCase.candidateId(),
                    hash((char) ('1' + index)),
                    ReviewOrigin.EXTERNAL_EXPERT,
                    true,
                    candidateCase.split() == CorpusSplit.CALIBRATION ? 750 : 500,
                    750,
                    List.of(reviewer == 0 ? "conceptual-depth" : "cross-domain-reuse"),
                    hash((char) ('a' + index)),
                    hash((char) ('A' + index))
                ));
                index++;
            }
        }
        return List.copyOf(submissions);
    }

    static CandidateCase candidate(
        String caseId,
        String candidateId,
        CorpusSplit split,
        String family,
        char marker
    ) {
        return new CandidateCase(
            caseId,
            candidateId,
            split,
            family,
            hash(marker),
            hash((char) (marker + 4)),
            hash((char) (marker + 8)),
            hash((char) (marker + 12))
        );
    }

    static String hash(char marker) {
        byte[] bytes = String.valueOf(marker).getBytes(StandardCharsets.UTF_8);
        return InterestingnessIndependentReviewStudy.sha256(bytes);
    }
}
