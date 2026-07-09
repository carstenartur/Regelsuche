package de.regelsuche.search.telemetry;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Fan-out observer that forwards each event to all configured observers in declaration order. */
public final class CompositeSearchObserver implements SearchObserver {
    private final List<SearchObserver> observers;

    public CompositeSearchObserver(List<SearchObserver> observers) {
        if (observers == null || observers.isEmpty()) {
            this.observers = List.of();
            return;
        }
        this.observers = observers.stream()
            .map(observer -> Objects.requireNonNull(observer, "observer"))
            .toList();
    }

    public static CompositeSearchObserver of(SearchObserver... observers) {
        return new CompositeSearchObserver(observers == null ? List.of() : Arrays.asList(observers));
    }

    @Override
    public void onEvent(SearchEvent event) {
        for (SearchObserver observer : observers) {
            observer.onEvent(event);
        }
    }
}
