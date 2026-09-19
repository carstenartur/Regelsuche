package de.regelsuche.mining;

import de.regelsuche.ast.BinaryOperator;
import java.util.ArrayList;
import java.util.List;

/**
 * A quotient spelling of a multiplicative rule pattern containing exact reciprocals.
 * This changes representation only: no factor is cancelled, no denominator is
 * distributed, and no exponent other than the literal -1 is interpreted.
 */
final class ReciprocalPatternView {
    private ReciprocalPatternView() {}

    static RulePatternNode quotient(RulePatternNode node) {
        if (node instanceof PatternFunction function) {
            return new PatternFunction(function.name(), function.arguments().stream()
                .map(ReciprocalPatternView::quotient).toList());
        }
        if (!(node instanceof PatternBinary binary)) return node;
        if (binary.op() == BinaryOperator.MUL || isReciprocal(binary)) {
            var numerators = new ArrayList<RulePatternNode>();
            var denominators = new ArrayList<RulePatternNode>();
            collectFactors(node, numerators, denominators);
            if (!denominators.isEmpty()) {
                RulePatternNode result = product(numerators);
                // Keep separate reciprocal factors separate: a/(b/c) stays a/(b/c),
                // not a*c/b, which could erase c != 0 from the domain.
                for (RulePatternNode denominator : denominators) {
                    result = new PatternBinary(result, BinaryOperator.DIV, denominator);
                }
                return result;
            }
        }
        return new PatternBinary(quotient(binary.left()), binary.op(), quotient(binary.right()));
    }

    private static void collectFactors(RulePatternNode node, List<RulePatternNode> numerator,
            List<RulePatternNode> denominator) {
        if (node instanceof PatternBinary binary && binary.op() == BinaryOperator.MUL) {
            collectFactors(binary.left(), numerator, denominator);
            collectFactors(binary.right(), numerator, denominator);
        } else if (node instanceof PatternBinary binary && isReciprocal(binary)) {
            denominator.add(quotient(binary.left()));
        } else {
            numerator.add(quotient(node));
        }
    }

    private static boolean isReciprocal(PatternBinary binary) {
        if (binary.op() != BinaryOperator.POW) return false;
        RulePatternNode exponent = binary.right();
        if (exponent instanceof PatternNumber literal) return literal.value() == -1;
        // ExpressionParser represents a negative literal as 0 - literal.
        return exponent instanceof PatternBinary minus && minus.op() == BinaryOperator.SUB
            && minus.left() instanceof PatternNumber zero && zero.value() == 0
            && minus.right() instanceof PatternNumber one && one.value() == 1;
    }

    private static RulePatternNode product(List<RulePatternNode> factors) {
        if (factors.isEmpty()) return new PatternNumber(1);
        RulePatternNode result = factors.getFirst();
        for (int index = 1; index < factors.size(); index++) {
            result = new PatternBinary(result, BinaryOperator.MUL, factors.get(index));
        }
        return result;
    }
}
