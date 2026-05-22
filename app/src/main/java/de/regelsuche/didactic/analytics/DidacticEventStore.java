package de.regelsuche.didactic.analytics;

import java.util.List;

/**
 * Append-only sink for {@link DidacticEvent}s. Implementations decide
 * whether events are kept in memory or persisted to disk; the analytics
 * surface only relies on {@link #record(DidacticEvent)} and
 * {@link #events()}.
 */
public interface DidacticEventStore {

    /** Append a single event. */
    void record(DidacticEvent event);

    /** @return all recorded events in insertion order. */
    List<DidacticEvent> events();

    /** Drop all recorded events. */
    void clear();
}
