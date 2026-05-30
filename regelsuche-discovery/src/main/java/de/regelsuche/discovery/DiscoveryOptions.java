package de.regelsuche.discovery;

/** Aggregate configuration for discovery engine composition, orchestration learning and generated gallery output. */
public record DiscoveryOptions(
    DiscoveryProfile profile,
    DiscoveryEngineOptions engine,
    DiscoveryLearningOptions learning,
    boolean enableGeneratedGallery
) {
    public DiscoveryOptions {
        profile = profile == null ? DiscoveryProfile.PURE_REWRITE : profile;
        engine = engine == null ? DiscoveryEngineOptions.forProfile(profile) : engine;
        learning = learning == null ? DiscoveryLearningOptions.disabled() : learning;
    }

    public static DiscoveryOptions forProfile(DiscoveryProfile profile) {
        DiscoveryProfile resolved = profile == null ? DiscoveryProfile.PURE_REWRITE : profile;
        DiscoveryLearningOptions learning = resolved == DiscoveryProfile.RESEARCH_DISCOVERY_PIPELINE
            ? DiscoveryLearningOptions.researchDefaults()
            : DiscoveryLearningOptions.disabled();
        return new DiscoveryOptions(resolved, DiscoveryEngineOptions.forProfile(resolved), learning,
            resolved == DiscoveryProfile.RESEARCH_DISCOVERY_PIPELINE);
    }

    public static DiscoveryOptions researchDiscoveryPipeline() {
        return forProfile(DiscoveryProfile.RESEARCH_DISCOVERY_PIPELINE);
    }
}
