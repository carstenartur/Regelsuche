package de.regelsuche.discovery.representation;

import java.util.List;
import java.util.Objects;

/** Raw description vector; no field is an authoritative simplicity score. */
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
        normalizedExpression = RepresentationContracts.text(
            normalizedExpression, "normalizedExpression");
        if (tokenCount < 1 || astNodeCount < 1 || operatorCount < 0
                || numericBitLength < 0 || semanticValueOccurrences < 1
                || distinctSemanticValues < 1 || repeatedSemanticValueSavings < 0) {
            throw new IllegalArgumentException(
                "description metrics must be non-negative and non-empty");
        }
        if (distinctSemanticValues > semanticValueOccurrences
                || repeatedSemanticValueSavings
                    != semanticValueOccurrences - distinctSemanticValues) {
            throw new IllegalArgumentException(
                "semantic occurrence and sharing metrics do not balance");
        }
        variableSymbols = List.copyOf(
            Objects.requireNonNull(variableSymbols, "variableSymbols"));
        functionSymbols = List.copyOf(
            Objects.requireNonNull(functionSymbols, "functionSymbols"));
    }
}
