package de.regelsuche.evolution;

import de.regelsuche.ast.*;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
import java.util.ArrayDeque;
import java.util.Objects;

/** The external surface objective, not an algebraic simplifier or a CPU estimate. */
final class TypedPolynomialSurfaceCost {
    private TypedPolynomialSurfaceCost() { }
    private static final class Emission implements java.util.function.LongConsumer, de.regelsuche.retention.RetainedGraph.View {
        private long units;
        @Override public void accept(long amount) { units = Math.addExact(units, amount); }
        @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor visitor) { }
    }
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
                var emitted = new Emission();
                String spelling = ExpressionFormatter.formatMeasured(number, emitted);
                work = Math.addExact(work, Math.addExact(emitted.units, spelling.length()));
                for (int i = 0; i < spelling.length(); i++) {
                    if (spelling.charAt(i) == '-' || spelling.charAt(i) == '/') cost = Math.addExact(cost, 1);
                }
            } else if (!(next instanceof VariableExpr)) {
                throw new IllegalArgumentException("not polynomial surface syntax");
            }
        }
        return new TypedSourceOnlySearch.Score(cost, work);
    }

    /** The shared exact arithmetic transport has no powers or variable denominators. */
    static TypedSourceOnlySearch.Score evaluateSharedArithmetic(Expr expression) {
        var pending = new ArrayDeque<Expr>();
        pending.push(Objects.requireNonNull(expression, "expression"));
        var work = new Emission();
        while (!pending.isEmpty()) {
            Expr next = pending.pop();
            work.accept(1);
            if (next instanceof BinaryExpr binary) {
                if (binary.operator() == BinaryOperator.POW || binary.operator() == BinaryOperator.DIV
                        && !nonzeroConstant(binary.right(), work)) {
                    return new TypedSourceOnlySearch.Score(Long.MAX_VALUE, work.units);
                }
                pending.push(binary.right());
                pending.push(binary.left());
            } else if (next instanceof NumberExpr number) {
                if (number.value().numerator().abs().bitLength() > 63 || number.value().denominator().bitLength() > 63) {
                    return new TypedSourceOnlySearch.Score(Long.MAX_VALUE, work.units);
                }
            } else if (!(next instanceof VariableExpr)) {
                return new TypedSourceOnlySearch.Score(Long.MAX_VALUE, work.units);
            }
        }
        var accepted = evaluate(expression);
        return new TypedSourceOnlySearch.Score(accepted.value(), Math.addExact(work.units, accepted.work()));
    }

    private static boolean nonzeroConstant(Expr expression, Emission work) {
        try {
            return !constantValue(expression, work, 0).isZero();
        } catch (IllegalArgumentException | ArithmeticException unsupported) {
            return false;
        }
    }

    private static ExactRational constantValue(Expr expression, Emission work, int depth) {
        work.accept(1);
        if (depth > 128) throw new IllegalArgumentException("constant divisor depth");
        ExactRational value;
        if (expression instanceof NumberExpr number) value = number.value();
        else if (expression instanceof BinaryExpr binary && binary.operator() != BinaryOperator.POW) {
            var left = constantValue(binary.left(), work, depth + 1);
            var right = constantValue(binary.right(), work, depth + 1);
            value = switch (binary.operator()) {
                case ADD -> left.add(right);
                case SUB -> left.subtract(right);
                case MUL -> left.multiply(right);
                case DIV -> left.divide(right);
                case POW -> throw new IllegalStateException("power outside shared arithmetic");
            };
        } else throw new IllegalArgumentException("constant divisor required");
        if (value.numerator().abs().bitLength() > 256 || value.denominator().bitLength() > 256) {
            throw new IllegalArgumentException("constant divisor bit limit");
        }
        return value;
    }
}
