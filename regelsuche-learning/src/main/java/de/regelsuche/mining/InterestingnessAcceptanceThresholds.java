package de.regelsuche.mining;

/** Versioned acceptance thresholds for an empirical interestingness evaluation. */
public record InterestingnessAcceptanceThresholds(
    String profileId,
    int minimumCalibrationCases,
    int minimumTestCases,
    int minimumExpertConsensusCases,
    int minimumCalibrationAgreementPermille,
    int minimumTestAgreementPermille,
    int minimumCrossProfileOrderAgreementPermille,
    int minimumSelectionStabilityPermille,
    int minimumTopCandidateStabilityPermille,
    int maximumUncertainCasePermille
) {
    public static final String SCHEMA = "regelsuche.interestingness-acceptance-thresholds/v1";

    public static final InterestingnessAcceptanceThresholds DISCOVERY_RESEARCH_V1 =
        new InterestingnessAcceptanceThresholds(
            "discovery-research-v1",
            8,
            8,
            12,
            650,
            600,
            500,
            667,
            667,
            200);

    public InterestingnessAcceptanceThresholds {
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("profileId must not be blank");
        }
        requirePositive(minimumCalibrationCases, "minimumCalibrationCases");
        requirePositive(minimumTestCases, "minimumTestCases");
        requirePositive(minimumExpertConsensusCases, "minimumExpertConsensusCases");
        requirePermille(
            minimumCalibrationAgreementPermille,
            "minimumCalibrationAgreementPermille");
        requirePermille(minimumTestAgreementPermille, "minimumTestAgreementPermille");
        requirePermille(
            minimumCrossProfileOrderAgreementPermille,
            "minimumCrossProfileOrderAgreementPermille");
        requirePermille(
            minimumSelectionStabilityPermille,
            "minimumSelectionStabilityPermille");
        requirePermille(
            minimumTopCandidateStabilityPermille,
            "minimumTopCandidateStabilityPermille");
        requirePermille(maximumUncertainCasePermille, "maximumUncertainCasePermille");
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePermille(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " must be in [0,1000]");
        }
    }
}
