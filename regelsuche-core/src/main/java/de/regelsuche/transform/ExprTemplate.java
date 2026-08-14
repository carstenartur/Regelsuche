package de.regelsuche.transform;

import de.regelsuche.ast.Expr;
import java.util.Map;

/**
 * Builds one expression from previously established matcher bindings.
 *
 * <p>Templates are intentionally separate from {@link ExprMatcher}: matcher
 * nodes such as negation, containment and alternatives do not have a unique
 * expression that could be instantiated.</p>
 */
@FunctionalInterface
public interface ExprTemplate {
    Expr instantiate(Map<String, Expr> bindings);
}
