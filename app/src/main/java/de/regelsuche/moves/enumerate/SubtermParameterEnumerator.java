package de.regelsuche.moves.enumerate;

import de.regelsuche.ast.Expr;
import de.regelsuche.moves.MoveParameter;
import de.regelsuche.moves.MoveParameterKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enumerates every distinct subterm of the current expression as a
 * {@link MoveParameterKind#SUBTERM} parameter.
 */
public final class SubtermParameterEnumerator implements ParameterEnumerator {

    @Override
    public String id() {
        return "subterm";
    }

    @Override
    public List<MoveParameter> enumerate(String expression) {
        return MoveExpressions.parse(expression)
                .map(this::fromExpr)
                .orElseGet(List::of);
    }

    @Override
    public List<MoveParameter> enumerate(Expr expr) {
        return fromExpr(expr);
    }

    private List<MoveParameter> fromExpr(Expr root) {
        Map<String, MoveParameter> distinct = new LinkedHashMap<>();
        for (Expr node : MoveExpressions.subexpressions(root)) {
            String text = MoveExpressions.format(node);
            distinct.putIfAbsent(text, new MoveParameter(
                    text,
                    MoveParameterKind.SUBTERM,
                    text,
                    text,
                    MoveParameter.UNSPECIFIED_INDEX,
                    id()));
        }
        List<MoveParameter> parameters = new ArrayList<>(distinct.values());
        parameters.sort(MoveParameter.CANONICAL_ORDER);
        return List.copyOf(parameters);
    }
}
