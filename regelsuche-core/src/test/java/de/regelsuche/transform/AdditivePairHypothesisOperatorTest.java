package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdditivePairHypothesisOperatorTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void appliesTheDelegateToEveryPositiveTermPair() {
        AdditivePairHypothesisOperator operator =
            new AdditivePairHypothesisOperator(
                new SumOfSquaresCompletionOperator(),
                16);

        List<Transformation> candidates =
            operator.generateCandidates("a^2 + b^2 + c^2");

        assertEquals(3, candidates.size());
        assertContains(
            candidates,
            "(a + b)^2 - 2*a*b + c^2");
        assertContains(
            candidates,
            "(a + c)^2 - 2*a*c + b^2");
        assertContains(
            candidates,
            "a^2 + (b + c)^2 - 2*b*c");
        assertTrue(candidates.stream().allMatch(candidate ->
            candidate.applicationKey().startsWith(
                "additive-pair-v1:"
                    + SumOfSquaresCompletionOperator.RULE_ID
                    + ":")));
    }

    @Test
    void neverPairsANegativeSignedTerm() {
        AdditivePairHypothesisOperator operator =
            new AdditivePairHypothesisOperator(
                new SumOfSquaresCompletionOperator(),
                16);

        List<Transformation> candidates =
            operator.generateCandidates("a^2 - b^2 + c^2");

        assertEquals(1, candidates.size());
        assertExpression(
            "(a + c)^2 - 2*a*c - b^2",
            candidates.getFirst().transformedExpression());
    }

    @Test
    void ignoresTheSyntheticZeroOfALeadingUnaryMinus() {
        AdditivePairHypothesisOperator operator =
            new AdditivePairHypothesisOperator(
                new SumOfSquaresCompletionOperator(),
                16);

        List<Transformation> candidates =
            operator.generateCandidates("-x^2 + y^2 + z^2");

        assertEquals(1, candidates.size());
        assertExpression(
            "-x^2 + (y + z)^2 - 2*y*z",
            candidates.getFirst().transformedExpression());
        assertTrue(candidates.getFirst().applicationKey()
            .contains(":pair-1-2:"));
    }

    @Test
    void preservesDelegateMetadataAndPrimitiveAccounting() {
        HypothesisOperator delegate = ignored -> List.of(
            new Transformation(
                "learned_pair",
                "p - q",
                RewriteKind.FACTOR,
                true,
                7,
                false,
                "delegate-key",
                List.of("u != 0"),
                "learned-pack",
                "MIT",
                List.of("primitive-a", "primitive-b")));
        AdditivePairHypothesisOperator operator =
            new AdditivePairHypothesisOperator(delegate, 1);

        Transformation candidate = operator
            .generateCandidates("x + y + z")
            .getFirst();

        assertEquals("learned_pair", candidate.rule());
        assertEquals(RewriteKind.FACTOR, candidate.kind());
        assertTrue(candidate.mayIncreaseComplexity());
        assertEquals(7, candidate.estimatedCostDelta());
        assertFalse(
            candidate.equivalencePreservingByConstruction());
        assertEquals(List.of("u != 0"), candidate.assumptions());
        assertEquals("learned-pack", candidate.packId());
        assertEquals("MIT", candidate.license());
        assertEquals(
            List.of("primitive-a", "primitive-b"),
            candidate.primitiveRuleIds());
    }

    @Test
    void usesWhitespaceStablePairAndTransitionIdentity() {
        AdditivePairHypothesisOperator operator =
            new AdditivePairHypothesisOperator(
                new SumOfSquaresCompletionOperator(),
                16);

        List<String> compact = operator
            .generateCandidates("a^2+b^2+c^2")
            .stream()
            .map(Transformation::applicationKey)
            .toList();
        List<String> spaced = operator
            .generateCandidates("a ^ 2 + b ^ 2 + c ^ 2")
            .stream()
            .map(Transformation::applicationKey)
            .toList();

        assertEquals(compact, spaced);
        assertTrue(compact.stream().allMatch(key ->
            key.contains(":delegate-")
                && key.contains(":transition-")));
    }

    @Test
    void appliesADeterministicWholeExpressionLimit() {
        AdditivePairHypothesisOperator operator =
            new AdditivePairHypothesisOperator(
                new SumOfSquaresCompletionOperator(),
                2);

        List<Transformation> candidates =
            operator.generateCandidates("a^2 + b^2 + c^2 + d^2");

        assertEquals(2, candidates.size());
        assertContains(
            candidates,
            "(a + b)^2 - 2*a*b + c^2 + d^2");
        assertContains(
            candidates,
            "(a + c)^2 - 2*a*c + b^2 + d^2");
    }

    @Test
    void failsClosedForInvalidDelegateOutputAndDisabledConfiguration() {
        HypothesisOperator invalid = ignored -> List.of(
            new Transformation("broken", ">>"));
        AdditivePairHypothesisOperator operator =
            new AdditivePairHypothesisOperator(invalid, 4);

        assertTrue(operator.generateCandidates("a + b").isEmpty());
        assertTrue(operator.generateCandidates("a * b").isEmpty());
        assertTrue(operator.generateCandidates("").isEmpty());
        for (int limit : List.of(-1, 0)) {
            assertTrue(new AdditivePairHypothesisOperator(
                new SumOfSquaresCompletionOperator(),
                limit).generateCandidates("a^2 + b^2").isEmpty());
        }
    }

    private void assertContains(
        List<Transformation> candidates,
        String expected
    ) {
        Expr expectedAst = parser.parseTerm(expected);
        assertTrue(candidates.stream().anyMatch(candidate ->
            parser.parseTerm(candidate.transformedExpression())
                .equals(expectedAst)),
            () -> expected + " not in " + candidates);
    }

    private void assertExpression(String expected, String actual) {
        assertEquals(
            parser.parseTerm(expected),
            parser.parseTerm(actual),
            actual);
    }
}
