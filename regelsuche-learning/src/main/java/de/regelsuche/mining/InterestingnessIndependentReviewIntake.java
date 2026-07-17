package de.regelsuche.mining;

import de.regelsuche.mining.InterestingnessCalibrationCorpus.CorpusSplit;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.CandidateCase;
import de.regelsuche.mining.InterestingnessIndependentReviewStudy.StudyPlan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
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
        validateRevision(revision, priorHash);

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
        int minimumReviews = plan.reviewProtocol().minimumIndependentExpertReviews();
        List<CandidateReviewStatus> candidateStatuses = candidateStatuses(
            plan, decisions, minimumReviews);
        EvidenceStatus evidenceStatus = evidenceStatus(decisions);

        String labeledEvaluationHash = InterestingnessIndependentReviewStudy.sha256(
            InterestingnessIndependentReviewStudy.canonicalBytes(labeledPayload(
                plan.contentHash(),
                plan.predictiveCorpusHash(),
                plan.thresholdLockHash(),
                minimumReviews,
                revision,
                priorHash,
                decisions,
                candidateStatuses,
                evidenceStatus
            )));
        Map<String, Object> reportPayload = reportPayload(
            plan.contentHash(),
            plan.predictiveCorpusHash(),
            plan.thresholdLockHash(),
            minimumReviews,
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
            minimumReviews,
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
            submission.studyPlanHash(),
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

    private static EvidenceStatus evidenceStatus(List<IntakeDecision> decisions) {
        boolean counted = decisions.stream()
            .anyMatch(item -> item.outcome() == IntakeOutcome.COUNTED_EXPERT_REVIEW);
        boolean development = decisions.stream()
            .anyMatch(item -> item.outcome() == IntakeOutcome.DEVELOPMENT_ONLY);
        if (counted && development) {
            return EvidenceStatus.MIXED_WITH_DEVELOPMENT_FIXTURES;
        }
        return counted
            ? EvidenceStatus.EXTERNAL_REVIEW_COLLECTION
            : EvidenceStatus.DEVELOPMENT_ONLY;
    }

    private static List<CandidateReviewStatus> candidateStatuses(
        StudyPlan plan,
        List<IntakeDecision> decisions,
        int minimumReviews
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
            statuses.add(new CandidateReviewStatus(
                candidateCase.caseId(),
                candidateCase.candidateId(),
                candidateCase.split(),
                counted,
                blind,
                development,
                rejected,
                expectedCollectionStatus(
                    counted, blind, development, minimumReviews)
            ));
        }
        return List.copyOf(statuses);
    }

    private static CandidateCollectionStatus expectedCollectionStatus(
        int counted,
        int blind,
        int development,
        int minimumReviews
    ) {
        if (counted >= minimumReviews && blind == counted) {
            return CandidateCollectionStatus.READY_FOR_CONSENSUS;
        }
        if (counted > 0) {
            return CandidateCollectionStatus.INSUFFICIENT_EXPERT_REVIEWS;
        }
        if (development > 0) {
            return CandidateCollectionStatus.DEVELOPMENT_ONLY;
        }
        return CandidateCollectionStatus.NO_ACCEPTED_REVIEWS;
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
        String studyPlanHash,
        String predictiveCorpusHash,
        String thresholdLockHash,
        int minimumReviews,
        int revision,
        String priorHash,
        List<IntakeDecision> decisions,
        List<CandidateReviewStatus> statuses,
        EvidenceStatus evidenceStatus
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "regelsuche.independent-review-labeled-evaluation/v1");
        payload.put("studyPlanHash", studyPlanHash);
        payload.put("predictiveCorpusHash", predictiveCorpusHash);
        payload.put("thresholdLockHash", thresholdLockHash);
        payload.put("minimumIndependentExpertReviews", minimumReviews);
        payload.put("revision", revision);
        payload.put("priorLabeledEvaluationHash", priorHash);
        payload.put("evidenceStatus", evidenceStatus.name());
        payload.put("decisions", decisions.stream().map(IntakeDecision::toMap).toList());
        payload.put("candidateStatuses", statuses.stream()
            .map(CandidateReviewStatus::toMap).toList());
        return payload;
    }

    private static Map<String, Object> reportPayload(
        String studyPlanHash,
        String predictiveCorpusHash,
        String thresholdLockHash,
        int minimumReviews,
        int revision,
        String priorHash,
        List<IntakeDecision> decisions,
        List<CandidateReviewStatus> statuses,
        EvidenceStatus evidenceStatus,
        String labeledEvaluationHash
    ) {
        Map<String, Object> payload = new LinkedHashMap<>(labeledPayload(
            studyPlanHash,
            predictiveCorpusHash,
            thresholdLockHash,
            minimumReviews,
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
        DEVELOPMENT_ONLY,
        MIXED_WITH_DEVELOPMENT_FIXTURES
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
        String independenceAttestationHash,
        IntakeOutcome outcome,
        List<String> blockers,
        String contentHash
    ) {
        public IntakeDecision {
            ReviewSubmission submission = new ReviewSubmission(
                reviewId,
                studyPlanHash,
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
            );
            reviewId = submission.reviewId();
            studyPlanHash = submission.studyPlanHash();
            caseId = submission.caseId();
            candidateId = submission.candidateId();
            reviewerHash = submission.reviewerHash();
            origin = submission.origin();
            blindReview = submission.blindReview();
            relevancePermille = submission.relevancePermille();
            confidencePermille = submission.confidencePermille();
            rationaleCodes = submission.rationaleCodes();
            qualificationEvidenceHash = submission.qualificationEvidenceHash();
            independenceAttestationHash = submission.independenceAttestationHash();
            Objects.requireNonNull(outcome, "outcome");
            blockers = blockers == null ? List.of() : blockers.stream()
                .distinct().sorted().toList();
            contentHash = InterestingnessIndependentReviewStudy.requireSha256(
                contentHash, "contentHash");
            String expected = InterestingnessIndependentReviewStudy.sha256(
                InterestingnessIndependentReviewStudy.canonicalBytes(decisionPayload(
                    submission,
                    outcome,
                    blockers
                )));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException("intake decision contentHash mismatch");
            }
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
        int minimumIndependentExpertReviews,
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
            if (minimumIndependentExpertReviews < 2) {
                throw new IllegalArgumentException(
                    "minimumIndependentExpertReviews must be at least 2");
            }
            validateRevision(revision, priorLabeledEvaluationHash);
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
            validateEvidenceStatus(evidenceStatus, decisions);
            validateCandidateStatusCounts(
                candidateStatuses, decisions, minimumIndependentExpertReviews);
            labeledEvaluationHash = InterestingnessIndependentReviewStudy.requireSha256(
                labeledEvaluationHash, "labeledEvaluationHash");
            String expectedLabeledHash = InterestingnessIndependentReviewStudy.sha256(
                InterestingnessIndependentReviewStudy.canonicalBytes(labeledPayload(
                    studyPlanHash,
                    predictiveCorpusHash,
                    thresholdLockHash,
                    minimumIndependentExpertReviews,
                    revision,
                    priorLabeledEvaluationHash,
                    decisions,
                    candidateStatuses,
                    evidenceStatus
                )));
            if (!expectedLabeledHash.equals(labeledEvaluationHash)) {
                throw new IllegalArgumentException("labeledEvaluationHash mismatch");
            }
            if (!"NOT_EVALUATED".equals(promotionStatus)
                    || !"NOT_EVALUATED".equals(publicEvidenceStatus)) {
                throw new IllegalArgumentException(
                    "review intake cannot perform promotion or public evidence");
            }
            contentHash = InterestingnessIndependentReviewStudy.requireSha256(
                contentHash, "contentHash");
            String expectedContentHash = InterestingnessIndependentReviewStudy.sha256(
                InterestingnessIndependentReviewStudy.canonicalBytes(reportPayload(
                    studyPlanHash,
                    predictiveCorpusHash,
                    thresholdLockHash,
                    minimumIndependentExpertReviews,
                    revision,
                    priorLabeledEvaluationHash,
                    decisions,
                    candidateStatuses,
                    evidenceStatus,
                    labeledEvaluationHash
                )));
            if (!expectedContentHash.equals(contentHash)) {
                throw new IllegalArgumentException("intake report contentHash mismatch");
            }
        }

        public long countedExpertReviews() {
            return decisions.stream()
                .filter(item -> item.outcome() == IntakeOutcome.COUNTED_EXPERT_REVIEW)
                .count();
        }

        public boolean eligibleForEmpiricalConsensus() {
            return evidenceStatus == EvidenceStatus.EXTERNAL_REVIEW_COLLECTION
                && !candidateStatuses.isEmpty()
                && candidateStatuses.stream().allMatch(
                    item -> item.status() == CandidateCollectionStatus.READY_FOR_CONSENSUS);
        }

        public String toCanonicalJson() {
            Map<String, Object> payload = reportPayload(
                studyPlanHash,
                predictiveCorpusHash,
                thresholdLockHash,
                minimumIndependentExpertReviews,
                revision,
                priorLabeledEvaluationHash,
                decisions,
                candidateStatuses,
                evidenceStatus,
                labeledEvaluationHash
            );
            payload.put("contentHash", contentHash);
            return InterestingnessIndependentReviewStudy.canonicalJson(payload);
        }
    }

    private static void validateRevision(int revision, String priorHash) {
        String normalized = priorHash == null ? "" : priorHash;
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (revision == 1 && !normalized.isEmpty()) {
            throw new IllegalArgumentException(
                "revision 1 must not declare a prior labeled evaluation");
        }
        if (revision > 1) {
            InterestingnessIndependentReviewStudy.requireSha256(
                normalized, "priorLabeledEvaluationHash");
        }
    }

    private static void validateEvidenceStatus(
        EvidenceStatus status,
        List<IntakeDecision> decisions
    ) {
        if (status != evidenceStatus(decisions)) {
            throw new IllegalArgumentException("evidenceStatus disagrees with decisions");
        }
    }

    private static void validateCandidateStatusCounts(
        List<CandidateReviewStatus> statuses,
        List<IntakeDecision> decisions,
        int minimumReviews
    ) {
        Set<String> uniqueCases = new java.util.HashSet<>();
        Set<String> uniqueCandidates = new java.util.HashSet<>();
        for (CandidateReviewStatus status : statuses) {
            if (!uniqueCases.add(status.caseId()) || !uniqueCandidates.add(status.candidateId())) {
                throw new IllegalArgumentException("duplicate candidate review status");
            }
            List<IntakeDecision> relevant = decisions.stream()
                .filter(item -> item.caseId().equals(status.caseId()))
                .filter(item -> item.candidateId().equals(status.candidateId()))
                .toList();
            int counted = (int) relevant.stream()
                .filter(item -> item.outcome() == IntakeOutcome.COUNTED_EXPERT_REVIEW).count();
            int blind = (int) relevant.stream()
                .filter(item -> item.outcome() == IntakeOutcome.COUNTED_EXPERT_REVIEW)
                .filter(IntakeDecision::blindReview).count();
            int development = (int) relevant.stream()
                .filter(item -> item.outcome() == IntakeOutcome.DEVELOPMENT_ONLY).count();
            int rejected = (int) relevant.stream()
                .filter(item -> item.outcome() == IntakeOutcome.REJECTED).count();
            if (status.countedExpertReviews() != counted
                    || status.blindExpertReviews() != blind
                    || status.developmentFixtureReviews() != development
                    || status.rejectedReviews() != rejected
                    || status.status() != expectedCollectionStatus(
                        counted, blind, development, minimumReviews)) {
                throw new IllegalArgumentException(
                    "candidate review status disagrees with intake decisions: "
                        + status.caseId());
            }
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
