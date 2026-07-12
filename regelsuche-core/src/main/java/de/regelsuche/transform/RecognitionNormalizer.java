package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.value.ExprValueFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Bounded canonicalization used only for recognition and anti-unification. */
public final class RecognitionNormalizer {
    private RecognitionNormalizer() {
    }

    public static Expr normalize(Expr expression, RecognitionProfile profile) {
        try (Session session = session(profile)) {
            return session.normalize(expression);
        }
    }

    /**
     * Creates one bounded value-identity owner for a batch of recognition
     * normalizations, for example all examples of one anti-unification run.
     */
    public static Session session(RecognitionProfile profile) {
        return new Session(profile);
    }

    /** Owner-scoped normalization session; not shared between rules or searches. */
    public static final class Session implements AutoCloseable {
        private final RecognitionProfile profile;
        private final ExprValueFactory values = new ExprValueFactory();
        private boolean closed;

        private Session(RecognitionProfile profile) {
            this.profile = Objects.requireNonNull(profile, "profile");
        }

        public Expr normalize(Expr expression) {
            ensureOpen();
            return normalizeInternal(Objects.requireNonNull(expression, "expression"));
        }

        private Expr normalizeInternal(Expr expression) {
            if (expression instanceof FunctionExpr function) {
                return new FunctionExpr(function.name(), function.arguments().stream()
                    .map(this::normalizeInternal).toList());
            }
            if (!(expression instanceof BinaryExpr binary)) {
                return expression;
            }
            Expr left = normalizeInternal(binary.left());
            Expr right = normalizeInternal(binary.right());
            BinaryOperator operator = binary.operator();

            if (profile.inferAlgebraicBindings() && operator == BinaryOperator.MUL && left.equals(right)) {
                return new BinaryExpr(left, BinaryOperator.POW, new NumberExpr(2));
            }
            if (profile.isAssociative(operator)) {
                List<Expr> operands = new ArrayList<>();
                flatten(left, operator, operands);
                flatten(right, operator, operands);
                if (profile.isCommutative(operator)) {
                    operands.sort(Comparator
                        .comparing((Expr operand) -> values.fromExpr(operand).key())
                        .thenComparing(RecognitionNormalizer::syntaxKey));
                }
                return rebuild(operands, operator);
            }
            return new BinaryExpr(left, operator, right);
        }

        @Override
        public void close() {
            if (!closed) {
                values.close();
                closed = true;
            }
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("recognition normalization session is closed");
            }
        }
    }

    private static void flatten(Expr expression, BinaryOperator operator, List<Expr> target) {
        if (expression instanceof BinaryExpr binary && binary.operator() == operator) {
            flatten(binary.left(), operator, target);
            flatten(binary.right(), operator, target);
        } else {
            target.add(expression);
        }
    }

    private static Expr rebuild(List<Expr> operands, BinaryOperator operator) {
        if (operands.isEmpty()) {
            throw new IllegalArgumentException("cannot rebuild an empty operand list");
        }
        Expr result = operands.get(0);
        for (int i = 1; i < operands.size(); i++) {
            result = new BinaryExpr(result, operator, operands.get(i));
        }
        return result;
    }

    /** Structural tie-breaker for values equal under broader factory laws. */
    private static String syntaxKey(Expr expression) {
        if (expression instanceof NumberExpr number) {
            return "N" + Long.toUnsignedString(Double.doubleToLongBits(number.value()), 16);
        }
        if (expression instanceof VariableExpr variable) {
            return "V" + segment(variable.name());
        }
        if (expression instanceof BinaryExpr binary) {
            return "B" + binary.operator().name()
                + segment(syntaxKey(binary.left()))
                + segment(syntaxKey(binary.right()));
        }
        if (expression instanceof FunctionExpr function) {
            StringBuilder key = new StringBuilder("F").append(segment(function.name()));
            for (Expr argument : function.arguments()) {
                key.append(segment(syntaxKey(argument)));
            }
            return key.toString();
        }
        throw new IllegalArgumentException("unsupported expression type: " + expression.getClass().getName());
    }

    private static String segment(String value) {
        return value.length() + ":" + value;
    }
}
