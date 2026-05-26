package de.regelsuche.search.index;

import java.util.Set;

/** Query context for goal-, domain-, proof-, and assumption-aware candidate retrieval. */
public record SearchContext(
    String goalExpression,
    TermRuleIndex.ProofStatusRank minimumProofStatus,
    String domain,
    boolean includeAtomicRules,
    boolean includeMacroMoves,
    Set<String> assumptionSignature
) {
    public SearchContext {
        goalExpression = goalExpression == null ? "" : goalExpression;
        minimumProofStatus = minimumProofStatus == null
            ? TermRuleIndex.ProofStatusRank.OBSERVED
            : minimumProofStatus;
        domain = domain == null ? "" : domain;
        includeAtomicRules = includeAtomicRules || !includeMacroMoves;
        assumptionSignature = assumptionSignature == null ? Set.of() : Set.copyOf(assumptionSignature);
    }

    public static SearchContext all() {
        return from(TermRuleIndex.Query.all());
    }

    public static SearchContext from(TermRuleIndex.Query query) {
        TermRuleIndex.Query effective = query == null ? TermRuleIndex.Query.all() : query;
        return new SearchContext(
            effective.goalExpression(),
            effective.minimumProofStatus(),
            effective.domain(),
            effective.includeAtomicRules(),
            effective.includeMacroMoves(),
            Set.of()
        );
    }
}
