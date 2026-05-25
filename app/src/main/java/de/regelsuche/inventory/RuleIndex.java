package de.regelsuche.inventory;

import java.util.List;
import java.util.Optional;

/**
 * Stable port for a fast, lookup-oriented index over the rule inventory.
 *
 * <p>Introduced as part of Teil 0 of the Discovery Epic (issue #41,
 * "Interfaces zuerst"): large new features around discovery, search and
 * macro-rule reuse must depend on this abstraction, not on a concrete
 * persistence backend (in-memory map, JSON file, Neo4j, …).
 *
 * <p>This is intentionally separated from {@link RuleInventoryRepository}:
 * the repository owns lifecycle and persistence of {@link ReusableRule}s;
 * the {@code RuleIndex} is a read-side view tuned for lookups by id, tag
 * or left-hand-side pattern key.
 *
 * <p>No production implementation is required for Teil 0 — concrete
 * implementations land with the relevant feature work and must follow the
 * dependency rules in {@code docs/dependency-rules.md}.
 */
public interface RuleIndex {

    /** @return the rule with the given id, if known. */
    Optional<ReusableRule> findById(String ruleId);

    /** @return all rules carrying the given domain or category tag. */
    List<ReusableRule> findByTag(String tag);

    /**
     * @return rules whose canonicalised left-hand side matches the given
     *     pattern key. The key is implementation defined (e.g. canonical
     *     hash or normalized string form); callers must use the same
     *     normaliser as the implementation.
     */
    List<ReusableRule> findByLeftPatternKey(String patternKey);

    /** @return the total number of indexed rules. */
    int size();
}
