package de.regelsuche.discovery;

/** Orchestration-level options for optional macro learning and promotion. */
public record DiscoveryLearningOptions(
    boolean enableMacroLearning,
    int minExamples,
    double minConfidence,
    boolean validateGeneratedInstantiations,
    boolean enablePromotion
) {
    public static final int DEFAULT_MIN_EXAMPLES = 2;
    public static final double DEFAULT_MIN_CONFIDENCE = 0.95;

    public DiscoveryLearningOptions {
        minExamples = Math.max(0, minExamples);
        minConfidence = Math.max(0.0, Math.min(1.0, minConfidence));
    }

    public static DiscoveryLearningOptions disabled() {
        return new DiscoveryLearningOptions(false, DEFAULT_MIN_EXAMPLES, DEFAULT_MIN_CONFIDENCE, false, false);
    }

    public static DiscoveryLearningOptions researchDefaults() {
        return new DiscoveryLearningOptions(true, DEFAULT_MIN_EXAMPLES, DEFAULT_MIN_CONFIDENCE, true, true);
    }
}
