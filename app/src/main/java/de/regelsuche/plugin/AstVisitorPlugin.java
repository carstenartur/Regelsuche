package de.regelsuche.plugin;

import de.regelsuche.ast.Expr;

public interface AstVisitorPlugin {
    String id();

    AstVisitorPhase phase();

    void visit(Expr root, AstVisitorContext context);
}
