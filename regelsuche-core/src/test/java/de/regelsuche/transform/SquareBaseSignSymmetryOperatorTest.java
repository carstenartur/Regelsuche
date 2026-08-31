package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class SquareBaseSignSymmetryOperatorTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final SquareBaseSignSymmetryOperator operator =
        new SquareBaseSignSymmetryOperator();

    @Test
    void negatesACompoundSquareBaseExactlyOnce() {
        List<Transformation> transformations =
            operator.generateCandidates("(a*b)^2");

        assertEquals(1, transformations.size());
        Transformation transformation = transformations.getFirst();
        assertExpression(
            "(-(a*b))^2",
            transformation.transformedExpression());
        assertFalse(
            parser.parseTerm("(a*b)^2").equals(
                parser.parseTerm(
                    transformation.transformedExpression())),
            "the symmetry must expose a distinct structural representation");
        assertEquals(
            SquareBaseSignSymmetryOperator.RULE_ID,
            transformation.rule());
        assertEquals(RewriteKind.NORMALIZE, transformation.kind());
        assertTrue(transformation.mayIncreaseComplexity());
        assertEquals(2, transformation.estimatedCostDelta());
        assertTrue(transformation.equivalencePreservingByConstruction());
        assertTrue(transformation.assumptions().isEmpty());
        assertEquals(
            List.of(SquareBaseSignSymmetryOperator.RULE_ID),
            transformation.primitiveRuleIds());
    }

    @Test
    void refusesAnAlreadyNegatedBaseAndOtherNonCandidates() {
        for (String expression : List.of(
            "(-x)^2",
            "2^2",
            "x^3",
            "x + y",
            "",
            "(")) {
            assertTrue(operator.generateCandidates(expression).isEmpty(),
                expression);
        }
    }

    @Test
    void usesCompactWhitespaceStableTransitionIdentity() {
        Transformation compact = operator
            .generateCandidates("(a*b)^2")
            .getFirst();
        Transformation spaced = operator
            .generateCandidates("(a * b) ^ 2")
            .getFirst();

        assertEquals(
            compact.transformedExpression(),
            spaced.transformedExpression());
        assertEquals(
            compact.applicationKey(),
            spaced.applicationKey());
        assertTrue(compact.applicationKey().startsWith(
            SquareBaseSignSymmetryOperator.RULE_ID
                + ":syntax-v1:"));
        assertTrue(compact.applicationKey().contains("->syntax-v1:"));
        assertTrue(compact.applicationKey().length() < 110,
            compact.applicationKey());
        assertFalse(compact.applicationKey().contains("a * b"));
    }

    @Test
    void subtreeExecutionCanChooseEitherSquareOccurrence() {
        List<Transformation> transformations =
            new SubtreeHypothesisOperator(operator)
                .generateCandidates("x^2 + y^2");

        assertEquals(2, transformations.size());
        assertContains(transformations, "(-x)^2 + y^2");
        assertContains(transformations, "x^2 + (-y)^2");
        assertTrue(transformations.stream().allMatch(transformation ->
            transformation.rule().equals(
                SquareBaseSignSymmetryOperator.RULE_ID)
                && transformation.applicationKey().startsWith(
                    "subtree-v1:"
                        + SquareBaseSignSymmetryOperator.RULE_ID
                        + ":")));
    }

    private void assertContains(
        List<Transformation> transformations,
        String expected
    ) {
        Expr expectedAst = parser.parseTerm(expected);
        assertTrue(transformations.stream()
            .map(Transformation::transformedExpression)
            .map(parser::parseTerm)
            .anyMatch(expectedAst::equals),
            () -> expected + " not found in " + transformations);
    }

    private void assertExpression(String expected, String actual) {
        assertEquals(parser.parseTerm(expected), parser.parseTerm(actual), actual);
    }
}
