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
 * <p>A sum {@code a + b} or product {@code a * b} is considered symmetric
 * when its canonical operands sort identically once viewed as a multiset —
 * which is automatically the case after PR 1 canonicalization. The cost
 * model goes one step further and rewards "palindromic" polynomial
 * coefficient sequences (e.g. {@code 1 + 3*x + 3*x^2 + x^3}) which the
 * search would otherwise treat as a long sum.</p>
 *
 * <p>Used by {@link TransformationGoal#PROOF_FRIENDLY PROOF_FRIENDLY},
 * where symmetric structures simplify case-splits in subsequent proof
 * steps.</p>
 */
public final class SymmetryCost implements CostModel {

    @Override
    public int cost(String expression, Expr parsedAst, ExpressionScore score) {
        if (parsedAst == null) {
            return Math.max(0, score.operatorCount());
        }
        int operators = countOperators(parsedAst);
        int symmetryBonus = symmetryBonus(parsedAst);
        return operators - symmetryBonus;
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
     * sort into a multiset of equal-length tokens get a small bonus. The
     * post-PR-1 canonicalizer already produces a sorted representation, so
     * any large sum that looks "rectangular" is rewarded.
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

    /** Two operands have the same "shape" — unused after the palindrome
     * tightening above, kept for binary compatibility within the package. */
    @SuppressWarnings("unused")
    private boolean sameShape(String left, String right) {
        return skeleton(left).equals(skeleton(right));
    }

    private String skeleton(String operand) {
        StringBuilder builder = new StringBuilder(operand.length());
        for (int i = 0; i < operand.length(); i++) {
            char character = operand.charAt(i);
            if (Character.isDigit(character)) {
                builder.append('n');
            } else if (Character.isLetter(character)) {
                builder.append('v');
            } else if (!Character.isWhitespace(character)) {
                builder.append(character);
            }
        }
        return builder.toString();
    }
}
