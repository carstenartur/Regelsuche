package de.regelsuche.mining;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.mining.InterestingnessAcceptanceGate.Thresholds;
import de.regelsuche.mining.InterestingnessCalibrationCorpus.CorpusSplit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Pre-registers an independently reviewed interestingness study before labels
 * are collected.
 *
 * <p>The plan binds candidate artifacts, assessments, structural splits,
 * reviewer qualification rules, blinded instructions, scales, rationale codes
 * and acceptance thresholds. It deliberately contains no relevance labels,
 * reviewer identities or TEST outcomes.</p>
 */
public final class InterestingnessIndependentReviewStudy {
    public static final String SCHEMA = "regelsuche.independent-review-study-plan/v1";
    public static final int MIN_CASES_PER_SPLIT = 2;

    private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{1,127}");
    private static final List<InterestingnessProfile> REQUIRED_PROFILES = List.of(
        InterestingnessProfile.THEORY_DISCOVERY,
        InterestingnessProfile.SEARCH_REUSE
    );
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    public StudyPlan freeze(
        String studyId,
        List<CandidateCase> cases,
        ReviewProtocol protocol,
        Thresholds thresholds,
        List<ExposureRecord> historicalExposures
    ) {
        String normalizedStudyId = requireIdentifier(studyId, "studyId");
        List<CandidateCase> orderedCases = orderedCases(cases);
        List<ExposureRecord> orderedExposures = orderedExposures(historicalExposures);
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(thresholds, "thresholds");

        validateCases(orderedCases);
        validateSplitIsolation(orderedCases);
        validateFreshTestCases(orderedCases, orderedExposures);

        String predictiveCorpusHash = sha256(canonicalBytes(predictivePayload(orderedCases)));
        String thresholdLockHash = sha256(canonicalBytes(thresholdPayload(thresholds)));
        Map<String, Object> payload = planPayload(
            normalizedStudyId,
            orderedCases,
            protocol,
            thresholds,
            thresholdLockHash,
            orderedExposures,
            predictiveCorpusHash
        );
        String contentHash = sha256(canonicalBytes(payload));
        return new StudyPlan(
            SCHEMA,
            normalizedStudyId,
            StudyStatus.FROZEN_BEFORE_REVIEW,
            orderedCases,
            protocol,
            thresholds,
            thresholdLockHash,
            orderedExposures,
            predictiveCorpusHash,
            "NOT_COLLECTED",
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash
        );
    }

    private static List<CandidateCase> orderedCases(List<CandidateCase> values) {
        Objects.requireNonNull(values, "cases");
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("cases must not contain null");
        }
        return values.stream()
            .sorted(Comparator.comparing((CandidateCase item) -> item.split().name())
                .thenComparing(CandidateCase::caseId))
            .toList();
    }

    private static List<ExposureRecord> orderedExposures(List<ExposureRecord> values) {
        return values == null ? List.of() : values.stream()
            .map(item -> Objects.requireNonNull(item, "exposure"))
            .sorted(Comparator.comparing(ExposureRecord::candidateArtifactHash)
                .thenComparing(item -> item.kind().name())
                .thenComparing(ExposureRecord::sourceStudyHash))
            .toList();
    }

    private static void validateCases(List<CandidateCase> cases) {
        Set<String> caseIds = new HashSet<>();
        Set<String> candidateIds = new HashSet<>();
        Set<String> artifactHashes = new HashSet<>();
        for (CandidateCase item : cases) {
            if (!caseIds.add(item.caseId())) {
                throw new IllegalArgumentException("duplicate caseId: " + item.caseId());
            }
            if (!candidateIds.add(item.candidateId())) {
                throw new IllegalArgumentException(
                    "candidate appears more than once: " + item.candidateId());
            }
            if (!artifactHashes.add(item.candidateArtifactHash())) {
                throw new IllegalArgumentException(
                    "candidate artifact appears more than once: " + item.candidateArtifactHash());
            }
        }
        for (CorpusSplit split : CorpusSplit.values()) {
            long count = cases.stream().filter(item -> item.split() == split).count();
            if (count < MIN_CASES_PER_SPLIT) {
                throw new IllegalArgumentException(
                    split.name() + " requires at least " + MIN_CASES_PER_SPLIT + " cases");
            }
        }
    }

    private static void validateSplitIsolation(List<CandidateCase> cases) {
        Set<String> calibrationFamilies = splitValues(cases, CorpusSplit.CALIBRATION, true);
        Set<String> testFamilies = splitValues(cases, CorpusSplit.TEST, true);
        Set<String> familyOverlap = new TreeSet<>(calibrationFamilies);
        familyOverlap.retainAll(testFamilies);
        if (!familyOverlap.isEmpty()) {
            throw new IllegalArgumentException(
                "candidate families cross calibration/test: " + familyOverlap);
        }

        Set<String> calibrationSignatures = splitValues(cases, CorpusSplit.CALIBRATION, false);
        Set<String> testSignatures = splitValues(cases, CorpusSplit.TEST, false);
        Set<String> signatureOverlap = new TreeSet<>(calibrationSignatures);
        signatureOverlap.retainAll(testSignatures);
        if (!signatureOverlap.isEmpty()) {
            throw new IllegalArgumentException(
                "structural signatures cross calibration/test: " + signatureOverlap);
        }
    }

    private static Set<String> splitValues(
        List<CandidateCase> cases,
        CorpusSplit split,
        boolean family
    ) {
        Set<String> values = new TreeSet<>();
        cases.stream()
            .filter(item -> item.split() == split)
            .map(item -> family ? item.candidateFamily() : item.structuralSignatureHash())
            .forEach(values::add);
        return values;
    }

    private static void validateFreshTestCases(
        List<CandidateCase> cases,
        List<ExposureRecord> exposures
    ) {
        Set<String> exposed = exposures.stream()
            .map(ExposureRecord::candidateArtifactHash)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        List<String> reused = cases.stream()
            .filter(item -> item.split() == CorpusSplit.TEST)
            .filter(item -> exposed.contains(item.candidateArtifactHash()))
            .map(CandidateCase::caseId)
            .sorted()
            .toList();
        if (!reused.isEmpty()) {
            throw new IllegalArgumentException(
                "previously exposed artifacts cannot count as fresh TEST: " + reused);
        }
    }

    private static Map<String, Object> predictivePayload(List<CandidateCase> cases) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "regelsuche.independent-review-predictive-corpus/v1");
        payload.put("minimumCasesPerSplit", MIN_CASES_PER_SPLIT);
        payload.put("cases", cases.stream().map(CandidateCase::toMap).toList());
        return payload;
    }

    private static Map<String, Object> thresholdPayload(Thresholds thresholds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("minimumTestCases", thresholds.minimumTestCases());
        payload.put("minimumCalibrationAgreementPermille",
            thresholds.minimumCalibrationAgreementPermille());
        payload.put("minimumTestAgreementPermille",
            thresholds.minimumTestAgreementPermille());
        payload.put("minimumProfileOrderAgreementPermille",
            thresholds.minimumProfileOrderAgreementPermille());
        payload.put("minimumLeaveOneOutStabilityPermille",
            thresholds.minimumLeaveOneOutStabilityPermille());
        payload.put("requireStableTopCandidate", thresholds.requireStableTopCandidate());
        payload.put("requireNonEmptyParetoFront", thresholds.requireNonEmptyParetoFront());
        return payload;
    }

    private static Map<String, Object> planPayload(
        String studyId,
        List<CandidateCase> cases,
        ReviewProtocol protocol,
        Thresholds thresholds,
        String thresholdLockHash,
        List<ExposureRecord> exposures,
        String predictiveCorpusHash
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", SCHEMA);
        payload.put("studyId", studyId);
        payload.put("status", StudyStatus.FROZEN_BEFORE_REVIEW.name());
        payload.put("cases", cases.stream().map(CandidateCase::toMap).toList());
        payload.put("reviewProtocol", protocol.toMap());
        payload.put("acceptanceThresholds", thresholdPayload(thresholds));
        payload.put("thresholdLockHash", thresholdLockHash);
        payload.put("historicalExposures", exposures.stream().map(ExposureRecord::toMap).toList());
        payload.put("predictiveCorpusHash", predictiveCorpusHash);
        payload.put("labelsStatus", "NOT_COLLECTED");
        payload.put("promotionStatus", "NOT_EVALUATED");
        payload.put("publicEvidenceStatus", "NOT_EVALUATED");
        return payload;
    }

    public enum StudyStatus {
        FROZEN_BEFORE_REVIEW
    }

    public enum ExposureKind {
        PRIOR_EXPERT_REVIEW,
        PUBLIC_PRESENTATION,
        DEVELOPMENT_EVALUATION,
        PRIOR_HELD_OUT_USE
    }

    public record CandidateCase(
        String caseId,
        String candidateId,
        CorpusSplit split,
        String candidateFamily,
        String structuralSignatureHash,
        String assessmentContentHash,
        String candidateArtifactHash,
        String blindedPresentationHash
    ) {
        public CandidateCase {
            caseId = requireIdentifier(caseId, "caseId");
            candidateId = requireIdentifier(candidateId, "candidateId");
            Objects.requireNonNull(split, "split");
            candidateFamily = requireIdentifier(candidateFamily, "candidateFamily");
            structuralSignatureHash = requireSha256(
                structuralSignatureHash, "structuralSignatureHash");
            assessmentContentHash = requireSha256(
                assessmentContentHash, "assessmentContentHash");
            candidateArtifactHash = requireSha256(
                candidateArtifactHash, "candidateArtifactHash");
            blindedPresentationHash = requireSha256(
                blindedPresentationHash, "blindedPresentationHash");
        }

        Map<String, Object> toMap() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("caseId", caseId);
            payload.put("candidateId", candidateId);
            payload.put("split", split.name());
            payload.put("candidateFamily", candidateFamily);
            payload.put("structuralSignatureHash", structuralSignatureHash);
            payload.put("assessmentContentHash", assessmentContentHash);
            payload.put("candidateArtifactHash", candidateArtifactHash);
            payload.put("blindedPresentationHash", blindedPresentationHash);
            return payload;
        }
    }

    public record ReviewProtocol(
        int minimumIndependentExpertReviews,
        boolean blindReviewRequired,
        boolean oneReviewPerReviewerAndCandidate,
        boolean reviewerIdentitiesStoredAsHashesOnly,
        boolean testLabelsExcludedFromSelection,
        String reviewerHashSaltCommitment,
        String reviewerInstructionsHash,
        List<String> qualificationCriteria,
        List<Integer> relevanceScalePermille,
        List<Integer> confidenceScalePermille,
        List<String> rationaleCodes,
        List<InterestingnessProfile> profiles,
        String correctionPolicy
    ) {
        public static final String CORRECTION_POLICY =
            "NEW_LABELED_EVALUATION_IDENTITY";

        public ReviewProtocol {
            if (minimumIndependentExpertReviews < 2) {
                throw new IllegalArgumentException(
                    "minimumIndependentExpertReviews must be at least 2");
            }
            if (!blindReviewRequired
                    || !oneReviewPerReviewerAndCandidate
                    || !reviewerIdentitiesStoredAsHashesOnly
                    || !testLabelsExcludedFromSelection) {
                throw new IllegalArgumentException(
                    "v1 independent review safeguards must all be enabled");
            }
            reviewerHashSaltCommitment = requireSha256(
                reviewerHashSaltCommitment, "reviewerHashSaltCommitment");
            reviewerInstructionsHash = requireSha256(
                reviewerInstructionsHash, "reviewerInstructionsHash");
            qualificationCriteria = orderedIdentifiers(
                qualificationCriteria, "qualificationCriteria", 1);
            relevanceScalePermille = orderedScale(
                relevanceScalePermille, "relevanceScalePermille");
            confidenceScalePermille = orderedScale(
                confidenceScalePermille, "confidenceScalePermille");
            rationaleCodes = orderedIdentifiers(rationaleCodes, "rationaleCodes", 2);
            profiles = profiles == null ? List.of() : profiles.stream()
                .map(item -> Objects.requireNonNull(item, "profile"))
                .distinct()
                .sorted()
                .toList();
            if (!profiles.equals(REQUIRED_PROFILES.stream().sorted().toList())) {
                throw new IllegalArgumentException(
                    "both unchanged v1 interestingness profiles are required");
            }
            if (!CORRECTION_POLICY.equals(correctionPolicy)) {
                throw new IllegalArgumentException("unsupported correctionPolicy");
            }
        }

        public static ReviewProtocol conservative(
            String saltCommitment,
            String instructionsHash,
            List<String> qualificationCriteria,
            List<String> rationaleCodes
        ) {
            return new ReviewProtocol(
                2,
                true,
                true,
                true,
                true,
                saltCommitment,
                instructionsHash,
                qualificationCriteria,
                List.of(0, 250, 500, 750, 1000),
                List.of(0, 250, 500, 750, 1000),
                rationaleCodes,
                REQUIRED_PROFILES,
                CORRECTION_POLICY
            );
        }

        Map<String, Object> toMap() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("minimumIndependentExpertReviews", minimumIndependentExpertReviews);
            payload.put("blindReviewRequired", blindReviewRequired);
            payload.put("oneReviewPerReviewerAndCandidate", oneReviewPerReviewerAndCandidate);
            payload.put("reviewerIdentitiesStoredAsHashesOnly",
                reviewerIdentitiesStoredAsHashesOnly);
            payload.put("testLabelsExcludedFromSelection", testLabelsExcludedFromSelection);
            payload.put("reviewerHashSaltCommitment", reviewerHashSaltCommitment);
            payload.put("reviewerInstructionsHash", reviewerInstructionsHash);
            payload.put("qualificationCriteria", qualificationCriteria);
            payload.put("relevanceScalePermille", relevanceScalePermille);
            payload.put("confidenceScalePermille", confidenceScalePermille);
            payload.put("rationaleCodes", rationaleCodes);
            payload.put("profiles", profiles.stream().map(Enum::name).toList());
            payload.put("correctionPolicy", correctionPolicy);
            return payload;
        }
    }

    public record ExposureRecord(
        String candidateArtifactHash,
        ExposureKind kind,
        String sourceStudyHash
    ) {
        public ExposureRecord {
            candidateArtifactHash = requireSha256(
                candidateArtifactHash, "candidateArtifactHash");
            Objects.requireNonNull(kind, "kind");
            sourceStudyHash = requireSha256(sourceStudyHash, "sourceStudyHash");
        }

        Map<String, Object> toMap() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("candidateArtifactHash", candidateArtifactHash);
            payload.put("kind", kind.name());
            payload.put("sourceStudyHash", sourceStudyHash);
            return payload;
        }
    }

    public record StudyPlan(
        String schema,
        String studyId,
        StudyStatus status,
        List<CandidateCase> cases,
        ReviewProtocol reviewProtocol,
        Thresholds acceptanceThresholds,
        String thresholdLockHash,
        List<ExposureRecord> historicalExposures,
        String predictiveCorpusHash,
        String labelsStatus,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public StudyPlan {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported independent review study schema");
            }
            studyId = requireIdentifier(studyId, "studyId");
            Objects.requireNonNull(status, "status");
            cases = orderedCases(cases);
            Objects.requireNonNull(reviewProtocol, "reviewProtocol");
            Objects.requireNonNull(acceptanceThresholds, "acceptanceThresholds");
            thresholdLockHash = requireSha256(thresholdLockHash, "thresholdLockHash");
            historicalExposures = orderedExposures(historicalExposures);
            predictiveCorpusHash = requireSha256(
                predictiveCorpusHash, "predictiveCorpusHash");
            if (!"NOT_COLLECTED".equals(labelsStatus)
                    || !"NOT_EVALUATED".equals(promotionStatus)
                    || !"NOT_EVALUATED".equals(publicEvidenceStatus)) {
                throw new IllegalArgumentException(
                    "frozen plan cannot contain labels, promotion or public evidence");
            }
            contentHash = requireSha256(contentHash, "contentHash");

            validateCases(cases);
            validateSplitIsolation(cases);
            validateFreshTestCases(cases, historicalExposures);
            String expectedPredictiveHash = sha256(
                canonicalBytes(predictivePayload(cases)));
            if (!expectedPredictiveHash.equals(predictiveCorpusHash)) {
                throw new IllegalArgumentException("predictiveCorpusHash mismatch");
            }
            String expectedThresholdHash = sha256(
                canonicalBytes(thresholdPayload(acceptanceThresholds)));
            if (!expectedThresholdHash.equals(thresholdLockHash)) {
                throw new IllegalArgumentException("thresholdLockHash mismatch");
            }
            String expectedContentHash = sha256(canonicalBytes(planPayload(
                studyId,
                cases,
                reviewProtocol,
                acceptanceThresholds,
                thresholdLockHash,
                historicalExposures,
                predictiveCorpusHash
            )));
            if (!expectedContentHash.equals(contentHash)) {
                throw new IllegalArgumentException("study contentHash mismatch");
            }
        }

        public CandidateCase requireCase(String caseId) {
            return cases.stream()
                .filter(item -> item.caseId().equals(caseId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                    "unknown review case: " + caseId));
        }

        public String toCanonicalJson() {
            Map<String, Object> payload = planPayload(
                studyId,
                cases,
                reviewProtocol,
                acceptanceThresholds,
                thresholdLockHash,
                historicalExposures,
                predictiveCorpusHash
            );
            payload.put("contentHash", contentHash);
            return canonicalJson(payload);
        }
    }

    static byte[] canonicalBytes(Map<String, Object> payload) {
        try {
            return JSON.writeValueAsBytes(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode independent review study", exception);
        }
    }

    static String canonicalJson(Map<String, Object> payload) {
        try {
            return JSON.writeValueAsString(payload) + "\n";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode independent review study", exception);
        }
    }

    static String sha256(byte[] value) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    static String requireSha256(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be SHA-256");
        }
        return value;
    }

    static String requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a valid identifier");
        }
        return value;
    }

    private static List<String> orderedIdentifiers(
        List<String> values,
        String field,
        int minimum
    ) {
        if (values == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        List<String> ordered = values.stream()
            .map(value -> requireIdentifier(value, field))
            .distinct()
            .sorted()
            .toList();
        if (ordered.size() < minimum) {
            throw new IllegalArgumentException(
                field + " requires at least " + minimum + " values");
        }
        return ordered;
    }

    private static List<Integer> orderedScale(List<Integer> values, String field) {
        if (values == null || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(field + " must not contain null");
        }
        List<Integer> ordered = values.stream().distinct().sorted().toList();
        if (ordered.size() < 3 || ordered.getFirst() != 0 || ordered.getLast() != 1000) {
            throw new IllegalArgumentException(
                field + " must contain at least three values including 0 and 1000");
        }
        if (ordered.stream().anyMatch(value -> value < 0 || value > 1000)) {
            throw new IllegalArgumentException(field + " values must be in [0,1000]");
        }
        return ordered;
    }
}
