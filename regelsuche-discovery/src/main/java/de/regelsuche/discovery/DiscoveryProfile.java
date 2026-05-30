package de.regelsuche.discovery;

/** Named discovery configurations for deterministic rewrite, hypothesis, macro-reuse and orchestration runs. */
public enum DiscoveryProfile {
    PURE_REWRITE,
    HYPOTHESIS_ONLY,
    MACRO_REUSE_ONLY,
    HYPOTHESIS_AND_MACRO_REUSE,
    RESEARCH_DISCOVERY_PIPELINE
}
