package de.regelsuche.discovery.representation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Source-minus-candidate reductions. Positive values improve the corresponding
 * description dimension, except {@code repeatedSemanticValueSavingsIncrease},
 * where positive means the candidate exposes more shareable repetition.
 */
public record SemanticCompressionDelta(
    int tokenCountReduction,
    int astNodeCountReduction,
    int operatorCountReduction,
    int numericBitLengthReduction,
    int distinctSemanticValueReduction,
    int repeatedSemanticValueSavingsIncrease
) {
    public static SemanticCompressionDelta between(
        SemanticDescriptionMetrics source,
        SemanticDescriptionMetrics candidate
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(candidate, "candidate");
        return new SemanticCompressionDelta(
            source.tokenCount() - candidate.tokenCount(),
            source.astNodeCount() - candidate.astNodeCount(),
            source.operatorCount() - candidate.operatorCount(),
            source.numericBitLength() - candidate.numericBitLength(),
            source.distinctSemanticValues() - candidate.distinctSemanticValues(),
            candidate.repeatedSemanticValueSavings() - source.repeatedSemanticValueSavings()
        );
    }

    public List<String> improvedDimensions() {
        List<String> dimensions = new ArrayList<>();
        addPositive(dimensions, "TOKEN_COUNT", tokenCountReduction);
        addPositive(dimensions, "AST_NODE_COUNT", astNodeCountReduction);
        addPositive(dimensions, "OPERATOR_COUNT", operatorCountReduction);
        addPositive(dimensions, "NUMERIC_BIT_LENGTH", numericBitLengthReduction);
        addPositive(dimensions, "DISTINCT_SEMANTIC_VALUES", distinctSemanticValueReduction);
        addPositive(
            dimensions,
            "REPEATED_SEMANTIC_VALUE_SAVINGS",
            repeatedSemanticValueSavingsIncrease
        );
        return List.copyOf(dimensions);
    }

    public List<String> regressedDimensions() {
        List<String> dimensions = new ArrayList<>();
        addNegative(dimensions, "TOKEN_COUNT", tokenCountReduction);
        addNegative(dimensions, "AST_NODE_COUNT", astNodeCountReduction);
        addNegative(dimensions, "OPERATOR_COUNT", operatorCountReduction);
        addNegative(dimensions, "NUMERIC_BIT_LENGTH", numericBitLengthReduction);
        addNegative(dimensions, "DISTINCT_SEMANTIC_VALUES", distinctSemanticValueReduction);
        addNegative(
            dimensions,
            "REPEATED_SEMANTIC_VALUE_SAVINGS",
            repeatedSemanticValueSavingsIncrease
        );
        return List.copyOf(dimensions);
    }

    public boolean hasStructuralRegression() {
        return astNodeCountReduction < 0
            || operatorCountReduction < 0
            || distinctSemanticValueReduction < 0;
    }

    private static void addPositive(List<String> values, String name, int value) {
        if (value > 0) {
            values.add(name);
        }
    }

    private static void addNegative(List<String> values, String name, int value) {
        if (value < 0) {
            values.add(name);
        }
    }
}
