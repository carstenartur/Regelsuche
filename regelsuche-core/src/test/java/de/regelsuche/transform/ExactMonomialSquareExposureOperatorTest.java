package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
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
    void optInExposesRationalMonomialSquaresAndChargesRootSearch() {
        var work = new java.util.concurrent.atomic.AtomicLong();
        var operator = new ExactMonomialSquareExposureOperator(8, work::addAndGet);
        assertContains(operator.generateCandidates("(4/9)*y*y"), "(2/3*y)^2");
        assertContains(operator.generateCandidates("2.25*a*a"), "(1.5*a)^2");
        assertTrue(operator.generateCandidates("(2/3)*x*x").isEmpty());
        assertTrue(work.get() > 0);
        assertTrue(new ExactMonomialSquareExposureOperator().generateCandidates("(4/9)*y*y").isEmpty(),
            "the existing integer-only mode must retain its behavior");
    }

    @Test
    void exposesIntegralResultsWhenLegacySyntaxCannotExpressTheFraction() {
        var operator = new ExactMonomialSquareExposureOperator(8, units -> { });
        for (String source : List.of("(4/1)*y*y", "(16/4)*y*y", "(4/9)*9*y*y", "0.5*8*y*y")) {
            assertTrue(new ExactMonomialSquareExposureOperator().generateCandidates(source).isEmpty(), source);
            var candidates = operator.generateCandidates(source);
            assertTrue(!candidates.isEmpty(), source);
            assertContains(candidates, "(2*y)^2");
        }
        assertTrue(operator.generateCandidates("4*y*y").isEmpty(),
            "the separate integer provider already exposes this square");
    }

    @Test
    void rejectsUndefinedOrSymbolicDivisionAndPropagatesWorkMeterFailure() {
        var measured = new ExactMonomialSquareExposureOperator(8, units -> { });
        for (String denominator : List.of("x-x", "y")) {
            for (var candidate : measured.generateCandidates("(4/9)*x*x/(" + denominator + ")")) {
                var root = parser.parseTerm(candidate.transformedExpression());
                assertTrue(root instanceof BinaryExpr division && division.operator() == BinaryOperator.DIV
                    && division.right().equals(parser.parseTerm(denominator)),
                    "rewriting a numerator must preserve the original domain");
            }
        }
        var brokenMeter = new ExactMonomialSquareExposureOperator(8, units -> {
            throw new IllegalArgumentException("work meter failed");
        });
        assertThrows(IllegalArgumentException.class, () -> brokenMeter.generateCandidates("(4/9)*x*x"));
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
    void applicationIdentityBindsNormalizedSourceSyntax() {
        ExactMonomialSquareExposureOperator operator =
            new ExactMonomialSquareExposureOperator();

        String first = operator.generateCandidates("x^4 + y")
            .getFirst()
            .applicationKey();
        String sameNormalizedSource = operator.generateCandidates("x^4+y")
            .getFirst()
            .applicationKey();
        String otherSource = operator.generateCandidates("x^4 + z")
            .getFirst()
            .applicationKey();

        assertEquals(first, sameNormalizedSource);
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
