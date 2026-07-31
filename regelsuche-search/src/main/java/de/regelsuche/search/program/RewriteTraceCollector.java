package de.regelsuche.search.program;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Thread-safe in-memory trace sink for tests, debugging and UI adapters. */
public final class RewriteTraceCollector implements RewriteTraceSink {
    private final ConcurrentLinkedQueue<RewriteTraceEvent> events =
        new ConcurrentLinkedQueue<>();

    @Override
    public void accept(RewriteTraceEvent event) {
        events.add(event);
    }

    public List<RewriteTraceEvent> events() {
        return List.copyOf(events);
    }

    public void clear() {
        events.clear();
    }
}
