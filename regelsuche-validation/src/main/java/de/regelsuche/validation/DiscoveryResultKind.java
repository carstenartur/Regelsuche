package de.regelsuche.validation;

/** Search-result state for discovery outcomes; evidence/capabilities are tracked separately. */
public enum DiscoveryResultKind {
    NO_CANDIDATE,
    HYPOTHESIS_ONLY,
    BRIDGE_FOUND,
    TRANSFORMED,
    FALSE_POSITIVE
}
