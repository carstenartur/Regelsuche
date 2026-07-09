package de.regelsuche.search.telemetry;

/** Stable event types emitted by a search strategy while it explores a transformation space. */
public enum SearchEventType {
    SEARCH_STARTED,
    STATE_DEQUEUED,
    STATE_VISITED,
    STATE_PRUNED_DUPLICATE,
    STATE_PRUNED_TRANSPOSITION,
    STATE_PRUNED_DEPTH,
    STATE_PRUNED_BUDGET,
    STATE_EXPANDED,
    TRANSFORMATION_GENERATED,
    STATE_ENQUEUED,
    SEARCH_FINISHED
}
