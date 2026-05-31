package de.regelsuche.plugin;

import de.regelsuche.ast.Expr;
import java.util.Map;

public record TransformationMatchContext(Expr node, Map<String, Object> metadata) {
    public static TransformationMatchContext from(AstVisitorContext visitorContext, Expr node) {
        return new TransformationMatchContext(node, visitorContext.metadata(node));
    }
}
