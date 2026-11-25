package de.regelsuche.search.strategy;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;

/**
 * Explicit provider contract for the prepared continuation quotient. For a fixed inventory
 * and settings, the complete ordered primitive list depends on the exact source syntax and
 * normalized assumptions. The supplied AST was parsed from that syntax. Earlier moves,
 * call order and remaining resources may not change the list. This declares execution
 * behavior. Mathematical authority still belongs to replay and to applicable side conditions.
 */
@FunctionalInterface
public interface StateLocalTransformationEngine extends TransformationEngine {
    List<Transformation> transformPrepared(String source, Expr parsedSource, List<String> assumptions);

    @Override
    default List<Transformation> transform(String source) {
        return transformPrepared(source, new ExpressionParser().parseTerm(source), List.of());
    }
}
