package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class AdditivePairHypothesisOperatorTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void enumeratesPairsDeterministicallyAndPreservesDelegateMetadata() {
        List<Transformation> transformations =
            new AdditivePairHypothesisOperator(pairDelegate())
                .generateCandidates("a + b + c");

        assertEquals(6, transformations.size());
        assertExpression("pair(a, b) + c", transformations.get(0));
        assertExpression("pair(b, a) + c", transformations.get(1));
        assertExpression("pair(a, c) + b", transformations.get(2));
        assertExpression("pair(c, a) + b", transformations.get(3));
        assertExpression("a + pair(b, c)", transformations.get(4));
        assertExpression("a + pair(c, b)", transformations.get(5));
        assertTrue(transformations.stream().allMatch(transformation ->
            transformation.rule().equals("learned_pair")
                && transformation.kind() == RewriteKind.EXPAND
                && transformation.mayIncreaseComplexity()
                && transformation.estimatedCostDelta() == 3
                && !transformation.equivalencePreservingByConstruction()
                && transformation.assumptions().equals(List.of("x != 0"))
                && transformation.packId().equals("test-pack")
                && transformation.license().equals("MIT")
                && transformation.primitiveRuleIds().equals(
                    List.of("primitive_select", "primitive_replace"))));
        assertEquals(
            transformations.size(),
            transformations.stream()
                .map(Transformation::applicationKey)
                .distinct()
                .count());
        assertTrue(transformations.stream().allMatch(transformation ->
            transformation.applicationKey().startsWith(
                "additive-pair-v1:learned_pair:")
                && !transformation.applicationKey().contains("pair(")));
    }

    @Test
    void exposesTheNonAdjacentSquarePairNeededByBrahmagupta() {
        String source =
            "(a*c)^2 + (a*d)^2 + (b*c)^2 + (b*d)^2";
        List<Transformation> transformations =
            new AdditivePairHypothesisOperator(
                new SumOfSquaresCompletionOperator())
                .generateCandidates(source);

        Transformation nonAdjacent = transformations.stream()
            .filter(transformation -> containsSquareOfSum(
                transformation.transformedExpression(),
                "a*c",
                "b*d"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "non-adjacent (a*c)^2 + (b*d)^2 was not selected: "
                    + transformations));

        assertEquals(
            SumOfSquaresCompletionOperator.RULE_ID,
            nonAdjacent.rule());
        assertTrue(nonAdjacent.applicationKey().startsWith(
            "additive-pair-v1:"
                + SumOfSquaresCompletionOperator.RULE_ID
                + ":0.3:"));
        assertTrue(nonAdjacent.equivalencePreservingByConstruction());
        assertEquals(
            List.of(SumOfSquaresCompletionOperator.RULE_ID),
            nonAdjacent.primitiveRuleIds());
    }

    @Test
    void leavesSubtractionOpaqueInsteadOfInventingSignedTerms() {
        List<Transformation> transformations =
            new AdditivePairHypothesisOperator(pairDelegate())
                .generateCandidates("a - b + c");

        assertEquals(2, transformations.size());
        assertExpression("pair(a - b, c)", transformations.get(0));
        assertExpression("pair(c, a - b)", transformations.get(1));
    }

    @Test
    void triesTheReverseOrientationOfEveryPair() {
        Expr expectedPair = parser.parseTerm("b + a");
        HypothesisOperator orientationSensitive = expression -> {
            if (!parser.parseTerm(expression).equals(expectedPair)) {
                return List.of();
            }
            return List.of(new Transformation(
                "ordered_pair",
                "ordered(b, a)",
                RewriteKind.NORMALIZE,
                false,
                0,
                false,
                "ordered-pair-v1"));
        };

        List<Transformation> transformations =
            new AdditivePairHypothesisOperator(orientationSensitive)
                .generateCandidates("a + b + c");

        assertEquals(1, transformations.size());
        assertExpression(
            "ordered(b, a) + c",
            transformations.getFirst());
        assertTrue(transformations.getFirst().applicationKey().contains(
            ":0.1:reverse:"));
    }

    @Test
    void appliesTheGlobalLimitInPairAndOrientationOrder() {
        List<Transformation> transformations =
            new AdditivePairHypothesisOperator(pairDelegate(), 2)
                .generateCandidates("a + b + c");

        assertEquals(2, transformations.size());
        assertExpression("pair(a, b) + c", transformations.get(0));
        assertExpression("pair(b, a) + c", transformations.get(1));
        assertTrue(new AdditivePairHypothesisOperator(pairDelegate(), 0)
            .generateCandidates("a + b + c")
            .isEmpty());
        assertTrue(new AdditivePairHypothesisOperator(pairDelegate(), -1)
            .generateCandidates("a + b + c")
            .isEmpty());
    }

    @Test
    void rejectsOversizedSumsBeforeQuadraticDelegation() {
        AtomicInteger delegatedCalls = new AtomicInteger();
        HypothesisOperator countingDelegate = expression -> {
            delegatedCalls.incrementAndGet();
            return List.of();
        };

        assertTrue(new AdditivePairHypothesisOperator(
            countingDelegate,
            64,
            3).generateCandidates("a + b + c + d").isEmpty());
        assertEquals(0, delegatedCalls.get());

        assertTrue(new AdditivePairHypothesisOperator(
            countingDelegate,
            64,
            -1).generateCandidates("a + b").isEmpty());
        assertEquals(0, delegatedCalls.get());
    }

    @Test
    void usesWhitespaceStableApplicationIdentity() {
        AdditivePairHypothesisOperator operator =
            new AdditivePairHypothesisOperator(
                new SumOfSquaresCompletionOperator(),
                1);

        Transformation compact = operator
            .generateCandidates("a^2+b^2+c^2")
            .getFirst();
        Transformation spaced = operator
            .generateCandidates("a ^ 2 + b ^ 2 + c ^ 2")
            .getFirst();

        assertEquals(
            compact.transformedExpression(),
            spaced.transformedExpression());
        assertEquals(
            compact.applicationKey(),
            spaced.applicationKey());
    }

    @Test
    void skipsInvalidNullAndNonAdditiveDelegatedResults() {
        HypothesisOperator invalid = expression -> List.of(
            new Transformation("invalid", "("));
        HypothesisOperator nullList = expression -> null;
        HypothesisOperator nullCandidate = expression -> {
            List<Transformation> candidates = new ArrayList<>();
            candidates.add(null);
            return candidates;
        };

        assertTrue(new AdditivePairHypothesisOperator(invalid)
            .generateCandidates("a + b + c")
            .isEmpty());
        assertTrue(new AdditivePairHypothesisOperator(nullList)
            .generateCandidates("a + b + c")
            .isEmpty());
        assertTrue(new AdditivePairHypothesisOperator(nullCandidate)
            .generateCandidates("a + b + c")
            .isEmpty());
        assertTrue(new AdditivePairHypothesisOperator(pairDelegate())
            .generateCandidates("a * b")
            .isEmpty());
    }

    private HypothesisOperator pairDelegate() {
        return expression -> {
            Expr parsed;
            try {
                parsed = parser.parseTerm(expression);
            } catch (IllegalArgumentException exception) {
                return List.of();
            }
            if (!(parsed instanceof BinaryExpr addition)
                    || addition.operator() != BinaryOperator.ADD) {
                return List.of();
            }
            String transformed = ExpressionFormatter.format(
                new FunctionExpr(
                    "pair",
                    List.of(addition.left(), addition.right())));
            return List.of(new Transformation(
                "learned_pair",
                transformed,
                RewriteKind.EXPAND,
                true,
                3,
                false,
                "delegate-pair-v1:" + transformed,
                List.of("x != 0"),
                "test-pack",
                "MIT",
                List.of("primitive_select", "primitive_replace")));
        };
    }

    private boolean containsSquareOfSum(
        String expression,
        String leftBase,
        String rightBase
    ) {
        Expr left = parser.parseTerm(leftBase);
        Expr right = parser.parseTerm(rightBase);
        return contains(
            parser.parseTerm(expression),
            candidate -> candidate instanceof BinaryExpr power
                && power.operator() == BinaryOperator.POW
                && power.right() instanceof NumberExpr exponent
                && Double.compare(exponent.value(), 2.0) == 0
                && power.left() instanceof BinaryExpr sum
                && sum.operator() == BinaryOperator.ADD
                && ((sum.left().equals(left)
                        && sum.right().equals(right))
                    || (sum.left().equals(right)
                        && sum.right().equals(left))));
    }

    private boolean contains(Expr expression, Predicate<Expr> predicate) {
        if (predicate.test(expression)) {
            return true;
        }
        if (expression instanceof BinaryExpr binary) {
            return contains(binary.left(), predicate)
                || contains(binary.right(), predicate);
        }
        if (expression instanceof FunctionExpr function) {
            return function.arguments().stream()
                .anyMatch(argument -> contains(argument, predicate));
        }
        return false;
    }

    private void assertExpression(
        String expected,
        Transformation transformation
    ) {
        assertEquals(
            parser.parseTerm(expected),
            parser.parseTerm(transformation.transformedExpression()),
            transformation.toString());
    }
}
