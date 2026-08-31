package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactMonomialSquareExposureOperatorTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void exposesEligibleSquareOccurrencesWithoutChangingTheirValue() {
        List<Transformation> transformations =
            new ExactMonomialSquareExposureOperator()
                .generateCandidates("x^4 + 4*y^4");

        assertContains(
            transformations,
            "(x^2)^2 + 4*y^4");
        assertContains(
            transformations,
            "x^4 + (2*y^2)^2");
        assertTrue(transformations.stream().allMatch(transformation ->
            ExactMonomialSquareExposureOperator.RULE_ID.equals(
                transformation.rule())
                && transformation.equivalencePreservingByConstruction()
                && transformation.assumptions().isEmpty()
                && transformation.primitiveRuleIds().equals(List.of(
                    ExactMonomialSquareExposureOperator.RULE_ID))));
        assertEquals(
            transformations.size(),
            transformations.stream()
                .map(Transformation::applicationKey)
                .distinct()
                .count());
    }

    @Test
    void exposesTheCrossTermNeededAfterSquareCompletion() {
        List<Transformation> transformations =
            new ExactMonomialSquareExposureOperator()
                .generateCandidates("2*x^2*(2*y^2)");

        assertContains(transformations, "(2*x*y)^2");
    }

    @Test
    void skipsAlreadyExplicitSquaresAndNonSquares() {
        assertTrue(new ExactMonomialSquareExposureOperator()
            .generateCandidates("x^2 + 2*y^2")
            .isEmpty());
    }

    @Test
    void appliesTheConfiguredCandidateLimitDeterministically() {
        List<Transformation> transformations =
            new ExactMonomialSquareExposureOperator(1)
                .generateCandidates("x^4 + 4*y^4");

        assertEquals(1, transformations.size());
        assertContains(transformations, "(x^2)^2 + 4*y^4");
    }

    @Test
    void applicationIdentityBindsTheCompleteSourceSyntax() {
        ExactMonomialSquareExposureOperator operator =
            new ExactMonomialSquareExposureOperator();

        String first = operator.generateCandidates("x^4 + y")
            .getFirst()
            .applicationKey();
        String sameFormattedSource = operator.generateCandidates("x^4+y")
            .getFirst()
            .applicationKey();
        String otherSource = operator.generateCandidates("x^4 + z")
            .getFirst()
            .applicationKey();

        assertEquals(first, sameFormattedSource);
        assertNotEquals(first, otherSource);
    }

    private void assertContains(
        List<Transformation> transformations,
        String expectedExpression
    ) {
        Expr expected = parser.parseTerm(expectedExpression);
        assertTrue(
            transformations.stream()
                .map(Transformation::transformedExpression)
                .map(parser::parseTerm)
                .anyMatch(expected::equals),
            () -> expectedExpression + " not found in " + transformations);
    }
}
