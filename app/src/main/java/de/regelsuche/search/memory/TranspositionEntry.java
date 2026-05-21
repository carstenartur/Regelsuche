package de.regelsuche.search.memory;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One entry in the {@link TranspositionTable}: the best path a search ever
 * reached the state with, plus light-weight statistics used to decide whether
 * a re-visit is worth following or should be pruned.
 *
 * <p>{@code reachedByRuleIds} accumulates the union of all distinct atomic
 * rule combinations that have landed on this canonical state. A new path
 * with a so-far unseen rule combination is allowed to re-visit even if its
 * score is not strictly better, so the rule miner gets diverse examples.</p>
 *
 * @see TranspositionTable
 */
public record TranspositionEntry(
    String canonicalHash,
    String canonicalExpression,
    int bestScore,
    int minDepthSeen,
    String bestKnownPathId,
    Set<String> reachedByRuleIds,
    int visitCount,
    Instant firstSeen,
    Instant lastSeen
) {
    public TranspositionEntry {
        if (canonicalHash == null || canonicalHash.isBlank()) {
            throw new IllegalArgumentException("canonicalHash must not be blank");
        }
        if (canonicalExpression == null) {
            canonicalExpression = "";
        }
        if (minDepthSeen < 0) {
            throw new IllegalArgumentException("minDepthSeen must not be negative");
        }
        if (visitCount < 1) {
            throw new IllegalArgumentException("visitCount must be >= 1");
        }
        reachedByRuleIds = reachedByRuleIds == null
            ? Set.of()
            : Collections.unmodifiableSet(new LinkedHashSet<>(reachedByRuleIds));
        if (firstSeen == null) {
            firstSeen = Instant.EPOCH;
        }
        if (lastSeen == null) {
            lastSeen = firstSeen;
        }
        if (bestKnownPathId == null) {
            bestKnownPathId = "";
        }
    }

    /** Returns a copy reflecting a new visit by {@code path}. */
    public TranspositionEntry merge(
        int newScore,
        int newDepth,
        String newPathId,
        Set<String> newRuleIds,
        Instant now
    ) {
        Set<String> merged = new LinkedHashSet<>(reachedByRuleIds);
        if (newRuleIds != null) {
            merged.addAll(newRuleIds);
        }
        int updatedBestScore = Math.min(bestScore, newScore);
        int updatedMinDepth = Math.min(minDepthSeen, newDepth);
        String updatedBestPath = (newScore < bestScore || (newScore == bestScore && newDepth < minDepthSeen))
            ? (newPathId == null ? bestKnownPathId : newPathId)
            : bestKnownPathId;
        return new TranspositionEntry(
            canonicalHash,
            canonicalExpression,
            updatedBestScore,
            updatedMinDepth,
            updatedBestPath,
            merged,
            visitCount + 1,
            firstSeen,
            now == null ? lastSeen : now
        );
    }
}
