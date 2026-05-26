package de.regelsuche.search.index;

import de.regelsuche.transform.RewriteRule;
import java.util.Collection;
import java.util.List;

/** Index for narrowing atomic rewrite rules and learned macro moves by term shape and metadata. */
public interface TermRuleIndex {
    void addAtomicRule(String rootSymbol, RewriteRule rule);

    void addMacroMove(IndexedMacroMove rule);

    default boolean removeAtomicRule(String rootSymbol, RewriteRule rule) {
        return false;
    }

    default boolean removeMacroMove(String id) {
        return false;
    }

    default boolean updateMacroMove(IndexedMacroMove rule) {
        boolean removed = removeMacroMove(rule.id());
        addMacroMove(rule);
        return removed;
    }

    default void rebuild(Collection<AtomicRuleEntry> atomicRules, Collection<IndexedMacroMove> macroMoves) {
        throw new UnsupportedOperationException("bulk rebuild is not supported by this index");
    }

    QueryResult query(String expression, Query query);

    record Query(
        String goalExpression,
        ProofStatusRank minimumProofStatus,
        String domain,
        boolean includeAtomicRules,
        boolean includeMacroMoves
    ) {
        public Query {
            includeAtomicRules = includeAtomicRules || !includeMacroMoves;
            minimumProofStatus = minimumProofStatus == null ? ProofStatusRank.OBSERVED : minimumProofStatus;
            domain = domain == null ? "" : domain;
        }

        public static Query all() {
            return new Query("", ProofStatusRank.OBSERVED, "", true, true);
        }
    }

    record QueryResult(List<RewriteRule> atomicRules, List<IndexedMacroMove> macroMoves, Metrics metrics) {
        public QueryResult {
            atomicRules = atomicRules == null ? List.of() : List.copyOf(atomicRules);
            macroMoves = macroMoves == null ? List.of() : List.copyOf(macroMoves);
            metrics = metrics == null ? new Metrics(0, 0, 0) : metrics;
        }
    }

    record Metrics(int rulesConsidered, int rulesSkippedByIndex, int rulesMatched) {
    }

    record AtomicRuleEntry(String rootSymbol, RewriteRule rule) {
        public AtomicRuleEntry {
            rootSymbol = rootSymbol == null ? "" : rootSymbol;
            if (rule == null) {
                throw new IllegalArgumentException("rule must not be null");
            }
        }
    }

    record IndexedMacroMove(
        String id,
        String leftPattern,
        String rightPattern,
        ProofStatusRank proofStatus,
        String domain
    ) {
        public IndexedMacroMove {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id must not be blank");
            }
            leftPattern = leftPattern == null ? "" : leftPattern;
            rightPattern = rightPattern == null ? "" : rightPattern;
            proofStatus = proofStatus == null ? ProofStatusRank.OBSERVED : proofStatus;
            domain = domain == null ? "" : domain;
        }
    }

    enum ProofStatusRank {
        REJECTED,
        OBSERVED,
        VALIDATED_BY_EXAMPLES,
        SYMBOLICALLY_VERIFIED,
        FORMALLY_PROVABLE,
        FORMALLY_PROVED
    }
}
