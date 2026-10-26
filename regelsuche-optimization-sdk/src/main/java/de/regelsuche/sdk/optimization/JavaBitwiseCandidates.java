package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import java.util.List;

/** Fixed-size, branchless proposals. The independent bitvector checker remains the authority. */
final class JavaBitwiseCandidates {
    private JavaBitwiseCandidates() {}

    static Expr simplify(Expr source, NumericOperation join, NumericKind kind, List<Expr> operands, VerificationWork work) {
        if ((kind != NumericKind.INT && kind != NumericKind.LONG)
                || (join != NumericOperation.OR && join != NumericOperation.XOR)
                || operands.size() != 2) return source;
        Expr left = operands.getFirst(), right = operands.get(1);
        Expr reconstructed = reconstruct(kind, left, right, work);
        if (reconstructed == null) reconstructed = reconstruct(kind, right, left, work);
        if (reconstructed != null) return reconstructed;
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

    /** High/low projections at any Java-masked word boundary; proof is separate. */
    private static Expr reconstruct(NumericKind kind, Expr high, Expr low, VerificationWork work) {
        if (!operation(high, NumericOperation.SHIFT_LEFT, kind) || !operation(low, NumericOperation.AND, kind)) return null;
        var outer = JavaExpressions.operands(high);
        Integer distance = distance(outer.get(1), kind);
        if (distance == null || !(operation(outer.getFirst(), NumericOperation.UNSIGNED_SHIFT_RIGHT, kind)
                || operation(outer.getFirst(), NumericOperation.SHIFT_RIGHT, kind))) return null;
        var inner = JavaExpressions.operands(outer.getFirst());
        if (!distance.equals(distance(inner.get(1), kind))) return null;
        Expr value = inner.getFirst();
        long mask = distance == 0 ? 0 : -1L >>> (64 - distance);
        var lower = JavaExpressions.operands(low);
        for (int side = 0; side < 2; side++) {
            Expr literal = lower.get(side);
            if (!JavaExpressions.isLiteral(literal)) continue;
            Object number = JavaExpressions.literalValue(literal);
            if (!(number instanceof Number n) || n.longValue() != mask) continue;
            Expr projected = lower.get(1 - side);
            for (int depth = 0; depth <= 128; depth++) {
                work.charge(1);
                if (projected.equals(value)) return value;
                var from = JavaExpressions.castSourceKind(projected);
                if (from.isEmpty() || !from.get().integral() || !JavaExpressions.resultKind(projected).integral()
                        || Math.min(from.get().bits(), JavaExpressions.resultKind(projected).bits()) < distance) break;
                projected = JavaExpressions.operands(projected).getFirst();
            }
        }
        return null;
    }

    private static Integer distance(Expr expression, NumericKind kind) {
        if (!JavaExpressions.isLiteral(expression)) return null;
        Object value = JavaExpressions.literalValue(expression);
        return value instanceof Number number ? number.intValue() & (kind.bits() - 1)
                : value instanceof Character character ? character & (kind.bits() - 1) : null;
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
