package de.regelsuche.search.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Opt-in search-memory bundle attached to a
 * {@link de.regelsuche.search.strategy.SearchProblem}.
 *
 * <p>Combines a {@link TranspositionTable} with an in-search log of
 * {@link PruningDecision}s so a strategy can record why it dropped or kept
 * a re-visited state without changing its return type.</p>
 *
 * <p>When a {@link de.regelsuche.search.strategy.SearchProblem} carries
 * {@code null} as its memory, strategies fall back to their classic
 * canonical-hash-only deduplication and emit no decisions — that keeps the
 * existing rule-mining and benchmark tests stable. Memory is wired in by
 * {@link de.regelsuche.search.SearchProfile#DISCOVERY_PLUS} and tests that
 * explicitly opt in.</p>
 */
public final class SearchMemory {

    private final TranspositionTable table;
    private final List<PruningDecision> decisions = Collections.synchronizedList(new ArrayList<>());

    public SearchMemory() {
        this(new InMemoryTranspositionTable());
    }

    public SearchMemory(TranspositionTable table) {
        if (table == null) {
            throw new IllegalArgumentException("table must not be null");
        }
        this.table = table;
    }

    public TranspositionTable table() {
        return table;
    }

    public void recordDecision(PruningDecision decision) {
        decisions.add(decision);
    }

    public List<PruningDecision> decisions() {
        synchronized (decisions) {
            return List.copyOf(decisions);
        }
    }

    public void clearDecisions() {
        decisions.clear();
    }
}
