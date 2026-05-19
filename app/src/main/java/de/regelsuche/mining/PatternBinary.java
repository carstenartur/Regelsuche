package de.regelsuche.mining;

import de.regelsuche.ast.BinaryOperator;

public record PatternBinary(RulePatternNode left, BinaryOperator op, RulePatternNode right) implements RulePatternNode {
    public PatternBinary {
        if (left == null || op == null || right == null) {
            throw new IllegalArgumentException("left, op and right must not be null");
        }
    }
}
