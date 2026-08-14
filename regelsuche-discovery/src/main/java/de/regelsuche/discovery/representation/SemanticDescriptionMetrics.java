package de.regelsuche.discovery.representation;

import java.util.List;
import java.util.Objects;

/**
 * Raw, non-aggregated description measures for one exact parsed expression.
 *
 * <p>No field is an authoritative universal simplicity score. Consumers must
 * retain the complete vector and bind any later aggregation policy separately.</p>
 */
public record SemanticDescriptionMetrics(
    String normalizedExpression,
    int tokenCount,
    int astNodeCount,
    int operatorCount,
    int numericBitLength,
    int semanticValueOccurrences,
    int distinctSemanticValues,
    int repeatedSemanticValueSavings,
    List<String> variableSymbols,
    List<String> functionSymbols
) {
    public SemanticDescriptionMetrics {
        normalizedExpression = requireText(normalizedExpression, "normalizedExpression");
        if (tokenCount < 1 || astNodeCount < 1 || operatorCount < 0
                || numericBitLength < 0 || semanticValueOccurrences < 1
                || distinctSemanticValues < 1 || repeatedSemanticValueSavings < 0) {
            throw new IllegalArgumentException("description metrics must be non-negative and non-empty");
        }
        if (distinctSemanticValues > semanticValueOccurrences) {
            throw new IllegalArgumentException("distinct semantic values cannot exceed occurrences");
        }
        if (repeatedSemanticValueSavings
                != semanticValueOccurrences - distinctSemanticValues) {
            throw new IllegalArgumentException("repeated-value savings do not balance");
        }
        variableSymbols = List.copyOf(Objects.requireNonNull(variableSymbols, "variableSymbols"));
        functionSymbols = List.copyOf(Objects.requireNonNull(functionSymbols, "functionSymbols"));
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
