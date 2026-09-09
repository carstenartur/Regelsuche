package de.regelsuche.plugin;

import de.regelsuche.ast.Expr;
import java.util.Map;

@de.regelsuche.api.StableApi(since = "1")
public record TransformationMatchContext(Expr node, Map<String, Object> metadata) {
    public static TransformationMatchContext from(AstVisitorContext visitorContext, Expr node) {
        return new TransformationMatchContext(node, visitorContext.metadata(node));
    }
}
