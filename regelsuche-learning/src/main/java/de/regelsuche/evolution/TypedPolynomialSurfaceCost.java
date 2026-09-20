package de.regelsuche.evolution;

import de.regelsuche.ast.*;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
import java.util.ArrayDeque;
import java.util.Objects;

/** The external surface objective, not an algebraic simplifier or a CPU estimate. */
final class TypedPolynomialSurfaceCost {
    private TypedPolynomialSurfaceCost() { }
    static TypedSourceOnlySearch.Score evaluate(Expr expression) {
        var pending = new ArrayDeque<Expr>();
        pending.push(Objects.requireNonNull(expression, "expression"));
        long cost = 0, work = 0;
        while (!pending.isEmpty()) {
            Expr next = pending.pop();
            work = Math.addExact(work, 1);
            if (next instanceof BinaryExpr binary) {
                cost = Math.addExact(cost, 1);
                pending.push(binary.right()); pending.push(binary.left());
            } else if (next instanceof NumberExpr number) {
                // A typed rational leaf may print as a decimal, a fraction, or a negative.
                // Inspect only its surface spelling; never format/reparse whole states.
                long[] emitted = {0};
                String spelling = ExpressionFormatter.formatMeasured(number, n -> emitted[0] = Math.addExact(emitted[0], n));
                work = Math.addExact(work, Math.addExact(emitted[0], spelling.length()));
                for (int i = 0; i < spelling.length(); i++) {
                    if (spelling.charAt(i) == '-' || spelling.charAt(i) == '/') cost = Math.addExact(cost, 1);
                }
            } else if (!(next instanceof VariableExpr)) {
                throw new IllegalArgumentException("not polynomial surface syntax");
            }
        }
        return new TypedSourceOnlySearch.Score(cost, work);
    }
}
