package de.regelsuche.mining;

import de.regelsuche.json.JsonWriter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Deterministic profile-calibration and held-out evaluation artifact. */
public record InterestingnessCalibrationReport(
    String schema,
    CalibrationStatus status,
    String selectedProfile,
    String predictiveDatasetHash,
    String labeledEvaluationHash,
    List<String> calibrationFamilies,
    List<String> testFamilies,
    List<ProfileMetric> profileMetrics,
    List<CaseResult> calibrationResults,
    List<CaseResult> testResults,
    int calibrationAgreementPermille,
    int testAgreementPermille,
    List<ParetoPoint> testParetoFront,
    List<String> blockers,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.interestingness-calibration/v1";

    public InterestingnessCalibrationReport {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported calibration schema");
        }
        status = Objects.requireNonNull(status, "status");
        selectedProfile = selectedProfile == null || selectedProfile.isBlank()
            ? "NOT_SELECTED"
            : selectedProfile;
        requireSha256(predictiveDatasetHash, "predictiveDatasetHash");
        requireSha256(labeledEvaluationHash, "labeledEvaluationHash");
        calibrationFamilies = orderedStrings(calibrationFamilies);
        testFamilies = orderedStrings(testFamilies);
        profileMetrics = profileMetrics == null
            ? List.of()
            : profileMetrics.stream()
                .sorted(Comparator.comparing(metric -> metric.profile().name()))
                .toList();
        calibrationResults = orderedResults(calibrationResults);
        testResults = orderedResults(testResults);
        requirePermille(calibrationAgreementPermille, "calibrationAgreementPermille");
        requirePermille(testAgreementPermille, "testAgreementPermille");
        testParetoFront = testParetoFront == null
            ? List.of()
            : testParetoFront.stream()
                .sorted(Comparator.comparing(ParetoPoint::caseId))
                .toList();
        blockers = orderedStrings(blockers);
        requireSha256(contentHash, "contentHash");
    }

    public String toCanonicalJson() {
        return new JsonWriter().beginObject()
            .property("schema", schema)
            .property("status", status.name())
            .property("selectedProfile", selectedProfile)
            .property("predictiveDatasetHash", predictiveDatasetHash)
            .property("labeledEvaluationHash", labeledEvaluationHash)
            .stringArray("calibrationFamilies", calibrationFamilies)
            .stringArray("testFamilies", testFamilies)
            .array("profileMetrics", array -> profileMetrics.forEach(metric ->
                array.objectValue(object -> object
                    .property("profile", metric.profile().name())
                    .property("calibrationAgreementPermille", metric.calibrationAgreementPermille())
                    .property("rankableComplete", metric.rankableComplete())
                    .property("rankableIncomplete", metric.rankableIncomplete())
                    .property("blocked", metric.blocked()))))
            .array("calibrationResults", array -> calibrationResults.forEach(result ->
                array.objectValue(object -> writeResult(object, result))))
            .array("testResults", array -> testResults.forEach(result ->
                array.objectValue(object -> writeResult(object, result))))
            .property("calibrationAgreementPermille", calibrationAgreementPermille)
            .property("testAgreementPermille", testAgreementPermille)
            .array("testParetoFront", array -> testParetoFront.forEach(point ->
                array.objectValue(object -> object
                    .property("caseId", point.caseId())
                    .property("paretoOptimal", point.paretoOptimal())
                    .stringArray("dominatedBy", point.dominatedBy()))))
            .stringArray("blockers", blockers)
            .property("contentHash", contentHash)
            .endObject()
            .toString();
    }

    private static void writeResult(JsonWriter json, CaseResult result) {
        InterestingnessAssessment assessment = result.assessment();
        json.property("caseId", result.caseId())
            .property("structuralFamily", result.structuralFamily())
            .property("split", result.split().name())
            .property("relevanceLabel", result.relevanceLabel().name())
            .property("eligibility", assessment.eligibility().name())
            .property("totalPermille", assessment.totalPermille())
            .property("unresolvedRiskPenaltyPermille", assessment.unresolvedRiskPenaltyPermille())
            .property("controlPenaltyPermille", assessment.controlPenaltyPermille())
            .property("assessmentHash", assessment.contentHash())
            .array("contributions", array -> assessment.contributions().forEach(contribution ->
                array.objectValue(object -> object
                    .property("name", contribution.name())
                    .property("rawPermille", contribution.rawPermille())
                    .property("weightPermille", contribution.weightPermille())
                    .property("weightedPermille", contribution.weightedPermille()))));
    }

    public enum CalibrationStatus {
        EVALUATED,
        SPLIT_REJECTED
    }

    public record ProfileMetric(
        InterestingnessProfile profile,
        int calibrationAgreementPermille,
        int rankableComplete,
        int rankableIncomplete,
        int blocked
    ) {
        public ProfileMetric {
            profile = Objects.requireNonNull(profile, "profile");
            requirePermille(calibrationAgreementPermille, "calibrationAgreementPermille");
            requireNonNegative(rankableComplete, "rankableComplete");
            requireNonNegative(rankableIncomplete, "rankableIncomplete");
            requireNonNegative(blocked, "blocked");
        }
    }

    public record CaseResult(
        String caseId,
        String structuralFamily,
        InterestingnessCalibrationCase.Split split,
        InterestingnessCalibrationCase.RelevanceLabel relevanceLabel,
        InterestingnessAssessment assessment
    ) {
        public CaseResult {
            requireText(caseId, "caseId");
            requireText(structuralFamily, "structuralFamily");
            split = Objects.requireNonNull(split, "split");
            relevanceLabel = Objects.requireNonNull(relevanceLabel, "relevanceLabel");
            assessment = Objects.requireNonNull(assessment, "assessment");
        }
    }

    public record ParetoPoint(String caseId, boolean paretoOptimal, List<String> dominatedBy) {
        public ParetoPoint {
            requireText(caseId, "caseId");
            dominatedBy = orderedStrings(dominatedBy);
        }
    }

    private static List<CaseResult> orderedResults(List<CaseResult> results) {
        return results == null
            ? List.of()
            : results.stream().sorted(Comparator.comparing(CaseResult::caseId)).toList();
    }

    private static List<String> orderedStrings(List<String> values) {
        return values == null
            ? List.of()
            : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }

    private static void requirePermille(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " must be in [0,1000]");
        }
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
