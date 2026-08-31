package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class SumOfSquaresDifferenceCompletionOperatorTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final SumOfSquaresDifferenceCompletionOperator operator =
        new SumOfSquaresDifferenceCompletionOperator();

    @Test
    void completesAnExplicitSquareSumAroundADifference() {
        List<Transformation> transformations =
            operator.generateCandidates("x^2 + y^2");

        assertEquals(1, transformations.size());
        assertExpression(
            "(x - y)^2 + 2*x*y",
            transformations.getFirst().transformedExpression());
        assertEquals(
            List.of(SumOfSquaresDifferenceCompletionOperator.RULE_ID),
            transformations.getFirst().primitiveRuleIds());
        assertTrue(transformations.getFirst()
            .equivalencePreservingByConstruction());
        assertTrue(transformations.getFirst().assumptions().isEmpty());
    }

    @Test
    void bindsCompleteSquareBasesRatherThanOnlyVariables() {
        List<Transformation> transformations = operator.generateCandidates(
            "(m + 1)^2 + sin(t)^2");

        assertEquals(1, transformations.size());
        assertExpression(
            "((m + 1) - sin(t))^2 + 2*(m + 1)*sin(t)",
            transformations.getFirst().transformedExpression());
    }

    @Test
    void acceptsEquivalentNumericSquareExponentFormatting() {
        List<Transformation> transformations =
            operator.generateCandidates("x^2.0 + y^2");

        assertEquals(1, transformations.size());
        assertExpression(
            "(x - y)^2 + 2*x*y",
            transformations.getFirst().transformedExpression());
    }

    @Test
    void usesCompactStableTransitionIdentity() {
        Transformation compact = operator
            .generateCandidates("x^2+y^2")
            .getFirst();
        Transformation reformatted = operator
            .generateCandidates("x ^ 2 + y ^ 2")
            .getFirst();
        Transformation different = operator
            .generateCandidates("a^2+b^2")
            .getFirst();

        assertEquals(compact.applicationKey(), reformatted.applicationKey());
        assertNotEquals(compact.applicationKey(), different.applicationKey());
        assertTrue(compact.applicationKey().startsWith(
            SumOfSquaresDifferenceCompletionOperator.RULE_ID
                + ":syntax-v1:"));
        assertTrue(compact.applicationKey().contains("->syntax-v1:"));
        assertTrue(compact.applicationKey().length() < 120,
            compact.applicationKey());
        assertFalse(compact.applicationKey().contains("x ^ 2"));
    }

    @Test
    void rejectsNonSquaresDifferencesAndAdditionalTerms() {
        for (String expression : List.of(
            "x^3 + y^2",
            "x^2 - y^2",
            "x^2 + y^2 + z^2",
            "")) {
            assertTrue(operator.generateCandidates(expression).isEmpty(),
                expression);
        }
    }

    private void assertExpression(String expected, String actual) {
        Expr expectedAst = parser.parseTerm(expected);
        assertEquals(expectedAst, parser.parseTerm(actual), actual);
    }
}
