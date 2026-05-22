package de.regelsuche.didactic.analytics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Thread-safe in-memory {@link DidacticEventStore} (default for tests). */
public final class InMemoryDidacticEventStore implements DidacticEventStore {

    private final int maxEvents;
    private final List<DidacticEvent> events =
        Collections.synchronizedList(new ArrayList<>());

    public InMemoryDidacticEventStore() {
        this(Integer.MAX_VALUE);
    }

    public InMemoryDidacticEventStore(int maxEvents) {
        this.maxEvents = Math.max(1, maxEvents);
    }

    @Override
    public void record(DidacticEvent event) {
        events.add(Objects.requireNonNull(event, "event"));
        while (events.size() > maxEvents) {
            events.removeFirst();
        }
    }

    @Override
    public List<DidacticEvent> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    @Override
    public void clear() {
        events.clear();
    }
}
