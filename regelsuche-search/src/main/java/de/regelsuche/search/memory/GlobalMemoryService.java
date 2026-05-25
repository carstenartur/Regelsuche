package de.regelsuche.search.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Global mathematical memory" service — derives long-running statistics
 * from a {@link TranspositionTable} that has been kept across multiple
 * search sessions.
 *
 * <p>This is the additive, non-breaking core of the roadmap's PR 6:
 * the underlying {@link TranspositionEntry} schema is unchanged
 * (the universality score is derived from {@link
 * TranspositionEntry#visitCount() visitCount} and {@link
 * TranspositionEntry#reachedByRuleIds() reachedByRuleIds}), so existing
 * {@code transposition.json} files keep loading, and the three backends
 * (in-memory, JSON file, Neo4j) do not need a new column.</p>
 *
 * <p>Garbage collection of rarely seen patterns lives here too: a search
 * memory that grows forever stops being useful, so {@link #garbageCollect}
 * removes entries that are both rarely visited and old, freeing the index
 * for genuinely universal patterns.</p>
 */
public final class GlobalMemoryService {

    /**
     * Major version of the serialised JSON layout consumed by
     * {@link JsonFileTranspositionTable}. Bumped only when a field is
     * removed or its semantics change in a non-additive way. New optional
     * fields keep the schema version stable.
     */
    public static final int SCHEMA_VERSION = 1;

    private final TranspositionTable table;

    public GlobalMemoryService(TranspositionTable table) {
        if (table == null) {
            throw new IllegalArgumentException("table must not be null");
        }
        this.table = table;
    }

    /**
     * Universality score of {@code entry}: how universal this canonical
     * state has proven across all searches the table has ever seen. The
     * score is a weighted combination of:
     * <ul>
     *   <li>{@link TranspositionEntry#visitCount() visitCount} — how
     *       often the state was revisited,</li>
     *   <li>distinct {@link TranspositionEntry#reachedByRuleIds() rule
     *       combinations} that landed on it — a state reachable via many
     *       independent rewrite paths is more universal,</li>
     *   <li>and a recency factor — a state observed today is slightly
     *       more weighted than the same visit count a year ago, so the
     *       score adapts to the current direction of search work.</li>
     * </ul>
     */
    public int universalityScore(TranspositionEntry entry, Instant now) {
        if (entry == null) {
            return 0;
        }
        int diverseRulePaths = entry.reachedByRuleIds().size();
        int recency = recencyFactor(entry.lastSeen(), now);
        // Diversity dominates; raw visit count is capped so a single popular
        // syntactic variant cannot outweigh a state reached via many
        // independent rewrite paths (which is the truer signal of
        // universality across tasks).
        int boundedVisits = Math.min(20, entry.visitCount());
        return diverseRulePaths * 10 + boundedVisits + recency;
    }

    private int recencyFactor(Instant lastSeen, Instant now) {
        if (lastSeen == null || now == null) {
            return 0;
        }
        long days = Math.max(0, Duration.between(lastSeen, now).toDays());
        if (days == 0) {
            return 3;
        }
        if (days < 7) {
            return 2;
        }
        if (days < 30) {
            return 1;
        }
        return 0;
    }

    /**
     * Returns the top {@code limit} most universal entries, ordered by
     * descending {@link #universalityScore(TranspositionEntry, Instant)}.
     * Ties are broken by {@link TranspositionEntry#canonicalHash() hash}
     * so the order is deterministic across runs.
     */
    public List<TranspositionEntry> topUniversalPatterns(int limit, Instant now) {
        if (limit <= 0) {
            return List.of();
        }
        List<TranspositionEntry> snapshot = new ArrayList<>(table.entries());
        snapshot.sort(Comparator
            .<TranspositionEntry>comparingInt(e -> -universalityScore(e, now))
            .thenComparing(TranspositionEntry::canonicalHash));
        if (snapshot.size() > limit) {
            return List.copyOf(snapshot.subList(0, limit));
        }
        return List.copyOf(snapshot);
    }

    /**
     * Returns rule combinations that have proven cross-task useful: a
     * mapping from rule ID to the number of distinct canonical states that
     * combination has helped reach. Ordering: descending frequency, ties
     * broken alphabetically.
     */
    public Map<String, Integer> ruleCoverage() {
        Map<String, Integer> coverage = new HashMap<>();
        for (TranspositionEntry entry : table.entries()) {
            for (String ruleId : entry.reachedByRuleIds()) {
                coverage.merge(ruleId, 1, Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(coverage.entrySet());
        sorted.sort(Comparator
            .<Map.Entry<String, Integer>>comparingInt(e -> -e.getValue())
            .thenComparing(Map.Entry::getKey));
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : sorted) {
            ordered.put(e.getKey(), e.getValue());
        }
        return ordered;
    }

    /**
     * Remove entries that are both rarely visited and old enough to be
     * unlikely to recur in the current workload.
     *
     * <p>Concretely, an entry is dropped iff
     * {@code visitCount < minVisits AND lastSeen older than retainOlderThan}.
     * The constraint must hold for both axes — a state visited rarely but
     * very recently is kept (still relevant), and a popular long-tail
     * state is kept too (proven universality).</p>
     *
     * @return number of entries removed.
     */
    public int garbageCollect(int minVisits, Duration retainOlderThan, Instant now) {
        if (minVisits <= 0 || retainOlderThan == null || retainOlderThan.isNegative() || now == null) {
            return 0;
        }
        Instant cutoff = now.minus(retainOlderThan);
        List<TranspositionEntry> snapshot = new ArrayList<>(table.entries());
        int removed = 0;
        for (TranspositionEntry entry : snapshot) {
            boolean rare = entry.visitCount() < minVisits;
            boolean old = entry.lastSeen() != null && entry.lastSeen().isBefore(cutoff);
            if (rare && old) {
                if (table instanceof InMemoryTranspositionTable inMemory) {
                    inMemory.remove(entry.canonicalHash());
                    removed++;
                }
            }
        }
        return removed;
    }
}
