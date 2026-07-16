package de.regelsuche.solver.portfolio;

/** Why one backend appears in the complete portfolio trace. */
public enum AttemptDisposition {
    FILTERED_UNSUPPORTED,
    FILTERED_IRRELEVANT,
    CACHE_HIT,
    EXECUTED,
    SKIPPED_UNAVAILABLE,
    SKIPPED_BUDGET,
    TIMED_OUT,
    CANCELLED,
    FAILED
}
