package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.transform.SignedAdditiveForm;
import java.math.BigInteger;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Typed representation bridge. The independent checker remains the admission authority. */
final class JavaSignedAdditiveCandidates {
    record Result(List<Expr> outputs, boolean complete) {
        Result { outputs = List.copyOf(outputs); }
    }
    private final VerificationWork work;
    private final Map<Expr, Expr> memo = new IdentityHashMap<>();
    private final Map<NumericKind, SignedAdditiveForm.Algebra> algebras = new EnumMap<>(NumericKind.class);
    private boolean complete = true;
    JavaSignedAdditiveCandidates(VerificationWork work) { this.work = work; }

    Result generate(List<Expr> outputs) {
        try { return new Result(outputs.stream().map(expression -> rewrite(expression, 0)).toList(), complete); }
        finally { memo.clear(); algebras.clear(); }
    }
    private Expr rewrite(Expr expression, int depth) {
        work.charge(1);
        if (depth > 128) { complete = false; return expression; }
        Expr cached = memo.get(expression);
        if (cached != null) return cached;
        if (expression instanceof VariableExpr || JavaExpressions.isLiteral(expression)) return expression;
        var arguments = JavaExpressions.operands(expression).stream().map(child -> rewrite(child, depth + 1)).toList();
        Expr result = new FunctionExpr(((FunctionExpr) expression).name(), arguments);
        NumericKind kind = JavaExpressions.resultKind(result);
        NumericOperation operation = JavaExpressions.operationOf(result).orElse(null);
        // Floating point, checked arithmetic and differently typed islands stay opaque.
        if ((kind == NumericKind.INT || kind == NumericKind.LONG || kind == NumericKind.BIG_INTEGER)
                && (operation == NumericOperation.ADD || operation == NumericOperation.SUBTRACT)) {
            var normalized = SignedAdditiveForm.normalize(result,
                    algebras.computeIfAbsent(kind, JavaSignedAdditiveCandidates::algebra), work::charge);
            complete &= normalized.complete();
            result = normalized.expression();
        }
        memo.put(expression, result);
        return result;
    }
    private static SignedAdditiveForm.Algebra algebra(NumericKind kind) {
        return new SignedAdditiveForm.Algebra() {
            @Override public SignedAdditiveForm.Sum sum(Expr expression) {
                if (expression instanceof VariableExpr || JavaExpressions.isLiteral(expression)
                        || JavaExpressions.resultKind(expression) != kind) return null;
                var operation = JavaExpressions.operationOf(expression).orElse(null);
                if (operation != NumericOperation.ADD && operation != NumericOperation.SUBTRACT) return null;
                var arguments = JavaExpressions.operands(expression);
                return new SignedAdditiveForm.Sum(arguments.getFirst(), arguments.get(1), operation == NumericOperation.SUBTRACT);
            }
            @Override public Expr add(Expr left, Expr right) {
                return JavaExpressions.operation(kind, NumericOperation.ADD, left, right);
            }
            @Override public Expr subtract(Expr left, Expr right) {
                return JavaExpressions.operation(kind, NumericOperation.SUBTRACT, left, right);
            }
            @Override public Expr zero() {
                return switch (kind) {
                    case INT -> JavaExpressions.literal(0);
                    case LONG -> JavaExpressions.literal(0L);
                    case BIG_INTEGER -> JavaExpressions.literal(BigInteger.ZERO);
                    default -> throw new IllegalArgumentException("ADDITIVE_GROUP_KIND_REQUIRED");
                };
            }
        };
    }
}
