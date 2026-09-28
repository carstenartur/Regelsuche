package de.regelsuche.search.moves;

import de.regelsuche.ast.Expr;

/** Session-owned reference; never a proof or persistence identity. */
public final class SearchExpressionRef {
    final SearchExpressionStore owner;
    final Expr expression;
    SearchExpressionRef(SearchExpressionStore owner, Expr expression) { this.owner = owner; this.expression = expression; }
}
