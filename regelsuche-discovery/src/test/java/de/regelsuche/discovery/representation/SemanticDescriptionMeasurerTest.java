package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SemanticDescriptionMeasurerTest {
    private final SemanticDescriptionMeasurer measurer =
        new SemanticDescriptionMeasurer();

    @Test
    void exposesSharingAwareRawDimensions() {
        SemanticDescriptionMetrics metrics =
            measurer.measure("(x + 1) * (x + 1)");

        assertTrue(metrics.semanticValueOccurrences()
            > metrics.distinctSemanticValues());
        assertEquals(
            metrics.semanticValueOccurrences() - metrics.distinctSemanticValues(),
            metrics.repeatedSemanticValueSavings());
        assertTrue(metrics.variableSymbols().contains("x"));
    }

    @Test
    void compactSquareImprovesSeveralIndependentDimensions() {
        SemanticDescriptionMetrics expanded =
            measurer.measure("a^2 + 2*a*b + b^2");
        SemanticDescriptionMetrics compact =
            measurer.measure("(a + b)^2");

        assertTrue(expanded.tokenCount() > compact.tokenCount());
        assertTrue(expanded.astNodeCount() > compact.astNodeCount());
        assertTrue(expanded.operatorCount() > compact.operatorCount());
        assertTrue(expanded.distinctSemanticValues()
            > compact.distinctSemanticValues());
    }

    @Test
    void whitespaceDoesNotChangeTheMeasuredExpression() {
        assertEquals(
            measurer.measure("a+b"),
            measurer.measure("  a   +   b  "));
    }
}
