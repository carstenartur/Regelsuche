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
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.transform.AdditivePairHypothesisOperator;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.ExactMonomialSquareExposureOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.OccurrenceAwareAstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Composes independently learned square-completion precursors into the
 * Brahmagupta-Fibonacci two-square identity.
 *
 * <p>The endpoint is supplied only to measure bounded reachability in this
 * first slice. A following target-free slice must remove that concrete
 * endpoint without changing the frozen precursor identities.</p>
 */
class BrahmaguptaFibonacciPrecursorCompositionIntegrationTest {
    private static final String SOURCE =
        "(a^2 + b^2) * (c^2 + d^2)";
    private static final String HISTORICAL_IDENTITY =
        "(a*c - b*d)^2 + (a*d + b*c)^2";
    private static final Set<String> STRUCTURAL_RULE_IDS = Set.of(
        "ast_distribute_left_add",
        "ast_distribute_right_add",
        "ast_canonical_normalize");
    private static final SearchHeuristic HEURISTIC =
        new SearchHeuristic(10, 20_000, 1, 24, 192, 4_096);

    private final ExpressionParser parser = new ExpressionParser();
    private final HistoricalPrecursorTestSupport support =
        new HistoricalPrecursorTestSupport();

    @Test
    @Timeout(300)
    void independentlyFrozenPrecursorsComposeIntoTheHistoricalIdentity() {
        RuntimeTask plusTask = support.completionTask();
        RuntimeTask minusTask = support.differenceCompletionTask();
        String trainingMaterial = plusTask.observableInput()
            + "\n" + minusTask.observableInput();
        assertFalse(trainingMaterial.contains(SOURCE));
        assertFalse(trainingMaterial.contains(HISTORICAL_IDENTITY));

        FrozenRule plus = support.freeze(plusTask);
        FrozenRule minus = support.freeze(minusTask);
        assertFalse(plus.operator().ruleId().equals(
            minus.operator().ruleId()));

        AdditivePairHypothesisOperator plusPairs =
            new AdditivePairHypothesisOperator(plus.operator(), 24);
        AdditivePairHypothesisOperator minusPairs =
            new AdditivePairHypothesisOperator(minus.operator(), 24);
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
        String minusApplied = support.requireExactMove(
            minusPairs,
            exposed,
            "(a*c - b*d)^2 + 2*a*c*b*d"
                + " + (a*d)^2 + (b*c)^2");
        String plusApplied = support.requireExactMove(
            plusPairs,
            minusApplied,
            "(a*c - b*d)^2 + 2*a*c*b*d"
                + " + (a*d + b*c)^2 - 2*a*d*b*c");
        String discoveredTarget = requireExact(
            canonicalOnlyEngine(),
            plusApplied,
            HISTORICAL_IDENTITY);

        assertTrue(support.exactVerifier().verify(
            SOURCE,
            discoveredTarget).proved());

        GoalSearchResult baseline = support.search(
            new HypothesisTransformationEngine(
                structuralEngine(),
                List.of(exposure),
                192),
            HEURISTIC,
            SOURCE,
            discoveredTarget);
        GoalSearchResult accumulated = support.search(
            new HypothesisTransformationEngine(
                structuralEngine(),
                List.of(exposure, minusPairs, plusPairs),
                192),
            HEURISTIC,
            SOURCE,
            discoveredTarget);

        assertFalse(baseline.reached(), baseline.toString());
        assertTrue(accumulated.reached(), accumulated.toString());
        assertNotNull(accumulated.reachedState(), accumulated.toString());
        assertTrue(accumulated.reachedState().appliedRuleIds().contains(
            plus.candidate().dynamicRuleId()));
        assertTrue(accumulated.reachedState().appliedRuleIds().contains(
            minus.candidate().dynamicRuleId()));
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
        assertEquals(10, accumulated.reachedState().depth());
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
                .filter(candidate ->
                    explicitProductSquareCount(
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
                && power.right() instanceof NumberExpr exponent
                && Double.compare(exponent.value(), 2.0) == 0
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
}
