package de.regelsuche.didactic.analytics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Thread-safe in-memory {@link DidacticEventStore} (default for tests). */
public final class InMemoryDidacticEventStore implements DidacticEventStore {

    private final List<DidacticEvent> events =
        Collections.synchronizedList(new ArrayList<>());

    @Override
    public void record(DidacticEvent event) {
        events.add(Objects.requireNonNull(event, "event"));
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
