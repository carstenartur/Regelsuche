package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import java.util.List;

/** Fixed-size, branchless proposals. The independent bitvector checker remains the authority. */
final class JavaBitwiseCandidates {
    private JavaBitwiseCandidates() {}

    static Expr simplify(Expr source, NumericOperation join, NumericKind kind, List<Expr> operands) {
        if ((kind != NumericKind.INT && kind != NumericKind.LONG)
                || (join != NumericOperation.OR && join != NumericOperation.XOR)
                || operands.size() != 2) return source;
        Expr left = operands.getFirst(), right = operands.get(1);
        if (!operation(left, NumericOperation.AND, kind) || !operation(right, NumericOperation.AND, kind))
            return source;
        var a = JavaExpressions.operands(left);
        var b = JavaExpressions.operands(right);
        for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) {
            Expr mask = a.get(i), otherMask = b.get(j);
            Expr positive = a.get(1 - i), negative = b.get(1 - j);
            if (mask.equals(otherMask)) {
                // (a & m) JOIN (b & m) = (a JOIN b) & m. Applying this
                // locally also reduces the three-term majority function.
                return op(kind, NumericOperation.AND, op(kind, join, positive, negative), mask);
            }
            if (complementOf(otherMask, mask, kind)) return choose(kind, mask, positive, negative);
            if (complementOf(mask, otherMask, kind)) return choose(kind, otherMask, negative, positive);
        }
        return source;
    }

    private static Expr choose(NumericKind kind, Expr mask, Expr positive, Expr negative) {
        // Complementary masks are disjoint, so this holds for OR and XOR.
        // No data-dependent branch, lookup, narrowing or arithmetic is introduced.
        return op(kind, NumericOperation.XOR, negative,
                op(kind, NumericOperation.AND, mask, op(kind, NumericOperation.XOR, positive, negative)));
    }

    private static boolean complementOf(Expr expression, Expr other, NumericKind kind) {
        return operation(expression, NumericOperation.NOT, kind)
                && JavaExpressions.operands(expression).getFirst().equals(other);
    }

    private static boolean operation(Expr expression, NumericOperation expected, NumericKind kind) {
        return JavaExpressions.operationOf(expression).orElse(null) == expected
                && JavaExpressions.resultKind(expression) == kind;
    }

    private static Expr op(NumericKind kind, NumericOperation operation, Expr... operands) {
        return JavaExpressions.operation(kind, operation, operands);
    }
}
