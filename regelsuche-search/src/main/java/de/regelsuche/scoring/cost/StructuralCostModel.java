package de.regelsuche.scoring.cost;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.scoring.ExpressionScore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stateless built-in structural objectives, sharing the same AST metrics.
 * Custom objectives continue to implement {@link CostModel} independently.
 */
public enum StructuralCostModel implements CostModel {
    OPERATOR_COUNT("operator-count"),
    DEPTH("depth"),
    FACTORED_FORM("factored-form"),
    SYMMETRY("symmetry");

    private final String id;

    StructuralCostModel(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int cost(String expression, Expr parsedAst, ExpressionScore score) {
        if (parsedAst == null) {
            return bounded(switch (this) {
                case DEPTH -> score.nestingDepth();
                case SYMMETRY -> 6L * score.operatorCount();
                default -> score.operatorCount();
            });
        }
        return switch (this) {
            case OPERATOR_COUNT -> countOperators(parsedAst);
            case DEPTH -> depth(parsedAst, 1);
            case FACTORED_FORM -> bounded((long) countOperators(parsedAst)
                + topLevelAdditionPenalty(parsedAst) - factorizationBonus(parsedAst));
            // At most five bonus units per operator leave a positive cost.
            case SYMMETRY -> bounded(6L * countOperators(parsedAst) - symmetryBonus(parsedAst));
        };
    }

    private static int bounded(long value) {
        return (int) Math.clamp(value, 0, Integer.MAX_VALUE);
    }

    static int countOperators(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            return (int) Math.min(Integer.MAX_VALUE, 1L + countOperators(binary.left()) + countOperators(binary.right()));
        }
        if (expression instanceof FunctionExpr function) {
            long total = 1;
            for (Expr argument : function.arguments()) {
                total += countOperators(argument);
            }
            return (int) Math.min(Integer.MAX_VALUE, total);
        }
        return 0;
    }
    static int depth(Expr expression, int leafDepth) {
        if (expression instanceof BinaryExpr binary) {
            return 1 + Math.max(depth(binary.left(), leafDepth), depth(binary.right(), leafDepth));
        }
        if (expression instanceof FunctionExpr function) {
            int max = 0;
            for (Expr argument : function.arguments()) {
                max = Math.max(max, depth(argument, leafDepth));
            }
            return 1 + max;
        }
        return leafDepth;
    }
    /** Each top-level summand beyond the first adds friction. */
    private static int topLevelAdditionPenalty(Expr expression) {
        int summands = countTopLevelSummands(expression);
        return Math.max(0, summands - 1) * 2;
    }

    private static int countTopLevelSummands(Expr expression) {
        if (expression instanceof BinaryExpr binary
            && (binary.operator() == BinaryOperator.ADD || binary.operator() == BinaryOperator.SUB)) {
            return countTopLevelSummands(binary.left()) + countTopLevelSummands(binary.right());
        }
        return 1;
    }

    /**
     * Reward multiplications and powers between non-trivial sub-expressions
     * (i.e. structures that look like factors, not bare variables or
     * coefficients).
     */
    private static int factorizationBonus(Expr expression) {
        int bonus = 0;
        if (expression instanceof BinaryExpr binary) {
            if (binary.operator() == BinaryOperator.MUL
                && isNonTrivial(binary.left()) && isNonTrivial(binary.right())) {
                bonus += 3;
            }
            if (binary.operator() == BinaryOperator.POW
                && isNonTrivial(binary.left())
                && binary.right() instanceof NumberExpr number
                && number.value().compareTo(de.regelsuche.scalar.ExactRational.integer(2)) >= 0) {
                bonus += 2;
            }
            bonus += factorizationBonus(binary.left());
            bonus += factorizationBonus(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            for (Expr argument : function.arguments()) {
                bonus += factorizationBonus(argument);
            }
        }
        return bonus;
    }

    private static boolean isNonTrivial(Expr expression) {
        if (expression instanceof NumberExpr || expression instanceof VariableExpr) {
            return false;
        }
        return true;
    }
    private static int symmetryBonus(Expr expression) {
        int bonus = 0;
        if (expression instanceof BinaryExpr binary) {
            if (binary.operator() == BinaryOperator.ADD || binary.operator() == BinaryOperator.MUL) {
                List<String> operands = collect(binary, binary.operator());
                bonus += commutativeBonus(operands);
                bonus += palindromeBonus(operands);
            }
            bonus += symmetryBonus(binary.left());
            bonus += symmetryBonus(binary.right());
        } else if (expression instanceof FunctionExpr function) {
            for (Expr argument : function.arguments()) {
                bonus += symmetryBonus(argument);
            }
        }
        return bonus;
    }

    private static List<String> collect(Expr expression, BinaryOperator operator) {
        List<String> result = new ArrayList<>();
        if (expression instanceof BinaryExpr binary && binary.operator() == operator) {
            result.addAll(collect(binary.left(), operator));
            result.addAll(collect(binary.right(), operator));
        } else {
            result.add(ExpressionFormatter.format(expression));
        }
        return result;
    }

    /**
     * Sums or products with three or more operands whose canonical strings
     * have nearly equal lengths get a small bonus. This is a textual
     * uniformity heuristic, not a polynomial coefficient symmetry test.
     */
    private static int commutativeBonus(List<String> operands) {
        if (operands.size() < 3) {
            return 0;
        }
        List<Integer> lengths = operands.stream().map(String::length).toList();
        int min = Collections.min(lengths);
        int max = Collections.max(lengths);
        if (max - min <= 1) {
            return 2;
        }
        return 0;
    }

    /**
     * Reward palindromic operand sequences (e.g. {@code a + b + a},
     * {@code (x+1) + 2*x + (x+1)}) — exact operand match forward/backward.
     */
    private static int palindromeBonus(List<String> operands) {
        if (operands.size() < 3) {
            return 0;
        }
        int left = 0;
        int right = operands.size() - 1;
        boolean anyMirrored = false;
        while (left < right) {
            if (!operands.get(left).equals(operands.get(right))) {
                return 0;
            }
            anyMirrored = true;
            left++;
            right--;
        }
        return anyMirrored ? 3 : 0;
    }

}
