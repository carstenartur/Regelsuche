package de.regelsuche.discovery.signal;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Ordered, distinct coefficient requests for the normalized forward DFT. */
public record FourierQuery(PeriodicSignal signal, List<Integer> frequencies) {
    public FourierQuery {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(frequencies, "frequencies");
        if (frequencies.isEmpty() || frequencies.size() > signal.length()) {
            throw new IllegalArgumentException("request between one and L distinct frequencies");
        }
        frequencies = List.copyOf(frequencies);
        if (new HashSet<>(frequencies).size() != frequencies.size()
                || frequencies.stream().anyMatch(j -> j < 0 || j >= signal.length())) {
            throw new IllegalArgumentException("frequencies must be distinct and in [0, L)");
        }
    }

    public String canonical() {
        return signal.canonical() + "|" + frequencies.stream().map(Object::toString)
            .collect(Collectors.joining(","));
    }
}
