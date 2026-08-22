package de.regelsuche.transform;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Expr;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.parse.ExpressionFormatter;
import java.util.Map;
import java.util.Objects;

/**
 * One typed side-condition template instantiated from a complete applicability
 * match.
 */
public record RequiredAssumptionTemplate(
    Assumption.Kind kind,
    PatternExpr expressionPattern
) {
    public RequiredAssumptionTemplate {
        kind = Objects.requireNonNull(kind, "kind");
        expressionPattern = Objects.requireNonNull(
            expressionPattern, "expressionPattern");
        if (!supported(kind)) {
            throw new IllegalArgumentException(
                "unsupported applicability assumption kind: " + kind);
        }
    }

    public static RequiredAssumptionTemplate nonZero(
        PatternExpr expressionPattern
    ) {
        return new RequiredAssumptionTemplate(
            Assumption.Kind.NON_ZERO, expressionPattern);
    }

    public static RequiredAssumptionTemplate positive(
        PatternExpr expressionPattern
    ) {
        return new RequiredAssumptionTemplate(
            Assumption.Kind.POSITIVE, expressionPattern);
    }

    public static RequiredAssumptionTemplate nonNegative(
        PatternExpr expressionPattern
    ) {
        return new RequiredAssumptionTemplate(
            Assumption.Kind.NON_NEGATIVE, expressionPattern);
    }

    public Assumption instantiate(Map<String, Expr> bindings) {
        String expression = ExpressionFormatter.format(
            expressionPattern.instantiate(
                Objects.requireNonNull(bindings, "bindings")));
        return switch (kind) {
            case NON_ZERO -> Assumption.nonZero(expression);
            case POSITIVE -> Assumption.positive(expression);
            case NON_NEGATIVE -> Assumption.nonNegative(expression);
            case INTEGER -> Assumption.integer(expression);
            case NATURAL -> Assumption.natural(expression);
            case INVERTIBLE -> Assumption.invertible(expression);
            case REAL -> Assumption.real(expression);
            case RATIONAL -> Assumption.rational(expression);
            default -> throw new IllegalStateException(
                "unsupported applicability assumption kind: " + kind);
        };
    }

    public String contentHash() {
        return RuleInventoryFingerprint.assumptionTemplateContentHash(
            kind, expressionPattern);
    }

    private static boolean supported(Assumption.Kind kind) {
        return switch (kind) {
            case NON_ZERO, POSITIVE, NON_NEGATIVE, INTEGER, NATURAL,
                    INVERTIBLE, REAL, RATIONAL -> true;
            default -> false;
        };
    }
}
