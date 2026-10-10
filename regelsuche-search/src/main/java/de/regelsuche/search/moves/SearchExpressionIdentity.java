package de.regelsuche.search.moves;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.ExpressionIdentity;

/** Search-store accounting adapter over the shared core identity traversal. */
final class SearchExpressionIdentity {
    private SearchExpressionIdentity() {}
    static int hash(SearchExpressionStore store, Expr expression) {
        return ExpressionIdentity.hash(store, expression);
    }
    static boolean same(SearchExpressionStore store, Expr left, Expr right) {
        return ExpressionIdentity.same(store, left, right);
    }
}
