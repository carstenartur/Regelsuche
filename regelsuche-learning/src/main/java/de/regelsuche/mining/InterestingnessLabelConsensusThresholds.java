package de.regelsuche.mining;

/** Predeclared thresholds for independent relevance-label consensus. */
public record InterestingnessLabelConsensusThresholds(
    String profileId,
    int minimumIndependentExpertReviews,
    int minimumAgreementPermille,
    int maximumLabelSpread,
    int minimumBlindReviewPermille
) {
    public static final String SCHEMA = "regelsuche.interestingness-label-consensus-thresholds/v1";

    public static final InterestingnessLabelConsensusThresholds DISCOVERY_RESEARCH_V1 =
        new InterestingnessLabelConsensusThresholds(
            "discovery-research-v1",
            3,
            667,
            1,
            667);

    public InterestingnessLabelConsensusThresholds {
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("profileId must not be blank");
        }
        if (minimumIndependentExpertReviews < 1) {
            throw new IllegalArgumentException(
                "minimumIndependentExpertReviews must be positive");
        }
        requirePermille(minimumAgreementPermille, "minimumAgreementPermille");
        if (maximumLabelSpread < 0 || maximumLabelSpread > 3) {
            throw new IllegalArgumentException("maximumLabelSpread must be in [0,3]");
        }
        requirePermille(minimumBlindReviewPermille, "minimumBlindReviewPermille");
    }

    private static void requirePermille(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " must be in [0,1000]");
        }
    }
}
