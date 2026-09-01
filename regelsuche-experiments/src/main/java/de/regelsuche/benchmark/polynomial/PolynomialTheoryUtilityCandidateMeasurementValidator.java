package de.regelsuche.benchmark.polynomial;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Cross-value consistency checks for candidate measurement companions. */
final class PolynomialTheoryUtilityCandidateMeasurementValidator {
    private PolynomialTheoryUtilityCandidateMeasurementValidator() {
    }

    static void validate(
        PolynomialTheoryUtilityCandidateResult result,
        PolynomialTheoryUtilityExecutionProfile profile,
        int sourceAstNodeCount,
        List<PolynomialTheoryUtilityTransitionTrace> traces,
        List<PolynomialTheoryUtilityFactorizationAttempt> attempts,
        List<PolynomialTheoryUtilityCacheEvent> events
    ) {
        requireTransitionTraces(
            result,
            sourceAstNodeCount,
            traces
        );
        requireFactorizationAttempts(result, profile, attempts);
        requireCacheEvents(result, profile, events);
    }

    private static void requireTransitionTraces(
        PolynomialTheoryUtilityCandidateResult result,
        int sourceAstNodeCount,
        List<PolynomialTheoryUtilityTransitionTrace> traces
    ) {
        if (traces.size() != result.transitions().size()) {
            throw new IllegalArgumentException(
                "candidate result must retain one trace per transition"
            );
        }
        Set<String> identities = new HashSet<>();
        int primitiveExpansion = 0;
        for (int index = 0; index < traces.size(); index++) {
            var trace = Objects.requireNonNull(traces.get(index), "trace");
            trace.validateAgainst(
                index,
                result.transitions().get(index),
                sourceAstNodeCount
            );
            if (!identities.add(trace.traceId())) {
                throw new IllegalArgumentException(
                    "candidate measurements repeat a transition trace"
                );
            }
            primitiveExpansion = Math.addExact(
                primitiveExpansion,
                trace.primitiveExpansionLength()
            );
        }
        if (primitiveExpansion > result.work().primitiveWork()) {
            throw new IllegalArgumentException(
                "candidate trace primitive work exceeds the result"
            );
        }
    }

    private static void requireFactorizationAttempts(
        PolynomialTheoryUtilityCandidateResult result,
        PolynomialTheoryUtilityExecutionProfile profile,
        List<PolynomialTheoryUtilityFactorizationAttempt> attempts
    ) {
        Set<String> identities = new HashSet<>();
        Map<String, Integer> producers = new HashMap<>();
        for (int index = 0; index < attempts.size(); index++) {
            var attempt = Objects.requireNonNull(
                attempts.get(index),
                "factorizationAttempt"
            );
            attempt.validateAgainst(index, result, profile);
            if (!identities.add(attempt.attemptId())) {
                throw new IllegalArgumentException(
                    "candidate measurements repeat a factorization attempt"
                );
            }
            if (attempt.producedTransition()) {
                producers.merge(
                    attempt.transitionId(),
                    1,
                    Math::addExact
                );
            }
        }

        boolean factorizationEnabled = !"DISABLED".equals(
            profile.factorizationMode()
        );
        boolean workRetained = result.work().factorizationWork() > 0L;
        if ((!factorizationEnabled && !attempts.isEmpty())
                || (attempts.isEmpty() == workRetained)) {
            throw new IllegalArgumentException(
                "factorization observations differ from retained work"
            );
        }

        for (var transition : result.transitions()) {
            int expectedProducers = transition.cacheDisposition()
                    == PolynomialTheoryUtilityTransitionOutcome
                        .CacheDisposition.CACHE_HIT_REPLAYED
                ? 0
                : 1;
            if (producers.getOrDefault(transition.transitionId(), 0)
                    != expectedProducers) {
                throw new IllegalArgumentException(
                    "result transition has incomplete factorization lineage"
                );
            }
        }
    }

    private static void requireCacheEvents(
        PolynomialTheoryUtilityCandidateResult result,
        PolynomialTheoryUtilityExecutionProfile profile,
        List<PolynomialTheoryUtilityCacheEvent> events
    ) {
        Set<String> identities = new HashSet<>();
        Map<String, List<PolynomialTheoryUtilityCacheEvent>> byTransition =
            new LinkedHashMap<>();
        Map<String, Integer> transitionOrder = transitionOrder(result);
        int lastTransitionIndex = -1;

        for (int index = 0; index < events.size(); index++) {
            var event = Objects.requireNonNull(events.get(index), "cacheEvent");
            event.validateAgainst(index, result, profile);
            if (!identities.add(event.eventId())) {
                throw new IllegalArgumentException(
                    "candidate measurements repeat a cache event"
                );
            }
            if (!event.transitionBound()) {
                requireUnboundLookup(event);
                continue;
            }
            int current = transitionOrder.get(event.transitionId());
            if (current < lastTransitionIndex) {
                throw new IllegalArgumentException(
                    "cache transition events are not in result order"
                );
            }
            lastTransitionIndex = current;
            byTransition.computeIfAbsent(
                event.transitionId(),
                ignored -> new ArrayList<>()
            ).add(event);
        }

        boolean cacheEnabled = "READ_WRITE".equals(profile.cacheMode());
        if (!cacheEnabled && !events.isEmpty()) {
            throw new IllegalArgumentException(
                "cache-disabled profile retained cache events"
            );
        }
        requireCacheWork(result.work(), events);
        for (var transition : result.transitions()) {
            requireCacheSequence(
                transition,
                byTransition.getOrDefault(
                    transition.transitionId(),
                    List.of()
                )
            );
        }
    }

    private static Map<String, Integer> transitionOrder(
        PolynomialTheoryUtilityCandidateResult result
    ) {
        Map<String, Integer> order = new HashMap<>();
        for (int index = 0; index < result.transitions().size(); index++) {
            order.put(result.transitions().get(index).transitionId(), index);
        }
        return order;
    }

    private static void requireUnboundLookup(
        PolynomialTheoryUtilityCacheEvent event
    ) {
        if (event.kind() != PolynomialTheoryUtilityCacheEvent.Kind.LOOKUP_HIT
                && event.kind()
                    != PolynomialTheoryUtilityCacheEvent.Kind.LOOKUP_MISS) {
            throw new IllegalArgumentException(
                "cache mutation or replay lacks transition lineage"
            );
        }
    }

    private static void requireCacheWork(
        PolynomialTheoryUtilityWorkBreakdown work,
        List<PolynomialTheoryUtilityCacheEvent> events
    ) {
        long hits = count(
            events,
            PolynomialTheoryUtilityCacheEvent.Kind.LOOKUP_HIT
        );
        long misses = count(
            events,
            PolynomialTheoryUtilityCacheEvent.Kind.LOOKUP_MISS
        );
        long insertions = count(
            events,
            PolynomialTheoryUtilityCacheEvent.Kind.INSERTION
        );
        long evictions = count(
            events,
            PolynomialTheoryUtilityCacheEvent.Kind.EVICTION
        );
        long replays = count(
            events,
            PolynomialTheoryUtilityCacheEvent.Kind.REPLAY
        );
        if ((work.cacheLookupWork() > 0L) != (hits + misses > 0L)
                || (work.cacheInsertionWork() > 0L) != (insertions > 0L)
                || (work.cacheEvictionWork() > 0L) != (evictions > 0L)
                || (work.cacheReplayWork() > 0L) != (replays > 0L)) {
            throw new IllegalArgumentException(
                "cache event counts differ from retained cache work"
            );
        }
    }

    private static long count(
        List<PolynomialTheoryUtilityCacheEvent> events,
        PolynomialTheoryUtilityCacheEvent.Kind kind
    ) {
        return events.stream().filter(value -> value.kind() == kind).count();
    }

    private static void requireCacheSequence(
        PolynomialTheoryUtilityTransitionOutcome transition,
        List<PolynomialTheoryUtilityCacheEvent> events
    ) {
        List<PolynomialTheoryUtilityCacheEvent.Kind> actual = events.stream()
            .map(PolynomialTheoryUtilityCacheEvent::kind)
            .toList();
        List<PolynomialTheoryUtilityCacheEvent.Kind> expected = switch (
            transition.cacheDisposition()
        ) {
            case CACHE_DISABLED -> List.of();
            case CACHE_HIT_REPLAYED -> List.of(
                PolynomialTheoryUtilityCacheEvent.Kind.LOOKUP_HIT,
                PolynomialTheoryUtilityCacheEvent.Kind.REPLAY
            );
            case CACHE_MISS_INSERTED -> "NONE".equals(
                    transition.evictedCacheEntryId()
                )
                ? List.of(
                    PolynomialTheoryUtilityCacheEvent.Kind.LOOKUP_MISS,
                    PolynomialTheoryUtilityCacheEvent.Kind.INSERTION
                )
                : List.of(
                    PolynomialTheoryUtilityCacheEvent.Kind.LOOKUP_MISS,
                    PolynomialTheoryUtilityCacheEvent.Kind.INSERTION,
                    PolynomialTheoryUtilityCacheEvent.Kind.EVICTION
                );
        };
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                "cache event sequence differs from transition disposition"
            );
        }
    }
}
