package de.regelsuche.transform;

import de.regelsuche.ast.Expr;

public interface RewriteRule {
    String id();

    boolean matches(Expr subtree);

    Expr apply(Expr subtree);
}
