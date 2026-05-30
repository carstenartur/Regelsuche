package de.regelsuche.transform;

/** Immutable configuration for discovery engine composition and bounded candidate generation. */
public record DiscoveryOptions(
    boolean enableHypothesisOperators,
    boolean enableMacroLearning,
    boolean enableMacroReuse,
    boolean enableGeneratedGallery,
    int maxHypothesisCandidatesPerOperator,
    int searchDepth,
    int searchBudget,
    DiscoveryProfile profile
) {
    public static final int DEFAULT_MAX_HYPOTHESIS_CANDIDATES_PER_OPERATOR = 6;
    public static final int DEFAULT_SEARCH_DEPTH = 4;
    public static final int DEFAULT_SEARCH_BUDGET = 160;

    public DiscoveryOptions {
        profile = profile == null ? DiscoveryProfile.PURE_REWRITE : profile;
        maxHypothesisCandidatesPerOperator = Math.max(0, maxHypothesisCandidatesPerOperator);
        searchDepth = Math.max(0, searchDepth);
        searchBudget = Math.max(0, searchBudget);
    }

    public static DiscoveryOptions forProfile(DiscoveryProfile profile) {
        DiscoveryProfile resolved = profile == null ? DiscoveryProfile.PURE_REWRITE : profile;
        return switch (resolved) {
            case PURE_REWRITE -> new DiscoveryOptions(false, false, false, false,
                DEFAULT_MAX_HYPOTHESIS_CANDIDATES_PER_OPERATOR, DEFAULT_SEARCH_DEPTH, DEFAULT_SEARCH_BUDGET, resolved);
            case HYPOTHESIS_ONLY -> new DiscoveryOptions(true, false, false, false,
                DEFAULT_MAX_HYPOTHESIS_CANDIDATES_PER_OPERATOR, DEFAULT_SEARCH_DEPTH, DEFAULT_SEARCH_BUDGET, resolved);
            case MACRO_REUSE_ONLY -> new DiscoveryOptions(false, false, true, false,
                DEFAULT_MAX_HYPOTHESIS_CANDIDATES_PER_OPERATOR, DEFAULT_SEARCH_DEPTH, DEFAULT_SEARCH_BUDGET, resolved);
            case FULL_DISCOVERY -> new DiscoveryOptions(true, true, true, true,
                DEFAULT_MAX_HYPOTHESIS_CANDIDATES_PER_OPERATOR, DEFAULT_SEARCH_DEPTH, DEFAULT_SEARCH_BUDGET, resolved);
        };
    }

    public static DiscoveryOptions fullDiscovery() {
        return forProfile(DiscoveryProfile.FULL_DISCOVERY);
    }
}
