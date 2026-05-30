package de.regelsuche.transform;

/** Named discovery configurations for deterministic rewrite, hypothesis, and macro runs. */
public enum DiscoveryProfile {
    PURE_REWRITE,
    HYPOTHESIS_ONLY,
    MACRO_REUSE_ONLY,
    FULL_DISCOVERY
}
