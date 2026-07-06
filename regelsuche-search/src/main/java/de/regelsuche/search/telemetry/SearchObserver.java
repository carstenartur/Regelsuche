package de.regelsuche.search.telemetry;

/** Observer for runtime search telemetry. Implementations must not mutate search semantics. */
@FunctionalInterface
public interface SearchObserver {
    void onEvent(SearchEvent event);
}
