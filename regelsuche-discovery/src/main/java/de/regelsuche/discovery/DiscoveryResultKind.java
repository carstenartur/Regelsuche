package de.regelsuche.discovery;

/** Classification levels for hidden-structure and macro-discovery outcomes. */
public enum DiscoveryResultKind {
    NO_CANDIDATE,
    HYPOTHESIS_ONLY,
    BRIDGE_FOUND,
    FACTORED,
    SIMPLIFIED,
    MACRO_LEARNED,
    MACRO_REUSED,
    FALSE_POSITIVE;

    public boolean discovered() {
        return this != NO_CANDIDATE && this != FALSE_POSITIVE;
    }
}
