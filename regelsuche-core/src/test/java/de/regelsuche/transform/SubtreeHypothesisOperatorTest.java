package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubtreeHypothesisOperatorTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void rewritesEveryMatchingOccurrenceAndPreservesDelegateMetadata() {
        List<Transformation> transformations =
            new SubtreeHypothesisOperator(cancelDelegate())
                .generateCandidates("f(x / x) + (x / x)");

        assertEquals(2, transformations.size());
        assertContains(transformations, "f(1) + x / x");
        assertContains(transformations, "f(x / x) + 1");
        assertTrue(transformations.stream().allMatch(transformation ->
            transformation.rule().equals("learned_cancel")
                && transformation.kind() == RewriteKind.SIMPLIFY
                && !transformation.mayIncreaseComplexity()
                && transformation.estimatedCostDelta() == -2
                && transformation.equivalencePreservingByConstruction()
                && transformation.assumptions().equals(List.of("x != 0"))
                && transformation.packId().equals("test-pack")
                && transformation.license().equals("MIT")
                && transformation.primitiveRuleIds().equals(
                    List.of("primitive_cancel", "primitive_one"))));
        assertEquals(
            2,
            transformations.stream()
                .map(Transformation::applicationKey)
                .distinct()
                .count());
        assertTrue(transformations.stream().allMatch(transformation ->
            transformation.applicationKey().startsWith(
                "subtree-v1:learned_cancel:")
                && !transformation.applicationKey().contains("f(")));
    }

    @Test
    void alsoSupportsTheRootAndNormalizesWhitespaceInItsIdentity() {
        SubtreeHypothesisOperator operator = new SubtreeHypothesisOperator(
            new SumOfSquaresCompletionOperator());

        List<Transformation> compact = operator.generateCandidates(
            "x^2+y^2");
        List<Transformation> spaced = operator.generateCandidates(
            "x ^ 2 + y ^ 2");

        assertEquals(1, compact.size());
        assertEquals(1, spaced.size());
        assertEquals(
            compact.getFirst().transformedExpression(),
            spaced.getFirst().transformedExpression());
        assertEquals(
            compact.getFirst().applicationKey(),
            spaced.getFirst().applicationKey());
        assertTrue(compact.getFirst().applicationKey().contains(":root:"));
    }

    @Test
    void appliesTheCandidateLimitInDeterministicPreorder() {
        List<Transformation> transformations =
            new SubtreeHypothesisOperator(cancelDelegate(), 1)
                .generateCandidates("f(x / x) + (x / x)");

        assertEquals(1, transformations.size());
        assertContains(transformations, "f(1) + x / x");
    }

    @Test
    void skipsInvalidDelegatedOutputAndNonMatchingExpressions() {
        HypothesisOperator invalid = expression -> expression.equals("x")
            ? List.of(new Transformation("invalid", "("))
            : List.of();
        assertTrue(new SubtreeHypothesisOperator(invalid)
            .generateCandidates("f(x)")
            .isEmpty());
        assertTrue(new SubtreeHypothesisOperator(cancelDelegate())
            .generateCandidates("f(x / y)")
            .isEmpty());
    }

    @Test
    void clampsNonPositiveCandidateLimitsToDisabled() {
        for (int limit : List.of(-1, 0)) {
            assertTrue(
                new SubtreeHypothesisOperator(cancelDelegate(), limit)
                    .generateCandidates("x / x")
                    .isEmpty(),
                () -> "limit=" + limit);
        }
    }

    private HypothesisOperator cancelDelegate() {
        Expr pattern = parser.parseTerm("x / x");
        return expression -> {
            Expr actual;
            try {
                actual = parser.parseTerm(expression);
            } catch (IllegalArgumentException exception) {
                return List.of();
            }
            if (!pattern.equals(actual)) {
                return List.of();
            }
            return List.of(new Transformation(
                "learned_cancel",
                "1",
                RewriteKind.SIMPLIFY,
                false,
                -2,
                true,
                "delegate-cancel-v1",
                List.of("x != 0"),
                "test-pack",
                "MIT",
                List.of("primitive_cancel", "primitive_one")));
        };
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
