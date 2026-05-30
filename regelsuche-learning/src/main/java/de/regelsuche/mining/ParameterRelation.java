package de.regelsuche.mining;

import java.util.Locale;
import java.util.Optional;

/** Structured parameter relation used by learned macro guards and validators. */
public record ParameterRelation(
    String left,
    Operator operator,
    String right,
    RelationType relationType
) {
    public ParameterRelation {
        if (left == null || left.isBlank()) {
            throw new IllegalArgumentException("left must not be blank");
        }
        if (right == null || right.isBlank()) {
            throw new IllegalArgumentException("right must not be blank");
        }
        operator = operator == null ? Operator.EQUALS : operator;
        relationType = relationType == null ? infer(left, operator, right) : relationType;
        left = left.trim();
        right = right.trim();
    }

    public String display() {
        return left + " " + operator.symbol() + " " + right;
    }

    public static Optional<ParameterRelation> parse(String relation) {
        if (relation == null || relation.isBlank()) {
            return Optional.empty();
        }
        for (Operator operator : Operator.values()) {
            int separator = relation.indexOf(operator.symbol());
            if (separator > 0 && separator < relation.length() - operator.symbol().length()) {
                String left = relation.substring(0, separator).trim();
                String right = relation.substring(separator + operator.symbol().length()).trim();
                return Optional.of(new ParameterRelation(left, operator, right, infer(left, operator, right)));
            }
        }
        return Optional.empty();
    }

    private static RelationType infer(String left, Operator operator, String right) {
        if (operator == Operator.NOT_EQUALS) {
            return RelationType.NON_ZERO_ASSUMPTION;
        }
        String compact = right.replace(" ", "").toUpperCase(Locale.ROOT);
        if (compact.matches("[A-Z]\\+1")) {
            return RelationType.UNIT_STEP;
        }
        if (compact.matches("[A-Z][+-]\\d+")) {
            return RelationType.AFFINE_OFFSET;
        }
        if (compact.matches("[A-Z]\\^2")) {
            return RelationType.POWER;
        }
        return RelationType.EXPRESSION;
    }

    public enum Operator {
        NOT_EQUALS("!="),
        EQUALS("=");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    public enum RelationType {
        UNIT_STEP,
        AFFINE_OFFSET,
        POWER,
        NON_ZERO_ASSUMPTION,
        EXPRESSION
    }
}
