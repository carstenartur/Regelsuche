package de.regelsuche.moves.enumerate;

import de.regelsuche.ast.Expr;
import de.regelsuche.moves.MoveParameter;
import de.regelsuche.moves.MoveParameterKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enumerates subterms that occur more than once in the current expression. Such
 * repeated subexpressions are prime candidates for substitution or
 * common-subexpression moves.
 */
public final class RepeatedSubexpressionEnumerator implements ParameterEnumerator {

    @Override
    public String id() {
        return "repeated-subexpression";
    }

    @Override
    public List<MoveParameter> enumerate(String expression) {
        return MoveExpressions.parse(expression)
                .map(this::fromExpr)
                .orElseGet(List::of);
    }

    @Override
    public List<MoveParameter> enumerate(Expr root) {
        return fromExpr(root);
    }

    private List<MoveParameter> fromExpr(Expr root) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Expr node : MoveExpressions.subexpressions(root)) {
            if (!isInteresting(node)) {
                continue;
            }
            String text = MoveExpressions.format(node);
            counts.merge(text, 1, Integer::sum);
        }
        List<MoveParameter> parameters = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() < 2) {
                continue;
            }
            parameters.add(new MoveParameter(
                    entry.getKey(),
                    MoveParameterKind.SUBTERM,
                    entry.getKey(),
                    entry.getKey(),
                    MoveParameter.UNSPECIFIED_INDEX,
                    id()));
        }
        parameters.sort(MoveParameter.CANONICAL_ORDER);
        return List.copyOf(parameters);
    }

    private boolean isInteresting(Expr node) {
        // Only count composite subexpressions; bare numbers/variables repeat
        // trivially and are not useful substitution targets.
        return node instanceof de.regelsuche.ast.BinaryExpr
                || node instanceof de.regelsuche.ast.FunctionExpr;
    }
}
