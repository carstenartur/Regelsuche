package de.regelsuche.mining;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.InterestingnessCalibrationCase.RelevanceLabel;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Deterministic consensus report for post-hoc interestingness labels. */
public record InterestingnessLabelConsensusReport(
    String schema,
    ReportStatus status,
    String reviewRoundId,
    String thresholdProfileId,
    int expectedCases,
    int reviewedCases,
    int consensusCases,
    int uncertainCases,
    int insufficientCases,
    int developmentOnlyCases,
    List<CaseConsensus> cases,
    List<String> blockers,
    String reviewDatasetHash,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.interestingness-label-consensus/v1";

    public InterestingnessLabelConsensusReport {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported consensus schema");
        }
        status = Objects.requireNonNull(status, "status");
        requireText(reviewRoundId, "reviewRoundId");
        requireText(thresholdProfileId, "thresholdProfileId");
        requireNonNegative(expectedCases, "expectedCases");
        requireNonNegative(reviewedCases, "reviewedCases");
        requireNonNegative(consensusCases, "consensusCases");
        requireNonNegative(uncertainCases, "uncertainCases");
        requireNonNegative(insufficientCases, "insufficientCases");
        requireNonNegative(developmentOnlyCases, "developmentOnlyCases");
        cases = cases == null
            ? List.of()
            : cases.stream().sorted(Comparator.comparing(CaseConsensus::caseId)).toList();
        blockers = blockers == null
            ? List.of()
            : blockers.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .toList();
        requireSha256(reviewDatasetHash, "reviewDatasetHash");
        requireSha256(contentHash, "contentHash");
    }

    public String toCanonicalJson() {
        return new JsonWriter().beginObject()
            .property("schema", schema)
            .property("status", status.name())
            .property("reviewRoundId", reviewRoundId)
            .property("thresholdProfileId", thresholdProfileId)
            .property("expectedCases", expectedCases)
            .property("reviewedCases", reviewedCases)
            .property("consensusCases", consensusCases)
            .property("uncertainCases", uncertainCases)
            .property("insufficientCases", insufficientCases)
            .property("developmentOnlyCases", developmentOnlyCases)
            .array("cases", array -> cases.forEach(item ->
                array.objectValue(object -> object
                    .property("caseId", item.caseId())
                    .property("status", item.status().name())
                    .property("consensusLabel", item.consensusLabel().name())
                    .property("reviewCount", item.reviewCount())
                    .property("independentReviewerCount", item.independentReviewerCount())
                    .property("expertReviewCount", item.expertReviewCount())
                    .property("controlAssignmentCount", item.controlAssignmentCount())
                    .property("testFixtureCount", item.testFixtureCount())
                    .property("blindReviewCount", item.blindReviewCount())
                    .property("blindReviewPermille", item.blindReviewPermille())
                    .property("agreementPermille", item.agreementPermille())
                    .property("labelSpread", item.labelSpread())
                    .property("averageConfidencePermille", item.averageConfidencePermille())
                    .array("labelCounts", counts -> item.labelCounts().forEach(count ->
                        counts.objectValue(value -> value
                            .property("label", count.label().name())
                            .property("count", count.count()))))
                    .stringArray("reviewIds", item.reviewIds())
                    .stringArray("blockers", item.blockers()))))
            .stringArray("blockers", blockers)
            .property("reviewDatasetHash", reviewDatasetHash)
            .property("contentHash", contentHash)
            .endObject()
            .toString();
    }

    public enum ReportStatus {
        EXPERT_EVIDENCE,
        DEVELOPMENT_ONLY,
        INCOMPLETE
    }

    public enum ConsensusStatus {
        CONSENSUS,
        UNCERTAIN,
        INSUFFICIENT_REVIEWS,
        DEVELOPMENT_ONLY
    }

    public record CaseConsensus(
        String caseId,
        ConsensusStatus status,
        RelevanceLabel consensusLabel,
        int reviewCount,
        int independentReviewerCount,
        int expertReviewCount,
        int controlAssignmentCount,
        int testFixtureCount,
        int blindReviewCount,
        int blindReviewPermille,
        int agreementPermille,
        int labelSpread,
        int averageConfidencePermille,
        List<LabelCount> labelCounts,
        List<String> reviewIds,
        List<String> blockers
    ) {
        public CaseConsensus {
            requireText(caseId, "caseId");
            status = Objects.requireNonNull(status, "status");
            consensusLabel = Objects.requireNonNull(consensusLabel, "consensusLabel");
            requireNonNegative(reviewCount, "reviewCount");
            requireNonNegative(independentReviewerCount, "independentReviewerCount");
            requireNonNegative(expertReviewCount, "expertReviewCount");
            requireNonNegative(controlAssignmentCount, "controlAssignmentCount");
            requireNonNegative(testFixtureCount, "testFixtureCount");
            requireNonNegative(blindReviewCount, "blindReviewCount");
            requirePermille(blindReviewPermille, "blindReviewPermille");
            requirePermille(agreementPermille, "agreementPermille");
            if (labelSpread < 0 || labelSpread > 3) {
                throw new IllegalArgumentException("labelSpread must be in [0,3]");
            }
            requirePermille(averageConfidencePermille, "averageConfidencePermille");
            labelCounts = labelCounts == null
                ? List.of()
                : labelCounts.stream()
                    .sorted(Comparator.comparing(count -> count.label().name()))
                    .toList();
            reviewIds = orderedStrings(reviewIds);
            blockers = orderedStrings(blockers);
        }
    }

    public record LabelCount(RelevanceLabel label, int count) {
        public LabelCount {
            label = Objects.requireNonNull(label, "label");
            requireNonNegative(count, "count");
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
