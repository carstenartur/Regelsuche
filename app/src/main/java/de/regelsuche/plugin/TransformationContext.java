package de.regelsuche.plugin;

import de.regelsuche.ast.Expr;
import java.util.Map;

public record TransformationContext(Expr node, Map<String, Object> metadata) {
    public static TransformationContext from(AstVisitorContext visitorContext, Expr node) {
        return new TransformationContext(node, visitorContext.metadata(node));
    }
}
