package de.regelsuche.search.telemetry;

/** No-op observer used when callers do not opt into runtime telemetry. */
public enum NoOpSearchObserver implements SearchObserver {
    INSTANCE;

    @Override
    public void onEvent(SearchEvent event) {
        // Intentionally empty.
    }
}
