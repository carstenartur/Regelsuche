package de.regelsuche.transform;

import de.regelsuche.ast.Expr;
import java.util.HashMap;
import java.util.Map;

public class PatternRewriteRule implements RewriteRule {
    private final String id;
    private final PatternExpr source;
    private final PatternExpr target;

    public PatternRewriteRule(String id, PatternExpr source, PatternExpr target) {
        if (id == null || id.isBlank() || source == null || target == null) {
            throw new IllegalArgumentException("id, source and target are required");
        }
        this.id = id;
        this.source = source;
        this.target = target;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean matches(Expr subtree) {
        return source.match(subtree, new HashMap<>());
    }

    @Override
    public Expr apply(Expr subtree) {
        Map<String, Expr> bindings = new HashMap<>();
        if (!source.match(subtree, bindings)) {
            throw new IllegalArgumentException("Rule does not match subtree");
        }
        return target.instantiate(bindings);
    }
}
