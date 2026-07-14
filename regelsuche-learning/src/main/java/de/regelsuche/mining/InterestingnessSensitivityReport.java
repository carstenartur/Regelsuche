package de.regelsuche.mining;

import de.regelsuche.json.JsonWriter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Deterministic sensitivity report for profile and calibration-case perturbations. */
public record InterestingnessSensitivityReport(
    String schema,
    SensitivityStatus status,
    String predictiveDatasetHash,
    String baselineSelectedProfile,
    int crossProfileTestOrderAgreementPermille,
    int evaluatedLeaveOneOutScenarios,
    int selectionStabilityPermille,
    int topCandidateStabilityPermille,
    List<String> unstableTestCandidateIds,
    List<LeaveOneOutScenario> leaveOneOutScenarios,
    List<String> blockers,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.interestingness-sensitivity/v1";

    public InterestingnessSensitivityReport {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported sensitivity schema");
        }
        status = Objects.requireNonNull(status, "status");
        requireSha256(predictiveDatasetHash, "predictiveDatasetHash");
        baselineSelectedProfile = baselineSelectedProfile == null
                || baselineSelectedProfile.isBlank()
            ? "NOT_SELECTED"
            : baselineSelectedProfile;
        requirePermille(
            crossProfileTestOrderAgreementPermille,
            "crossProfileTestOrderAgreementPermille");
        requireNonNegative(evaluatedLeaveOneOutScenarios, "evaluatedLeaveOneOutScenarios");
        requirePermille(selectionStabilityPermille, "selectionStabilityPermille");
        requirePermille(topCandidateStabilityPermille, "topCandidateStabilityPermille");
        unstableTestCandidateIds = orderedStrings(unstableTestCandidateIds);
        leaveOneOutScenarios = leaveOneOutScenarios == null
            ? List.of()
            : leaveOneOutScenarios.stream()
                .sorted(Comparator.comparing(LeaveOneOutScenario::omittedCalibrationCaseId))
                .toList();
        blockers = orderedStrings(blockers);
        requireSha256(contentHash, "contentHash");
    }

    public String toCanonicalJson() {
        return new JsonWriter().beginObject()
            .property("schema", schema)
            .property("status", status.name())
            .property("predictiveDatasetHash", predictiveDatasetHash)
            .property("baselineSelectedProfile", baselineSelectedProfile)
            .property(
                "crossProfileTestOrderAgreementPermille",
                crossProfileTestOrderAgreementPermille)
            .property("evaluatedLeaveOneOutScenarios", evaluatedLeaveOneOutScenarios)
            .property("selectionStabilityPermille", selectionStabilityPermille)
            .property("topCandidateStabilityPermille", topCandidateStabilityPermille)
            .stringArray("unstableTestCandidateIds", unstableTestCandidateIds)
            .array("leaveOneOutScenarios", array -> leaveOneOutScenarios.forEach(scenario ->
                array.objectValue(object -> object
                    .property(
                        "omittedCalibrationCaseId",
                        scenario.omittedCalibrationCaseId())
                    .property("status", scenario.status().name())
                    .property("selectedProfile", scenario.selectedProfile())
                    .property("topTestCandidateId", scenario.topTestCandidateId())
                    .property("selectionMatchesBaseline", scenario.selectionMatchesBaseline())
                    .property("topCandidateMatchesBaseline", scenario.topCandidateMatchesBaseline())
                    .stringArray("blockers", scenario.blockers()))))
            .stringArray("blockers", blockers)
            .property("contentHash", contentHash)
            .endObject()
            .toString();
    }

    public enum SensitivityStatus {
        EVALUATED,
        BASELINE_REJECTED
    }

    public record LeaveOneOutScenario(
        String omittedCalibrationCaseId,
        InterestingnessCalibrationReport.CalibrationStatus status,
        String selectedProfile,
        String topTestCandidateId,
        boolean selectionMatchesBaseline,
        boolean topCandidateMatchesBaseline,
        List<String> blockers
    ) {
        public LeaveOneOutScenario {
            requireText(omittedCalibrationCaseId, "omittedCalibrationCaseId");
            status = Objects.requireNonNull(status, "status");
            selectedProfile = selectedProfile == null || selectedProfile.isBlank()
                ? "NOT_SELECTED"
                : selectedProfile;
            topTestCandidateId = topTestCandidateId == null || topTestCandidateId.isBlank()
                ? "NOT_AVAILABLE"
                : topTestCandidateId;
            blockers = orderedStrings(blockers);
        }
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
