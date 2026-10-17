package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.LongConsumer;

/** Bounded signed-additive proposals. Semantic validation remains the caller's responsibility. */
public final class SignedAdditiveForm {
    public static final int MAX_TERMS = 128;
    public static final int MAX_DEPTH = 64;

    /** Returns null at a non-additive or differently typed arithmetic island. */
    public interface Algebra {
        Sum sum(Expr expression);
        Expr add(Expr left, Expr right);
        Expr subtract(Expr left, Expr right);
        Expr zero();
    }
    public record Sum(Expr left, Expr right, boolean subtraction) {
        public Sum { Objects.requireNonNull(left); Objects.requireNonNull(right); }
    }
    public record Result(Expr expression, boolean complete) {
        public Result { Objects.requireNonNull(expression); }
    }
    private record Pending(Expr expression, int sign, int depth) { }
    private static final Algebra EXACT = new Algebra() {
        @Override public Sum sum(Expr expression) {
            if (!(expression instanceof BinaryExpr binary)) return null;
            return switch (binary.operator()) {
                case ADD -> new Sum(binary.left(), binary.right(), false);
                case SUB -> new Sum(binary.left(), binary.right(), true);
                default -> null;
            };
        }
        @Override public Expr add(Expr left, Expr right) { return new BinaryExpr(left, BinaryOperator.ADD, right); }
        @Override public Expr subtract(Expr left, Expr right) { return new BinaryExpr(left, BinaryOperator.SUB, right); }
        @Override public Expr zero() { return new NumberExpr(0); }
    };
    private SignedAdditiveForm() { }

    public static Result normalize(Expr expression, LongConsumer work) {
        return normalize(expression, EXACT, work);
    }
    /** Charges visits and construction; an exception from the work callback propagates. */
    public static Result normalize(Expr expression, Algebra algebra, LongConsumer work) {
        Objects.requireNonNull(expression); Objects.requireNonNull(algebra); Objects.requireNonNull(work);
        work.accept(1);
        if (algebra.sum(expression) == null) return new Result(expression, true);
        var pending = new ArrayDeque<Pending>();
        var coefficients = new LinkedHashMap<Expr, Integer>();
        pending.addLast(new Pending(expression, 1, 0));
        int terms = 0;
        while (!pending.isEmpty()) {
            work.accept(1);
            Pending next = pending.removeLast();
            if (next.depth() > MAX_DEPTH) return new Result(expression, false);
            Sum sum = algebra.sum(next.expression());
            if (sum == null) {
                if (++terms > MAX_TERMS) return new Result(expression, false);
                coefficients.merge(next.expression(), next.sign(), Integer::sum);
            } else {
                pending.addLast(new Pending(sum.right(), sum.subtraction() ? -next.sign() : next.sign(), next.depth() + 1));
                pending.addLast(new Pending(sum.left(), next.sign(), next.depth() + 1));
            }
        }
        Expr zero = algebra.zero();
        var positive = new ArrayList<Expr>();
        var negative = new ArrayList<Expr>();
        for (var entry : coefficients.entrySet()) {
            work.accept(1);
            if (entry.getKey().equals(zero)) continue;
            int count = entry.getValue();
            for (int i = 0; i < Math.abs(count); i++) {
                work.accept(1);
                (count > 0 ? positive : negative).add(entry.getKey());
            }
        }
        int pairs = Math.min(positive.size(), negative.size());
        Expr result = null;
        for (int i = 0; i < positive.size(); i++) {
            work.accept(1);
            Expr term = positive.get(i);
            if (i < pairs) { work.accept(1); term = algebra.subtract(term, negative.get(i)); }
            if (result == null) result = term;
            else { work.accept(1); result = algebra.add(result, term); }
        }
        if (result == null) result = zero;
        for (int i = pairs; i < negative.size(); i++) {
            work.accept(1);
            result = algebra.subtract(result, negative.get(i));
        }
        return new Result(result, true);
    }
}
