package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.docs.HistoricalPrecursorTestSupport.FrozenRule;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.transform.AdditivePairHypothesisOperator;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.ExactMonomialSquareExposureOperator;
import de.regelsuche.transform.HypothesisOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.OccurrenceAwareAstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.SquareBaseSignSymmetryOperator;
import de.regelsuche.transform.SubtreeHypothesisOperator;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Composes the Brahmagupta-Fibonacci identity with one independently learned
 * square-completion rule, generic associative pair selection and one exact
 * square-base sign symmetry.
 *
 * <p>The historical endpoint is supplied only to measure bounded
 * reachability. The learned rule is frozen before that endpoint is revealed,
 * and the same dynamic rule identity must be used twice.</p>
 */
class BrahmaguptaFibonacciSingleLearnedRuleIntegrationTest {
    private static final String SOURCE =
        "(a^2 + b^2) * (c^2 + d^2)";
    private static final String HISTORICAL_IDENTITY =
        "(a*c - b*d)^2 + (a*d + b*c)^2";
    private static final Set<String> STRUCTURAL_RULE_IDS = Set.of(
        "ast_distribute_left_add",
        "ast_distribute_right_add",
        "ast_canonical_normalize");
    private static final SearchHeuristic HEURISTIC =
        new SearchHeuristic(11, 60_000, 1, 32, 256, 8_192);

    private final ExpressionParser parser = new ExpressionParser();
    private final HistoricalPrecursorTestSupport support =
        new HistoricalPrecursorTestSupport();

    @Test
    @Timeout(300)
    void oneFrozenCompletionRuleComposesTheHistoricalIdentity() {
        RuntimeTask completionTask = support.completionTask();
        String observableTraining = completionTask.observableInput();
        assertFalse(observableTraining.contains(SOURCE));
        assertFalse(observableTraining.contains(HISTORICAL_IDENTITY));

        FrozenRule completion = support.freeze(completionTask);
        AdditivePairHypothesisOperator pairCompletion =
            new AdditivePairHypothesisOperator(
                completion.operator(),
                48);
        SubtreeHypothesisOperator signSymmetry =
            new SubtreeHypothesisOperator(
                new SquareBaseSignSymmetryOperator(),
                32);
        ExactMonomialSquareExposureOperator exposure =
            new ExactMonomialSquareExposureOperator();

        TransformationEngine structural = structuralEngine();
        String expanded = requireRule(
            structural,
            SOURCE,
            "ast_distribute_right_add");
        expanded = requireRule(
            structural,
            expanded,
            "ast_distribute_left_add");
        expanded = requireRule(
            structural,
            expanded,
            "ast_distribute_left_add");

        String exposed = exposeFourProducts(exposure, expanded);
        String signApplied = signSymmetry.generateCandidates(exposed).stream()
            .filter(candidate -> containsNegatedSquareBase(
                candidate.transformedExpression(),
                "b*d"))
            .map(Transformation::transformedExpression)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "the (b*d)^2 occurrence was not sign-reflected in "
                    + exposed));

        assertTrue(completion.operator()
            .generateCandidates(signApplied)
            .isEmpty(),
            "the root-only learned rule must still require pair selection");

        String differenceApplied = requireMoveWithSquareBases(
            pairCompletion,
            signApplied,
            List.of("a*c - b*d"));
        String plusApplied = requireMoveWithSquareBases(
            pairCompletion,
            differenceApplied,
            List.of("a*c - b*d", "a*d + b*c"));
        String discoveredTarget = requireExact(
            canonicalOnlyEngine(),
            plusApplied,
            HISTORICAL_IDENTITY);

        assertTrue(support.exactVerifier().verify(
            SOURCE,
            discoveredTarget).proved());

        GoalSearchResult withoutPairSelection = support.search(
            new HypothesisTransformationEngine(
                structuralEngine(),
                List.of(
                    exposure,
                    signSymmetry,
                    completion.operator()),
                256),
            HEURISTIC,
            SOURCE,
            discoveredTarget);
        GoalSearchResult withoutSignSymmetry = support.search(
            new HypothesisTransformationEngine(
                structuralEngine(),
                List.of(exposure, pairCompletion),
                256),
            HEURISTIC,
            SOURCE,
            discoveredTarget);
        GoalSearchResult accumulated = support.search(
            new HypothesisTransformationEngine(
                structuralEngine(),
                List.of(exposure, signSymmetry, pairCompletion),
                256),
            HEURISTIC,
            SOURCE,
            discoveredTarget);

        assertFalse(withoutPairSelection.reached(),
            withoutPairSelection.toString());
        assertFalse(withoutSignSymmetry.reached(),
            withoutSignSymmetry.toString());
        assertTrue(accumulated.reached(), accumulated.toString());
        assertNotNull(accumulated.reachedState(), accumulated.toString());
        assertEquals(11, accumulated.reachedState().depth());
        assertEquals(2L,
            accumulated.reachedState().appliedRuleIds().stream()
                .filter(completion.candidate().dynamicRuleId()::equals)
                .count(),
            accumulated.reachedState().toString());
        assertEquals(1L,
            accumulated.reachedState().appliedRuleIds().stream()
                .filter(SquareBaseSignSymmetryOperator.RULE_ID::equals)
                .count(),
            accumulated.reachedState().toString());
        assertTrue(accumulated.reachedState().appliedRuleIds().stream()
            .filter(ExactMonomialSquareExposureOperator.RULE_ID::equals)
            .count() >= 4,
            accumulated.reachedState().toString());
        assertTrue(accumulated.reachedState().appliedRuleIds().stream()
            .filter(rule -> rule.startsWith("ast_distribute_"))
            .count() >= 3,
            accumulated.reachedState().toString());
        assertTrue(accumulated.reachedState().appliedRuleIds().contains(
            "ast_canonical_normalize"));
        assertTrue(accumulated.reachedState()
            .appliedRuleApplications()
            .stream()
            .anyMatch(key -> key.startsWith("subtree-v1:")));
        assertTrue(accumulated.reachedState()
            .appliedRuleApplications()
            .stream()
            .filter(key -> key.startsWith("additive-pair-v1:"))
            .count() >= 2);
    }

    private TransformationEngine structuralEngine() {
        List<RewriteRule> rules =
            AstRewriteTransformationEngine.allBuiltInRules().stream()
                .filter(rule -> STRUCTURAL_RULE_IDS.contains(rule.id()))
                .toList();
        return new OccurrenceAwareAstRewriteTransformationEngine(
            rules,
            32,
            128);
    }

    private TransformationEngine canonicalOnlyEngine() {
        RewriteRule canonical =
            AstRewriteTransformationEngine.allBuiltInRules().stream()
                .filter(rule -> "ast_canonical_normalize".equals(rule.id()))
                .findFirst()
                .orElseThrow();
        return new OccurrenceAwareAstRewriteTransformationEngine(
            List.of(canonical),
            32,
            32);
    }

    private String requireRule(
        TransformationEngine engine,
        String source,
        String rule
    ) {
        return engine.transform(source).stream()
            .filter(candidate -> rule.equals(candidate.rule()))
            .map(Transformation::transformedExpression)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                rule + " not applicable to " + source));
    }

    private String exposeFourProducts(
        ExactMonomialSquareExposureOperator exposure,
        String source
    ) {
        String current = source;
        for (int expected = 1; expected <= 4; expected++) {
            final int expectedCount = expected;
            current = exposure.generateCandidates(current).stream()
                .map(Transformation::transformedExpression)
                .filter(candidate -> explicitProductSquareCount(
                    parser.parseTerm(candidate)) == expectedCount)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "exact product-square exposure " + expectedCount
                        + " missing from " + source));
        }
        assertEquals(4,
            explicitProductSquareCount(parser.parseTerm(current)));
        return current;
    }

    private String requireMoveWithSquareBases(
        HypothesisOperator operator,
        String source,
        List<String> expectedBases
    ) {
        return operator.generateCandidates(source).stream()
            .map(Transformation::transformedExpression)
            .filter(candidate -> expectedBases.stream().allMatch(
                base -> containsSquareWithEquivalentBase(candidate, base)))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "squares " + expectedBases + " not generated from "
                    + source));
    }

    private boolean containsNegatedSquareBase(
        String expression,
        String expectedBase
    ) {
        Expr base = parser.parseTerm(expectedBase);
        return contains(
            parser.parseTerm(expression),
            candidate -> candidate instanceof BinaryExpr power
                && power.operator() == BinaryOperator.POW
                && isTwo(power.right())
                && power.left() instanceof BinaryExpr subtraction
                && subtraction.operator() == BinaryOperator.SUB
                && isZero(subtraction.left())
                && subtraction.right().equals(base));
    }

    private boolean containsSquareWithEquivalentBase(
        String expression,
        String expectedBase
    ) {
        return contains(
            parser.parseTerm(expression),
            candidate -> candidate instanceof BinaryExpr power
                && power.operator() == BinaryOperator.POW
                && isTwo(power.right())
                && support.exactVerifier().verify(
                    ExpressionFormatter.format(power.left()),
                    expectedBase).proved());
    }

    private String requireExact(
        TransformationEngine engine,
        String source,
        String expected
    ) {
        Expr expectedAst = parser.parseTerm(expected);
        return engine.transform(source).stream()
            .map(Transformation::transformedExpression)
            .filter(candidate -> parser.parseTerm(candidate)
                .equals(expectedAst))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                expected + " not generated from " + source));
    }

    private int explicitProductSquareCount(Expr expression) {
        int here = expression instanceof BinaryExpr power
                && power.operator() == BinaryOperator.POW
                && power.left() instanceof BinaryExpr product
                && product.operator() == BinaryOperator.MUL
                && isTwo(power.right())
            ? 1
            : 0;
        if (expression instanceof BinaryExpr binary) {
            return here
                + explicitProductSquareCount(binary.left())
                + explicitProductSquareCount(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            return here + function.arguments().stream()
                .mapToInt(this::explicitProductSquareCount)
                .sum();
        }
        return here;
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

    private static boolean isTwo(Expr expression) {
        return expression instanceof NumberExpr number
            && Double.compare(number.value(), 2.0) == 0;
    }

    private static boolean isZero(Expr expression) {
        return expression instanceof NumberExpr number
            && Double.compare(number.value(), 0.0) == 0;
    }
}
