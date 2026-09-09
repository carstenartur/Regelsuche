package de.regelsuche.plugin;

import de.regelsuche.ast.Expr;

@de.regelsuche.api.StableApi(since = "1")
public interface AstVisitorPlugin {
    String id();

    AstVisitorPhase phase();

    void visit(Expr root, AstVisitorContext context);
}
