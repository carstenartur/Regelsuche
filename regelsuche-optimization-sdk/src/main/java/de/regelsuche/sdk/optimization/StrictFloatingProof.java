package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.*;
import java.util.*;

/** A small IEEE checker independent of candidate-generation rules. No reassociation or real algebra. */
final class StrictFloatingProof {
    private final VerificationWork work;
    StrictFloatingProof(VerificationWork work) { this.work = work; }
    boolean equivalent(Expr left, Expr right) { return canonical(left, 0).equals(canonical(right, 0)); }
    private Expr canonical(Expr expression, int depth) {
        work.charge(1);
        if (depth > 128) throw new IllegalArgumentException("FP_STRUCTURAL_BOUND");
        if (JavaExpressions.isLiteral(expression) || expression instanceof VariableExpr) return expression;
        var args = JavaExpressions.operands(expression).stream().map(child -> canonical(child, depth+1)).toList();
        Expr result = new FunctionExpr(((FunctionExpr)expression).name(), args);
        var op = JavaExpressions.operationOf(result);
        if (op.isEmpty()) return result;
        if (op.get() == NumericOperation.NEGATE && JavaExpressions.operationOf(args.getFirst()).orElse(null) == NumericOperation.NEGATE)
            return JavaExpressions.operands(args.getFirst()).getFirst();
        if (op.get() == NumericOperation.MULTIPLY || op.get() == NumericOperation.DIVIDE) {
            if (isNumber(args.get(1), 1.0, false)) return args.getFirst();
            if (op.get() == NumericOperation.MULTIPLY && isNumber(args.getFirst(), 1.0, false)) return args.get(1);
        }
        if (op.get() == NumericOperation.SUBTRACT && isNumber(args.get(1), 0.0, false)) return args.getFirst();
        if (op.get() == NumericOperation.ADD) {
            if (isNumber(args.get(1), 0.0, true)) return args.getFirst();
            if (isNumber(args.getFirst(), 0.0, true)) return args.get(1);
        }
        return result;
    }
    static boolean isNumber(Expr expression, double value, boolean negativeZero) {
        if (!JavaExpressions.isLiteral(expression)) return false;
        Object literal = JavaExpressions.literalValue(expression);
        if (!(literal instanceof Float || literal instanceof Double)) return false;
        double d = ((Number)literal).doubleValue();
        return d == value && (value != 0 || (Double.doubleToRawLongBits(d) < 0) == negativeZero);
    }
}
