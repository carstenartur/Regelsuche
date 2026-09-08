package de.regelsuche.search.program;

/** Stable event vocabulary emitted by the rewrite-program interpreter. */
public enum RewriteTraceEventType {
    NODE_ENTERED,
    NODE_EXITED,
    SOURCE_CANDIDATE,
    EXACT_THEORY_CANDIDATE,
    WORK_BUDGET_REJECTED,
    CANDIDATE_REJECTED,
    ALTERNATIVE_SELECTED,
    ALTERNATIVE_SKIPPED,
    ITERATION_COMPLETED,
    CANDIDATES_PRUNED
}
