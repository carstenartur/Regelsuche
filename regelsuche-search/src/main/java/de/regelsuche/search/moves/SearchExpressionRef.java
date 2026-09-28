package de.regelsuche.search.moves;

import de.regelsuche.ast.Expr;

/** Session-owned reference; never a proof or persistence identity. */
public final class SearchExpressionRef implements de.regelsuche.retention.RetainedGraph.View {
    @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v){v.reference(owner);v.reference(expression);}
    final SearchExpressionStore owner;
    final Expr expression;
    SearchExpressionRef(SearchExpressionStore owner, Expr expression) { this.owner = owner; this.expression = expression; }
}
