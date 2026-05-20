package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public sealed interface PatternExpr
    permits PatternExpr.Placeholder, PatternExpr.LiteralNumber, PatternExpr.Operation, PatternExpr.Function {
    static PatternExpr var(String name) {
        return new Placeholder(name);
    }

    static PatternExpr num(double value) {
        return new LiteralNumber(value);
    }

    static PatternExpr op(BinaryOperator operator, PatternExpr left, PatternExpr right) {
        return new Operation(operator, left, right);
    }

    static PatternExpr fn(String name, PatternExpr... arguments) {
        return new Function(name, List.of(arguments));
    }

    boolean match(Expr expression, Map<String, Expr> bindings);

    Expr instantiate(Map<String, Expr> bindings);

    record Placeholder(String name) implements PatternExpr {
        public Placeholder {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("placeholder name must not be blank");
            }
        }

        @Override
        public boolean match(Expr expression, Map<String, Expr> bindings) {
            Expr bound = bindings.get(name);
            if (bound == null) {
                bindings.put(name, expression);
                return true;
            }
            return bound.equals(expression);
        }

        @Override
        public Expr instantiate(Map<String, Expr> bindings) {
            return Optional.ofNullable(bindings.get(name))
                .orElseThrow(() -> new IllegalArgumentException("Missing binding for " + name));
        }
    }

    record LiteralNumber(double value) implements PatternExpr {
        @Override
        public boolean match(Expr expression, Map<String, Expr> bindings) {
            return expression instanceof NumberExpr numberExpr && numberExpr.value() == value;
        }

        @Override
        public Expr instantiate(Map<String, Expr> bindings) {
            return new NumberExpr(value);
        }
    }

    record Operation(BinaryOperator operator, PatternExpr left, PatternExpr right) implements PatternExpr {
        public Operation {
            if (operator == null || left == null || right == null) {
                throw new IllegalArgumentException("operator, left and right must not be null");
            }
        }

        @Override
        public boolean match(Expr expression, Map<String, Expr> bindings) {
            if (!(expression instanceof BinaryExpr binaryExpr) || binaryExpr.operator() != operator) {
                return false;
            }
            return left.match(binaryExpr.left(), bindings) && right.match(binaryExpr.right(), bindings);
        }

        @Override
        public Expr instantiate(Map<String, Expr> bindings) {
            return new BinaryExpr(left.instantiate(bindings), operator, right.instantiate(bindings));
        }
    }

    /**
     * Pattern for function applications like {@code sin(A)} or {@code log(A*B)}.
     * Matches a {@link FunctionExpr} by exact name (case-sensitive) and arity,
     * recursively matching each argument.
     */
    record Function(String name, List<PatternExpr> arguments) implements PatternExpr {
        public Function {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("function name must not be blank");
            }
            Objects.requireNonNull(arguments, "arguments");
            arguments = List.copyOf(arguments);
        }

        @Override
        public boolean match(Expr expression, Map<String, Expr> bindings) {
            if (!(expression instanceof FunctionExpr functionExpr)) {
                return false;
            }
            if (!functionExpr.name().equals(name) || functionExpr.arguments().size() != arguments.size()) {
                return false;
            }
            for (int i = 0; i < arguments.size(); i++) {
                if (!arguments.get(i).match(functionExpr.arguments().get(i), bindings)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public Expr instantiate(Map<String, Expr> bindings) {
            List<Expr> args = new ArrayList<>(arguments.size());
            for (PatternExpr argument : arguments) {
                args.add(argument.instantiate(bindings));
            }
            return new FunctionExpr(name, args);
        }
    }
}
