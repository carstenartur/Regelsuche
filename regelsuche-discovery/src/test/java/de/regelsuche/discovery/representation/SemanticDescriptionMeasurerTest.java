package de.regelsuche.discovery.representation;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static de.regelsuche.ast.BinaryOperator.MUL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
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
    void countsSharedAstObjectAtEverySyntaxPosition() {
        var shared = new BinaryExpr(new VariableExpr("x"), ADD, new NumberExpr(1));
        var root = new BinaryExpr(shared, MUL, shared);

        SemanticDescriptionMetrics metrics = measurer.measure(root);

        assertEquals(metrics.astNodeCount(), metrics.semanticValueOccurrences());
        assertTrue(metrics.repeatedSemanticValueSavings() > 0);
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
