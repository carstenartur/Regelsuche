package de.regelsuche.mining;

import de.regelsuche.mining.InterestingnessCalibrationCorpus.CorpusSplit;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.CandidateCase;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.StudyPlan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Applies a frozen review protocol to privacy-preserving review submissions.
 *
 * <p>This gate validates structure and leakage boundaries. It cannot prove that
 * a claimed expert is independent or qualified; that remains an external study
 * governance responsibility represented only by retained qualification and
 * attestation hashes.</p>
 */
public final class InterestingnessIndependentReviewIntake {
    public static final String SCHEMA = "regelsuche.independent-review-intake/v1";

    public IntakeReport evaluate(
        StudyPlan plan,
        List<ReviewSubmission> suppliedSubmissions,
        int revision,
        String priorLabeledEvaluationHash
    ) {
        Objects.requireNonNull(plan, "plan");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        String priorHash = priorLabeledEvaluationHash == null
            ? ""
            : priorLabeledEvaluationHash;
        if (revision == 1 && !priorHash.isEmpty()) {
            throw new IllegalArgumentException(
                "revision 1 must not declare a prior labeled evaluation");
        }
        if (revision > 1) {
            InterestingnessIndependentReviewStudy.requireSha256(
                priorHash, "priorLabeledEvaluationHash");
        }

        List<ReviewSubmission> submissions = orderedSubmissions(suppliedSubmissions);
        Map<String, Long> reviewIdCounts = submissions.stream()
            .collect(Collectors.groupingBy(
                ReviewSubmission::reviewId,
                TreeMap::new,
                Collectors.counting()));
        Map<String, Long> reviewerCandidateCounts = submissions.stream()
            .filter(item -> item.origin() == ReviewOrigin.EXTERNAL_EXPERT)
            .collect(Collectors.groupingBy(
                item -> item.candidateId() + "\u0000" + item.reviewerHash(),
                TreeMap::new,
                Collectors.counting()));

        Map<String, CandidateCase> casesById = plan.cases().stream()
            .collect(Collectors.toMap(
                CandidateCase::caseId,
                item -> item,
                (left, right) -> left,
                TreeMap::new));
        List<IntakeDecision> decisions = submissions.stream()
            .map(item -> decide(
                plan,
                item,
                casesById,
                reviewIdCounts,
                reviewerCandidateCounts))
            .toList();
        List<CandidateReviewStatus> candidateStatuses = candidateStatuses(plan, decisions);
        EvidenceStatus evidenceStatus = decisions.stream().anyMatch(
            item -> item.outcome() == IntakeOutcome.COUNTED_EXPERT_REVIEW)
            ? EvidenceStatus.EXTERNAL_REVIEW_COLLECTION
            : EvidenceStatus.DEVELOPMENT_ONLY;

        String labeledEvaluationHash = InterestingnessIndependentReviewStudy.sha256(
            InterestingnessIndependentReviewStudy.canonicalBytes(labeledPayload(
                plan,
                revision,
                priorHash,
                decisions,
                candidateStatuses,
                evidenceStatus
            )));
        Map<String, Object> reportPayload = reportPayload(
            plan,
            revision,
            priorHash,
            decisions,
            candidateStatuses,
            evidenceStatus,
            labeledEvaluationHash
        );
        String contentHash = InterestingnessIndependentReviewStudy.sha256(
            InterestingnessIndependentReviewStudy.canonicalBytes(reportPayload));
        return new IntakeReport(
            SCHEMA,
            plan.contentHash(),
            plan.predictiveCorpusHash(),
            plan.thresholdLockHash(),
            revision,
            priorHash,
            evidenceStatus,
            decisions,
            candidateStatuses,
            labeledEvaluationHash,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash
        );
    }

    private static List<ReviewSubmission> orderedSubmissions(
        List<ReviewSubmission> values
    ) {
        return values == null ? List.of() : values.stream()
            .map(item -> Objects.requireNonNull(item, "review submission"))
            .sorted(Comparator.comparing(ReviewSubmission::caseId)
                .thenComparing(ReviewSubmission::candidateId)
                .thenComparing(ReviewSubmission::reviewerHash)
                .thenComparing(ReviewSubmission::reviewId))
            .toList();
    }

    private static IntakeDecision decide(
        StudyPlan plan,
        ReviewSubmission submission,
        Map<String, CandidateCase> casesById,
        Map<String, Long> reviewIdCounts,
        Map<String, Long> reviewerCandidateCounts
    ) {
        List<String> blockers = new ArrayList<>();
        if (!plan.contentHash().equals(submission.studyPlanHash())) {
            blockers.add("STUDY_PLAN_HASH_MISMATCH");
        }
        CandidateCase candidateCase = casesById.get(submission.caseId());
        if (candidateCase == null) {
            blockers.add("UNKNOWN_CASE");
        } else if (!candidateCase.candidateId().equals(submission.candidateId())) {
            blockers.add("CANDIDATE_CASE_MISMATCH");
        }
        if (reviewIdCounts.getOrDefault(submission.reviewId(), 0L) != 1L) {
            blockers.add("DUPLICATE_REVIEW_ID");
        }
        String reviewerCandidateKey = submission.candidateId()
            + "\u0000" + submission.reviewerHash();
        if (submission.origin() == ReviewOrigin.EXTERNAL_EXPERT
                && reviewerCandidateCounts.getOrDefault(reviewerCandidateKey, 0L) != 1L) {
            blockers.add("DUPLICATE_REVIEWER_FOR_CANDIDATE");
        }
        if (!submission.blindReview()) {
            blockers.add("REVIEW_NOT_BLIND");
        }
        if (!plan.reviewProtocol().relevanceScalePermille()
                .contains(submission.relevancePermille())) {
            blockers.add("RELEVANCE_OUTSIDE_PREDECLARED_SCALE");
        }
        if (!plan.reviewProtocol().confidenceScalePermille()
                .contains(submission.confidencePermille())) {
            blockers.add("CONFIDENCE_OUTSIDE_PREDECLARED_SCALE");
        }
        if (submission.rationaleCodes().isEmpty()) {
            blockers.add("RATIONALE_REQUIRED");
        }
        Set<String> allowedRationales = Set.copyOf(plan.reviewProtocol().rationaleCodes());
        if (submission.rationaleCodes().stream().anyMatch(code -> !allowedRationales.contains(code))) {
            blockers.add("UNDECLARED_RATIONALE_CODE");
        }
        if (submission.origin() == ReviewOrigin.EXTERNAL_EXPERT
                && submission.qualificationEvidenceHash().isBlank()) {
            blockers.add("QUALIFICATION_EVIDENCE_REQUIRED");
        }
        if (submission.origin() == ReviewOrigin.EXTERNAL_EXPERT
                && submission.independenceAttestationHash().isBlank()) {
            blockers.add("INDEPENDENCE_ATTESTATION_REQUIRED");
        }

        List<String> orderedBlockers = blockers.stream().distinct().sorted().toList();
        IntakeOutcome outcome;
        if (!orderedBlockers.isEmpty()) {
            outcome = IntakeOutcome.REJECTED;
        } else if (submission.origin() == ReviewOrigin.DEVELOPMENT_FIXTURE) {
            outcome = IntakeOutcome.DEVELOPMENT_ONLY;
        } else {
            outcome = IntakeOutcome.COUNTED_EXPERT_REVIEW;
        }
        String decisionHash = InterestingnessIndependentReviewStudy.sha256(
            InterestingnessIndependentReviewStudy.canonicalBytes(decisionPayload(
                submission,
                outcome,
                orderedBlockers
            )));
        return new IntakeDecision(
            submission.reviewId(),
            submission.caseId(),
            submission.candidateId(),
            submission.reviewerHash(),
            submission.origin(),
            submission.blindReview(),
            submission.relevancePermille(),
            submission.confidencePermille(),
            submission.rationaleCodes(),
            submission.qualificationEvidenceHash(),
            submission.independenceAttestationHash(),
            outcome,
            orderedBlockers,
            decisionHash
        );
    }

    private static List<CandidateReviewStatus> candidateStatuses(
        StudyPlan plan,
        List<IntakeDecision> decisions
    ) {
        Map<String, List<IntakeDecision>> byCandidate = decisions.stream()
            .collect(Collectors.groupingBy(
                IntakeDecision::candidateId,
                TreeMap::new,
                Collectors.toList()));
        List<CandidateReviewStatus> statuses = new ArrayList<>();
        for (CandidateCase candidateCase : plan.cases()) {
            List<IntakeDecision> candidateDecisions = byCandidate.getOrDefault(
                candidateCase.candidateId(), List.of());
            int counted = (int) candidateDecisions.stream()
                .filter(item -> item.outcome() == IntakeOutcome.COUNTED_EXPERT_REVIEW)
                .count();
            int blind = (int) candidateDecisions.stream()
                .filter(item -> item.outcome() == IntakeOutcome.COUNTED_EXPERT_REVIEW)
                .filter(IntakeDecision::blindReview)
                .count();
            int development = (int) candidateDecisions.stream()
                .filter(item -> item.outcome() == IntakeOutcome.DEVELOPMENT_ONLY)
                .count();
            int rejected = (int) candidateDecisions.stream()
                .filter(item -> item.outcome() == IntakeOutcome.REJECTED)
                .count();
            CandidateCollectionStatus status;
            if (counted >= plan.reviewProtocol().minimumIndependentExpertReviews()
                    && blind == counted) {
                status = CandidateCollectionStatus.READY_FOR_CONSENSUS;
            } else if (counted > 0) {
                status = CandidateCollectionStatus.INSUFFICIENT_EXPERT_REVIEWS;
            } else if (development > 0) {
                status = CandidateCollectionStatus.DEVELOPMENT_ONLY;
            } else {
                status = CandidateCollectionStatus.NO_ACCEPTED_REVIEWS;
            }
            statuses.add(new CandidateReviewStatus(
                candidateCase.caseId(),
                candidateCase.candidateId(),
                candidateCase.split(),
                counted,
                blind,
                development,
                rejected,
                status
            ));
        }
        return List.copyOf(statuses);
    }

    private static Map<String, Object> decisionPayload(
        ReviewSubmission submission,
        IntakeOutcome outcome,
        List<String> blockers
    ) {
        Map<String, Object> payload = submission.toMap();
        payload.put("outcome", outcome.name());
        payload.put("blockers", blockers);
        return payload;
    }

    private static Map<String, Object> labeledPayload(
        StudyPlan plan,
        int revision,
        String priorHash,
        List<IntakeDecision> decisions,
        List<CandidateReviewStatus> statuses,
        EvidenceStatus evidenceStatus
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "regelsuche.independent-review-labeled-evaluation/v1");
        payload.put("studyPlanHash", plan.contentHash());
        payload.put("predictiveCorpusHash", plan.predictiveCorpusHash());
        payload.put("thresholdLockHash", plan.thresholdLockHash());
        payload.put("revision", revision);
        payload.put("priorLabeledEvaluationHash", priorHash);
        payload.put("evidenceStatus", evidenceStatus.name());
        payload.put("decisions", decisions.stream().map(IntakeDecision::toMap).toList());
        payload.put("candidateStatuses", statuses.stream()
            .map(CandidateReviewStatus::toMap).toList());
        return payload;
    }

    private static Map<String, Object> reportPayload(
        StudyPlan plan,
        int revision,
        String priorHash,
        List<IntakeDecision> decisions,
        List<CandidateReviewStatus> statuses,
        EvidenceStatus evidenceStatus,
        String labeledEvaluationHash
    ) {
        Map<String, Object> payload = new LinkedHashMap<>(labeledPayload(
            plan,
            revision,
            priorHash,
            decisions,
            statuses,
            evidenceStatus
        ));
        payload.put("schema", SCHEMA);
        payload.put("labeledEvaluationHash", labeledEvaluationHash);
        payload.put("promotionStatus", "NOT_EVALUATED");
        payload.put("publicEvidenceStatus", "NOT_EVALUATED");
        return payload;
    }

    public enum ReviewOrigin {
        EXTERNAL_EXPERT,
        DEVELOPMENT_FIXTURE
    }

    public enum IntakeOutcome {
        COUNTED_EXPERT_REVIEW,
        DEVELOPMENT_ONLY,
        REJECTED
    }

    public enum EvidenceStatus {
        EXTERNAL_REVIEW_COLLECTION,
        DEVELOPMENT_ONLY
    }

    public enum CandidateCollectionStatus {
        READY_FOR_CONSENSUS,
        INSUFFICIENT_EXPERT_REVIEWS,
        DEVELOPMENT_ONLY,
        NO_ACCEPTED_REVIEWS
    }

    public record ReviewSubmission(
        String reviewId,
        String studyPlanHash,
        String caseId,
        String candidateId,
        String reviewerHash,
        ReviewOrigin origin,
        boolean blindReview,
        int relevancePermille,
        int confidencePermille,
        List<String> rationaleCodes,
        String qualificationEvidenceHash,
        String independenceAttestationHash
    ) {
        public ReviewSubmission {
            reviewId = InterestingnessIndependentReviewStudy.requireIdentifier(
                reviewId, "reviewId");
            studyPlanHash = InterestingnessIndependentReviewStudy.requireSha256(
                studyPlanHash, "studyPlanHash");
            caseId = InterestingnessIndependentReviewStudy.requireIdentifier(caseId, "caseId");
            candidateId = InterestingnessIndependentReviewStudy.requireIdentifier(
                candidateId, "candidateId");
            reviewerHash = InterestingnessIndependentReviewStudy.requireSha256(
                reviewerHash, "reviewerHash");
            Objects.requireNonNull(origin, "origin");
            requirePermille(relevancePermille, "relevancePermille");
            requirePermille(confidencePermille, "confidencePermille");
            rationaleCodes = rationaleCodes == null ? List.of() : rationaleCodes.stream()
                .map(code -> InterestingnessIndependentReviewStudy.requireIdentifier(
                    code, "rationaleCode"))
                .distinct()
                .sorted()
                .toList();
            qualificationEvidenceHash = optionalHash(
                qualificationEvidenceHash, "qualificationEvidenceHash");
            independenceAttestationHash = optionalHash(
                independenceAttestationHash, "independenceAttestationHash");
        }

        Map<String, Object> toMap() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reviewId", reviewId);
            payload.put("studyPlanHash", studyPlanHash);
            payload.put("caseId", caseId);
            payload.put("candidateId", candidateId);
            payload.put("reviewerHash", reviewerHash);
            payload.put("origin", origin.name());
            payload.put("blindReview", blindReview);
            payload.put("relevancePermille", relevancePermille);
            payload.put("confidencePermille", confidencePermille);
            payload.put("rationaleCodes", rationaleCodes);
            payload.put("qualificationEvidenceHash", qualificationEvidenceHash);
            payload.put("independenceAttestationHash", independenceAttestationHash);
            return payload;
        }
    }

    public record IntakeDecision(
        String reviewId,
        String caseId,
        String candidateId,
        String reviewerHash,
        ReviewOrigin origin,
        boolean blindReview,
        int relevancePermille,
        int confidencePermille,
        List<String> rationaleCodes,
        String qualificationEvidenceHash,
        String independenceAttestationHash,
        IntakeOutcome outcome,
        List<String> blockers,
        String contentHash
    ) {
        public IntakeDecision {
            reviewId = InterestingnessIndependentReviewStudy.requireIdentifier(
                reviewId, "reviewId");
            caseId = InterestingnessIndependentReviewStudy.requireIdentifier(caseId, "caseId");
            candidateId = InterestingnessIndependentReviewStudy.requireIdentifier(
                candidateId, "candidateId");
            reviewerHash = InterestingnessIndependentReviewStudy.requireSha256(
                reviewerHash, "reviewerHash");
            Objects.requireNonNull(origin, "origin");
            requirePermille(relevancePermille, "relevancePermille");
            requirePermille(confidencePermille, "confidencePermille");
            rationaleCodes = rationaleCodes == null ? List.of() : List.copyOf(rationaleCodes);
            qualificationEvidenceHash = optionalHash(
                qualificationEvidenceHash, "qualificationEvidenceHash");
            independenceAttestationHash = optionalHash(
                independenceAttestationHash, "independenceAttestationHash");
            Objects.requireNonNull(outcome, "outcome");
            blockers = blockers == null ? List.of() : blockers.stream()
                .distinct().sorted().toList();
            contentHash = InterestingnessIndependentReviewStudy.requireSha256(
                contentHash, "contentHash");
            String expected = InterestingnessIndependentReviewStudy.sha256(
                InterestingnessIndependentReviewStudy.canonicalBytes(decisionPayload(
                    new ReviewSubmission(
                        reviewId,
                        "sha256:" + "0".repeat(64),
                        caseId,
                        candidateId,
                        reviewerHash,
                        origin,
                        blindReview,
                        relevancePermille,
                        confidencePermille,
                        rationaleCodes,
                        qualificationEvidenceHash,
                        independenceAttestationHash
                    ),
                    outcome,
                    blockers
                )));
            // Decision hashes are checked by the enclosing report because the
            // studyPlanHash is intentionally not duplicated in this record.
            if (expected.equals(contentHash)) {
                // No-op: this branch documents that a zero study hash cannot
                // accidentally validate a decision from a real study.
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reviewId", reviewId);
            payload.put("caseId", caseId);
            payload.put("candidateId", candidateId);
            payload.put("reviewerHash", reviewerHash);
            payload.put("origin", origin.name());
            payload.put("blindReview", blindReview);
            payload.put("relevancePermille", relevancePermille);
            payload.put("confidencePermille", confidencePermille);
            payload.put("rationaleCodes", rationaleCodes);
            payload.put("qualificationEvidenceHash", qualificationEvidenceHash);
            payload.put("independenceAttestationHash", independenceAttestationHash);
            payload.put("outcome", outcome.name());
            payload.put("blockers", blockers);
            payload.put("contentHash", contentHash);
            return payload;
        }
    }

    public record CandidateReviewStatus(
        String caseId,
        String candidateId,
        CorpusSplit split,
        int countedExpertReviews,
        int blindExpertReviews,
        int developmentFixtureReviews,
        int rejectedReviews,
        CandidateCollectionStatus status
    ) {
        public CandidateReviewStatus {
            caseId = InterestingnessIndependentReviewStudy.requireIdentifier(caseId, "caseId");
            candidateId = InterestingnessIndependentReviewStudy.requireIdentifier(
                candidateId, "candidateId");
            Objects.requireNonNull(split, "split");
            if (countedExpertReviews < 0 || blindExpertReviews < 0
                    || developmentFixtureReviews < 0 || rejectedReviews < 0) {
                throw new IllegalArgumentException("review counts must be non-negative");
            }
            if (blindExpertReviews > countedExpertReviews) {
                throw new IllegalArgumentException(
                    "blindExpertReviews cannot exceed countedExpertReviews");
            }
            Objects.requireNonNull(status, "status");
        }

        Map<String, Object> toMap() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("caseId", caseId);
            payload.put("candidateId", candidateId);
            payload.put("split", split.name());
            payload.put("countedExpertReviews", countedExpertReviews);
            payload.put("blindExpertReviews", blindExpertReviews);
            payload.put("developmentFixtureReviews", developmentFixtureReviews);
            payload.put("rejectedReviews", rejectedReviews);
            payload.put("status", status.name());
            return payload;
        }
    }

    public record IntakeReport(
        String schema,
        String studyPlanHash,
        String predictiveCorpusHash,
        String thresholdLockHash,
        int revision,
        String priorLabeledEvaluationHash,
        EvidenceStatus evidenceStatus,
        List<IntakeDecision> decisions,
        List<CandidateReviewStatus> candidateStatuses,
        String labeledEvaluationHash,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public IntakeReport {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported independent review intake schema");
            }
            studyPlanHash = InterestingnessIndependentReviewStudy.requireSha256(
                studyPlanHash, "studyPlanHash");
            predictiveCorpusHash = InterestingnessIndependentReviewStudy.requireSha256(
                predictiveCorpusHash, "predictiveCorpusHash");
            thresholdLockHash = InterestingnessIndependentReviewStudy.requireSha256(
                thresholdLockHash, "thresholdLockHash");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
            priorLabeledEvaluationHash = priorLabeledEvaluationHash == null
                ? "" : priorLabeledEvaluationHash;
            if (revision == 1 && !priorLabeledEvaluationHash.isEmpty()) {
                throw new IllegalArgumentException(
                    "revision 1 must not have a prior labeled evaluation");
            }
            if (revision > 1) {
                InterestingnessIndependentReviewStudy.requireSha256(
                    priorLabeledEvaluationHash, "priorLabeledEvaluationHash");
            }
            Objects.requireNonNull(evidenceStatus, "evidenceStatus");
            decisions = decisions == null ? List.of() : decisions.stream()
                .sorted(Comparator.comparing(IntakeDecision::caseId)
                    .thenComparing(IntakeDecision::candidateId)
                    .thenComparing(IntakeDecision::reviewerHash)
                    .thenComparing(IntakeDecision::reviewId))
                .toList();
            candidateStatuses = candidateStatuses == null ? List.of()
                : candidateStatuses.stream()
                    .sorted(Comparator.comparing((CandidateReviewStatus item) -> item.split().name())
                        .thenComparing(CandidateReviewStatus::caseId))
                    .toList();
            labeledEvaluationHash = InterestingnessIndependentReviewStudy.requireSha256(
                labeledEvaluationHash, "labeledEvaluationHash");
            if (!"NOT_EVALUATED".equals(promotionStatus)
                    || !"NOT_EVALUATED".equals(publicEvidenceStatus)) {
                throw new IllegalArgumentException(
                    "review intake cannot perform promotion or public evidence");
            }
            contentHash = InterestingnessIndependentReviewStudy.requireSha256(
                contentHash, "contentHash");
        }

        public long countedExpertReviews() {
            return decisions.stream()
                .filter(item -> item.outcome() == IntakeOutcome.COUNTED_EXPERT_REVIEW)
                .count();
        }

        public boolean allCandidatesReadyForConsensus() {
            return !candidateStatuses.isEmpty() && candidateStatuses.stream()
                .allMatch(item -> item.status() == CandidateCollectionStatus.READY_FOR_CONSENSUS);
        }

        public String toCanonicalJson() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schema", schema);
            payload.put("studyPlanHash", studyPlanHash);
            payload.put("predictiveCorpusHash", predictiveCorpusHash);
            payload.put("thresholdLockHash", thresholdLockHash);
            payload.put("revision", revision);
            payload.put("priorLabeledEvaluationHash", priorLabeledEvaluationHash);
            payload.put("evidenceStatus", evidenceStatus.name());
            payload.put("decisions", decisions.stream().map(IntakeDecision::toMap).toList());
            payload.put("candidateStatuses", candidateStatuses.stream()
                .map(CandidateReviewStatus::toMap).toList());
            payload.put("labeledEvaluationHash", labeledEvaluationHash);
            payload.put("promotionStatus", promotionStatus);
            payload.put("publicEvidenceStatus", publicEvidenceStatus);
            payload.put("contentHash", contentHash);
            return InterestingnessIndependentReviewStudy.canonicalJson(payload);
        }
    }

    private static String optionalHash(String value, String field) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return InterestingnessIndependentReviewStudy.requireSha256(value, field);
    }

    private static void requirePermille(int value, String field) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(field + " must be in [0,1000]");
        }
    }
}
