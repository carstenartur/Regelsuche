package de.regelsuche.search.memory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory {@link TranspositionTable} backed by a
 * {@link ConcurrentHashMap}.
 *
 * <p>Default implementation used when
 * {@link de.regelsuche.persistence.GraphPersistenceMode#IN_MEMORY} is
 * selected or no persistence context is configured (e.g. in tests).</p>
 */
public class InMemoryTranspositionTable implements TranspositionTable {

    private final ConcurrentHashMap<String, TranspositionEntry> entries = new ConcurrentHashMap<>();

    @Override
    public Optional<TranspositionEntry> lookup(String canonicalHash) {
        if (canonicalHash == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(canonicalHash));
    }

    @Override
    public synchronized TranspositionEntry record(TranspositionEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("entry must not be null");
        }
        TranspositionEntry existing = entries.get(entry.canonicalHash());
        if (existing == null) {
            entries.put(entry.canonicalHash(), entry);
            return entry;
        }
        TranspositionEntry merged = existing.merge(
            entry.bestScore(),
            entry.minDepthSeen(),
            entry.bestKnownPathId(),
            entry.reachedByRuleIds(),
            entry.lastSeen()
        );
        entries.put(merged.canonicalHash(), merged);
        return merged;
    }

    @Override
    public Collection<TranspositionEntry> entries() {
        List<TranspositionEntry> copy = new ArrayList<>(entries.values());
        return Collections.unmodifiableCollection(copy);
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public void clear() {
        entries.clear();
    }
}
