package de.regelsuche.discovery;

/** Engine-level discovery configuration for local rewrites, hypothesis operators and macro reuse. */
public record DiscoveryEngineOptions(
    boolean enableHypothesisOperators,
    boolean enableMacroReuse,
    int maxHypothesisCandidatesPerOperator,
    int searchDepth,
    int searchBudget,
    DiscoveryProfile profile
) {
    public static final int DEFAULT_MAX_HYPOTHESIS_CANDIDATES_PER_OPERATOR = 6;
    public static final int DEFAULT_SEARCH_DEPTH = 4;
    public static final int DEFAULT_SEARCH_BUDGET = 160;

    public DiscoveryEngineOptions {
        profile = profile == null ? DiscoveryProfile.PURE_REWRITE : profile;
        maxHypothesisCandidatesPerOperator = Math.max(0, maxHypothesisCandidatesPerOperator);
        searchDepth = Math.max(0, searchDepth);
        searchBudget = Math.max(0, searchBudget);
    }

    public static DiscoveryEngineOptions forProfile(DiscoveryProfile profile) {
        DiscoveryProfile resolved = profile == null ? DiscoveryProfile.PURE_REWRITE : profile;
        return switch (resolved) {
            case PURE_REWRITE -> new DiscoveryEngineOptions(false, false,
                DEFAULT_MAX_HYPOTHESIS_CANDIDATES_PER_OPERATOR, DEFAULT_SEARCH_DEPTH, DEFAULT_SEARCH_BUDGET, resolved);
            case HYPOTHESIS_ONLY -> new DiscoveryEngineOptions(true, false,
                DEFAULT_MAX_HYPOTHESIS_CANDIDATES_PER_OPERATOR, DEFAULT_SEARCH_DEPTH, DEFAULT_SEARCH_BUDGET, resolved);
            case MACRO_REUSE_ONLY -> new DiscoveryEngineOptions(false, true,
                DEFAULT_MAX_HYPOTHESIS_CANDIDATES_PER_OPERATOR, DEFAULT_SEARCH_DEPTH, DEFAULT_SEARCH_BUDGET, resolved);
            case HYPOTHESIS_AND_MACRO_REUSE, RESEARCH_DISCOVERY_PIPELINE -> new DiscoveryEngineOptions(true, true,
                DEFAULT_MAX_HYPOTHESIS_CANDIDATES_PER_OPERATOR, DEFAULT_SEARCH_DEPTH, DEFAULT_SEARCH_BUDGET, resolved);
        };
    }
}
