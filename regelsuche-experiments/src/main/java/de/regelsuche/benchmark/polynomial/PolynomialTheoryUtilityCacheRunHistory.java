package de.regelsuche.benchmark.polynomial;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Empty-at-start cache lineage validation across the measured rows of one run. */
public final class PolynomialTheoryUtilityCacheRunHistory {
    private final String runId;
    private final LinkedHashMap<String, Lineage> entries = new LinkedHashMap<>();
    private final Set<String> inputs = new HashSet<>();

    public PolynomialTheoryUtilityCacheRunHistory(String runId) {
        this.runId = Objects.requireNonNull(runId, "runId");
    }

    /** Validates a complete measured row before advancing the retained prefix. */
    public void accept(PolynomialTheoryUtilityMeasuredCandidate measured) {
        Objects.requireNonNull(measured, "measured");
        var result = measured.result();
        if (!runId.equals(result.input().runId()) || !PolynomialTheoryUtilityDerivedCacheAdapter.PROFILE_ID.equals(result.input().profileId())
                || result.observations() == null || inputs.contains(result.input().inputId())) {
            throw new IllegalArgumentException("cache history received another run, revision or duplicate row");
        }
        var proposed = new LinkedHashMap<>(entries);
        var events = measured.measurements().cacheEvents();
        for (var occurrence : result.observations().occurrences()) {
            applyOccurrence(measured, occurrence, events, proposed);
        }
        entries.clear();
        entries.putAll(proposed);
        inputs.add(result.input().inputId());
    }

    private static void applyOccurrence(PolynomialTheoryUtilityMeasuredCandidate measured,
            PolynomialTheoryUtilityExecutionObservations.Occurrence occurrence,
            List<PolynomialTheoryUtilityCacheEvent> events, LinkedHashMap<String, Lineage> proposed) {
        var own = events.stream().filter(event -> occurrence.cacheEventIds().contains(event.eventId())).toList();
        if (own.isEmpty()) return;
        var lookup = own.getFirst();
        if (lookup.kind() == PolynomialTheoryUtilityCacheEvent.Kind.LOOKUP_HIT) {
            validateReplay(measured, occurrence, lookup, proposed);
        } else if (own.size() > 1) {
            retainInsertion(measured, occurrence, own, proposed);
        }
    }

    private static void validateReplay(PolynomialTheoryUtilityMeasuredCandidate measured,
            PolynomialTheoryUtilityExecutionObservations.Occurrence occurrence,
            PolynomialTheoryUtilityCacheEvent lookup, LinkedHashMap<String, Lineage> proposed) {
        var original = proposed.get(lookup.entryId());
        if (original == null) throw new IllegalArgumentException("cache hit lacks an earlier insertion in this run");
        if (!occurrence.transitionId().equals("NONE")
                && !original.equals(lineage(measured, occurrence.transitionId()))) {
            throw new IllegalArgumentException("cache replay differs from its retained primitive derivation");
        }
    }

    private static void retainInsertion(PolynomialTheoryUtilityMeasuredCandidate measured,
            PolynomialTheoryUtilityExecutionObservations.Occurrence occurrence,
            List<PolynomialTheoryUtilityCacheEvent> own, LinkedHashMap<String, Lineage> proposed) {
        var insertion = own.get(1);
        if (insertion.kind() != PolynomialTheoryUtilityCacheEvent.Kind.INSERTION
                || proposed.containsKey(insertion.entryId())) {
            throw new IllegalArgumentException("cache insertion repeats a retained entry");
        }
        if (proposed.size() == PolynomialTheoryUtilityExecutionPlan.CACHE_CAPACITY) {
            String oldest = proposed.keySet().iterator().next();
            if (own.size() != 3 || own.get(2).kind() != PolynomialTheoryUtilityCacheEvent.Kind.EVICTION
                    || !own.get(2).entryId().equals(oldest)) {
                throw new IllegalArgumentException("cache eviction differs from FIFO insertion order");
            }
            proposed.remove(oldest);
        } else if (own.size() != 2) {
            throw new IllegalArgumentException("cache evicted before reaching the frozen capacity");
        }
        proposed.put(insertion.entryId(), lineage(measured, occurrence.transitionId()));
    }

    private static Lineage lineage(PolynomialTheoryUtilityMeasuredCandidate measured, String transitionId) {
        var trace = measured.measurements().transitionTraces().stream()
            .filter(value -> value.transition().transitionId().equals(transitionId)).findFirst().orElseThrow();
        var transition = trace.transition();
        return new Lineage(transition.sourceOccurrenceExpression(), transition.transformedOccurrenceExpression(),
            transition.transformationId(), transition.backendId(), transition.sourceEvidenceHash(),
            trace.primitiveSteps().stream().map(value -> List.of(value.ruleId(), value.evidenceHash())).toList(),
            trace.normalizedAssumptions());
    }

    private record Lineage(String source, String replacement, String transformationId, String backendId,
            String sourceEvidenceHash, List<List<String>> primitiveSteps, List<String> assumptions) { }
}
