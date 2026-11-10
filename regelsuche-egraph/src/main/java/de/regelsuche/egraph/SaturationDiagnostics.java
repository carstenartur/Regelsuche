package de.regelsuche.egraph;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Bounded per-run observations; aggregate counts survive event truncation. */
public record SaturationDiagnostics(Map<Outcome, Long> counts, List<Event> events, long droppedEvents) {
    public static final int MAX_EVENTS = 128;
    public static final int MAX_MESSAGE_LENGTH = 512;
    public enum Outcome { NON_APPLICABLE, UNRESOLVED_CONDITIONS, NOT_EQUIVALENCE_PRESERVING, RULE_FAILURE }
    public enum Stage { ELIGIBILITY, EXTRACTION, MATCHES, ASSUMPTIONS, APPLY, INSERTION }
    public record Event(String ruleId, EClassId eclass, Outcome outcome, Stage stage,
                        String exceptionType, String message) {}

    public SaturationDiagnostics {
        counts = Map.copyOf(counts);
        events = List.copyOf(events);
    }
    public long count(Outcome outcome) { return counts.getOrDefault(outcome, 0L); }
    public static SaturationDiagnostics empty() { return new SaturationDiagnostics(Map.of(), List.of(), 0); }

    static final class Collector {
        private final Map<Outcome, Long> counts = new EnumMap<>(Outcome.class);
        private final List<Event> events = new ArrayList<>();
        private long dropped;
        void record(String rule, EClassId source, Outcome outcome, Stage stage, RuntimeException failure) {
            counts.merge(outcome, 1L, Long::sum);
            // Ordinary misses are counted, not retained: they must not crowd out failures.
            if (outcome == Outcome.NON_APPLICABLE) return;
            if (events.size() >= MAX_EVENTS) { dropped++; return; }
            String message = failure == null ? "" : String.valueOf(failure.getMessage());
            if (message.length() > MAX_MESSAGE_LENGTH) message = message.substring(0, MAX_MESSAGE_LENGTH);
            events.add(new Event(rule, source, outcome, stage,
                failure == null ? "" : failure.getClass().getName(), message));
        }
        SaturationDiagnostics snapshot() { return new SaturationDiagnostics(counts, events, dropped); }
        boolean hasFailures() { return counts.containsKey(Outcome.RULE_FAILURE); }
    }
}
