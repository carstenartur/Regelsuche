package de.regelsuche.scoring.cost;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.scoring.ExpressionScore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Rewards structurally symmetric expressions over asymmetric ones.
 *
 * <p>The heuristic rewards sums and products with similarly sized operand
 * strings and exact palindromic operand sequences such as {@code a + b + a}.
 * It charges six units per operator before subtracting at most five bonus
 * units per operator, so symmetry rewards never produce negative costs.</p>
 *
 * <p>Used by {@link TransformationGoal#PROOF_FRIENDLY PROOF_FRIENDLY},
 * where symmetric structures simplify case-splits in subsequent proof
 * steps.</p>
 */
public final class SymmetryCost implements CostModel {

    @Override
    public int cost(String expression, Expr parsedAst, ExpressionScore score) {
        if (parsedAst == null) {
            return (int) Math.clamp(6L * score.operatorCount(), 0, Integer.MAX_VALUE);
        }
        int operators = countOperators(parsedAst);
        int symmetryBonus = symmetryBonus(parsedAst);
        // Each operator has at most five bonus units. Charging six units
        // leaves a positive structural cost while preserving symmetry rewards.
        return (int) Math.min(Integer.MAX_VALUE, 6L * operators - symmetryBonus);
    }

    @Override
    public String id() {
        return "symmetry";
    }

    private int countOperators(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            return 1 + countOperators(binary.left()) + countOperators(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            int total = 1;
            for (Expr argument : function.arguments()) {
                total += countOperators(argument);
            }
            return total;
        }
        return 0;
    }

    private int symmetryBonus(Expr expression) {
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

    private List<String> collect(Expr expression, BinaryOperator operator) {
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
    private int commutativeBonus(List<String> operands) {
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
    private int palindromeBonus(List<String> operands) {
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
