package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import java.math.BigInteger;
import java.util.*;

/** Independent quotient-ring proof for values observed through the same low-bit projection. */
final class LowWordProof {
    private final VerificationWork work;
    LowWordProof(VerificationWork work) { this.work = work; }
    boolean equivalent(Expr left, Expr right, NumericKind kind) {
        if (kind != NumericKind.INT && kind != NumericKind.LONG) return false;
        Projection a = projection(left, kind), b = projection(right, kind);
        if (a == null || b == null || a.bits != b.bits) return false;
        try {
            return new PolynomialProof(kind, a.bits, work).equivalent(
                    quotient(a.value, a.bits, kind, 0), quotient(b.value, b.bits, kind, 0));
        } catch (PolynomialProof.OutsideFragment outside) { return false; }
    }
    private record Projection(Expr value, int bits) { }
    private Projection projection(Expr expression, NumericKind kind) {
        work.charge(1);
        if (JavaExpressions.operationOf(expression).orElse(null) != NumericOperation.AND) return null;
        var args = JavaExpressions.operands(expression);
        for (int i = 0; i < 2; i++) if (JavaExpressions.isLiteral(args.get(i))) {
            BigInteger mask = SemanticChecker.integer(JavaExpressions.literalValue(args.get(i)))
                    .and(BigInteger.ONE.shiftLeft(kind.bits()).subtract(BigInteger.ONE));
            if (mask.signum() > 0 && mask.and(mask.add(BigInteger.ONE)).signum() == 0)
                return new Projection(args.get(1 - i), mask.bitLength());
        }
        return null;
    }
    private Expr quotient(Expr expression, int bits, NumericKind kind, int depth) {
        work.charge(1);
        if (depth > 128) throw new PolynomialProof.OutsideFragment();
        if (JavaExpressions.isLiteral(expression) || expression instanceof VariableExpr) return expression;
        var actual = JavaExpressions.resultKind(expression);
        if (!actual.integral() || actual == NumericKind.BIG_INTEGER || actual.bits() < bits) return expression;
        var args = JavaExpressions.operands(expression);
        var from = JavaExpressions.castSourceKind(expression);
        if (from.isPresent() && from.get().integral() && from.get() != NumericKind.BIG_INTEGER && from.get().bits() >= bits)
            return quotient(args.getFirst(), bits, kind, depth + 1);
        Projection projection = projection(expression, actual);
        if (projection != null && projection.bits >= bits) return quotient(projection.value, bits, kind, depth + 1);
        var op = JavaExpressions.operationOf(expression).orElse(null);
        if (op == NumericOperation.ADD || op == NumericOperation.SUBTRACT || op == NumericOperation.MULTIPLY || op == NumericOperation.NEGATE) {
            var children = args.stream().map(e -> quotient(e, bits, kind, depth + 1)).toArray(Expr[]::new);
            return JavaExpressions.operation(kind, op, children);
        }
        return expression;
    }
}
