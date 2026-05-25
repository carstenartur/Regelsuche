package de.regelsuche.index;

import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.List;

/** Index for narrowing atomic rewrite rules and learned macro moves by term shape and metadata. */
public interface TermRuleIndex {
    void addAtomicRule(String rootSymbol, RewriteRule rule);

    void addMacroMove(ReusableRule rule);

    QueryResult query(String expression, Query query);

    record Query(
        String goalExpression,
        CandidateProofStatus minimumProofStatus,
        String domain,
        boolean includeAtomicRules,
        boolean includeMacroMoves
    ) {
        public Query {
            includeAtomicRules = includeAtomicRules || !includeMacroMoves;
            minimumProofStatus = minimumProofStatus == null ? CandidateProofStatus.OBSERVED : minimumProofStatus;
            domain = domain == null ? "" : domain;
        }

        public static Query all() {
            return new Query("", CandidateProofStatus.OBSERVED, "", true, true);
        }
    }

    record QueryResult(List<RewriteRule> atomicRules, List<ReusableRule> macroMoves, Metrics metrics) {
        public QueryResult {
            atomicRules = atomicRules == null ? List.of() : List.copyOf(atomicRules);
            macroMoves = macroMoves == null ? List.of() : List.copyOf(macroMoves);
            metrics = metrics == null ? new Metrics(0, 0, 0) : metrics;
        }
    }

    record Metrics(int rulesConsidered, int rulesSkippedByIndex, int rulesMatched) {
    }
}
