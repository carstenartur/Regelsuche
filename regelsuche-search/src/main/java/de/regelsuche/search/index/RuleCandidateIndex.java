package de.regelsuche.search.index;

import de.regelsuche.ast.Expr;

/** Multi-stage index for retrieving rewrite-rule and macro-move candidates for an expression subtree. */
public interface RuleCandidateIndex {
    CandidateSet candidatesFor(Expr subtree, SearchContext context, CandidateBudget budget);
}
