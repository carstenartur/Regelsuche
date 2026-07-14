package de.regelsuche.mining;

import de.regelsuche.json.JsonWriter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Machine-readable decision over calibration, sensitivity and independent labels. */
public record InterestingnessAcceptanceReport(
    String schema,
    Decision decision,
    String thresholdProfileId,
    String selectedProfile,
    String predictiveDatasetHash,
    int calibrationCases,
    int testCases,
    int expertConsensusCases,
    int uncertainCasePermille,
    int calibrationAgreementPermille,
    int testAgreementPermille,
    int crossProfileOrderAgreementPermille,
    int selectionStabilityPermille,
    int topCandidateStabilityPermille,
    List<ThresholdCheck> checks,
    List<String> blockers,
    String calibrationHash,
    String sensitivityHash,
    String consensusHash,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.interestingness-acceptance/v1";

    public InterestingnessAcceptanceReport {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported acceptance schema");
        }
        decision = Objects.requireNonNull(decision, "decision");
        requireText(thresholdProfileId, "thresholdProfileId");
        selectedProfile = selectedProfile == null || selectedProfile.isBlank()
            ? "NOT_SELECTED"
            : selectedProfile;
        requireSha256(predictiveDatasetHash, "predictiveDatasetHash");
        requireNonNegative(calibrationCases, "calibrationCases");
        requireNonNegative(testCases, "testCases");
        requireNonNegative(expertConsensusCases, "expertConsensusCases");
        requirePermille(uncertainCasePermille, "uncertainCasePermille");
        requirePermille(calibrationAgreementPermille, "calibrationAgreementPermille");
        requirePermille(testAgreementPermille, "testAgreementPermille");
        requirePermille(
            crossProfileOrderAgreementPermille,
            "crossProfileOrderAgreementPermille");
        requirePermille(selectionStabilityPermille, "selectionStabilityPermille");
        requirePermille(topCandidateStabilityPermille, "topCandidateStabilityPermille");
        checks = checks == null
            ? List.of()
            : checks.stream().sorted(Comparator.comparing(ThresholdCheck::name)).toList();
        blockers = orderedStrings(blockers);
        requireSha256(calibrationHash, "calibrationHash");
        requireSha256(sensitivityHash, "sensitivityHash");
        requireSha256(consensusHash, "consensusHash");
        requireSha256(contentHash, "contentHash");
    }

    public String toCanonicalJson() {
        return new JsonWriter().beginObject()
            .property("schema", schema)
            .property("decision", decision.name())
            .property("thresholdProfileId", thresholdProfileId)
            .property("selectedProfile", selectedProfile)
            .property("predictiveDatasetHash", predictiveDatasetHash)
            .object("measured", object -> object
                .property("calibrationCases", calibrationCases)
                .property("testCases", testCases)
                .property("expertConsensusCases", expertConsensusCases)
                .property("uncertainCasePermille", uncertainCasePermille)
                .property("calibrationAgreementPermille", calibrationAgreementPermille)
                .property("testAgreementPermille", testAgreementPermille)
                .property(
                    "crossProfileOrderAgreementPermille",
                    crossProfileOrderAgreementPermille)
                .property("selectionStabilityPermille", selectionStabilityPermille)
                .property("topCandidateStabilityPermille", topCandidateStabilityPermille))
            .array("checks", array -> checks.forEach(check ->
                array.objectValue(object -> object
                    .property("name", check.name())
                    .property("comparison", check.comparison())
                    .property("measured", check.measured())
                    .property("threshold", check.threshold())
                    .property("passed", check.passed()))))
            .stringArray("blockers", blockers)
            .object("sourceHashes", object -> object
                .property("calibration", calibrationHash)
                .property("sensitivity", sensitivityHash)
                .property("consensus", consensusHash))
            .property("contentHash", contentHash)
            .endObject()
            .toString();
    }

    public enum Decision {
        ACCEPTED,
        REJECTED,
        DEVELOPMENT_ONLY
    }

    public record ThresholdCheck(
        String name,
        String comparison,
        int measured,
        int threshold,
        boolean passed
    ) {
        public ThresholdCheck {
            requireText(name, "name");
            requireText(comparison, "comparison");
            requireNonNegative(measured, "measured");
            requireNonNegative(threshold, "threshold");
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

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static void requirePermille(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " must be in [0,1000]");
        }
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }
}
