package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.docs.HistoricalPrecursorTestSupport.FrozenRule;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.cost.TransformationGoal;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AdditivePairHypothesisOperator;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.ExactMonomialSquareExposureOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.OccurrenceAwareAstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.SquareBaseSignSymmetryOperator;
import de.regelsuche.transform.SubtreeHypothesisOperator;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Target-free Brahmagupta-Fibonacci rediscovery with one independently frozen
 * square-completion rule.
 *
 * <p>The historical two-square forms are used only after both searches have
 * completed. Neither search receives a target expression, family name or
 * historical correspondence signal.</p>
 */
class BrahmaguptaFibonacciTargetFreeSingleRuleIntegrationTest {
    private static final String SOURCE =
        "(a^2 + b^2) * (c^2 + d^2)";
    private static final String FIRST_FORM_LEFT =
        "(a*c - b*d)^2";
    private static final String FIRST_FORM_RIGHT =
        "(a*d + b*c)^2";
    private static final String SECOND_FORM_LEFT =
        "(a*c + b*d)^2";
    private static final String SECOND_FORM_RIGHT =
        "(a*d - b*c)^2";
    private static final Set<String> STRUCTURAL_RULE_IDS = Set.of(
        "ast_distribute_left_add",
        "ast_distribute_right_add",
        "ast_canonical_normalize");
    private static final SearchHeuristic SEARCH_BUDGET =
        new SearchHeuristic(11, 60_000, 1, 24, 192, 8_192);

    private final HistoricalPrecursorTestSupport support =
        new HistoricalPrecursorTestSupport();
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    @Timeout(600)
    void oneFrozenRuleFormsAHistoricalTwoSquareIdentityWithoutATarget() {
        RuntimeTask completionTask = support.completionTask();
        String observableTraining = completionTask.observableInput();
        assertFalse(observableTraining.contains(SOURCE));
        assertFalse(observableTraining.contains(FIRST_FORM_LEFT));
        assertFalse(observableTraining.contains(FIRST_FORM_RIGHT));
        assertFalse(observableTraining.contains(SECOND_FORM_LEFT));
        assertFalse(observableTraining.contains(SECOND_FORM_RIGHT));

        FrozenRule completion = support.freeze(completionTask);
        AdditivePairHypothesisOperator pairCompletion =
            new AdditivePairHypothesisOperator(
                completion.operator(),
                24);
        SubtreeHypothesisOperator signSymmetry =
            new SubtreeHypothesisOperator(
                new SquareBaseSignSymmetryOperator(),
                16);
        ExactMonomialSquareExposureOperator exposure =
            new ExactMonomialSquareExposureOperator();

        GoalSearchResult baseline = support.searchUntargeted(
            new HypothesisTransformationEngine(
                structuralEngine(),
                List.of(exposure, pairCompletion),
                192),
            SEARCH_BUDGET,
            SOURCE,
            TransformationGoal.PROOF_FRIENDLY);
        GoalSearchResult accumulated = support.searchUntargeted(
            new HypothesisTransformationEngine(
                structuralEngine(),
                List.of(exposure, signSymmetry, pairCompletion),
                192),
            SEARCH_BUDGET,
            SOURCE,
            TransformationGoal.PROOF_FRIENDLY);

        assertUntargeted(baseline);
        assertUntargeted(accumulated);
        assertTrue(
            accumulated.states().size()
                < SEARCH_BUDGET.maxVisitedExpressions(),
            "rediscovery must not depend on exhausting the state budget: "
                + accumulated);
        assertFalse(containsHistoricalTwoSquareForm(baseline.states()),
            baseline.toString());

        SearchState discovered = historicalTwoSquareFormIn(
            accumulated.states());
        assertEquals(
            parser.parseTerm(SOURCE),
            parser.parseTerm(discovered.path().getFirst()),
            discovered.toString());
        assertEquals(11, discovered.depth(), discovered.toString());
        assertEquals(2L,
            discovered.appliedRuleIds().stream()
                .filter(completion.candidate().dynamicRuleId()::equals)
                .count(),
            discovered.toString());
        assertEquals(1L,
            discovered.appliedRuleIds().stream()
                .filter(SquareBaseSignSymmetryOperator.RULE_ID::equals)
                .count(),
            discovered.toString());
        assertTrue(discovered.appliedRuleIds().stream()
            .filter(ExactMonomialSquareExposureOperator.RULE_ID::equals)
            .count() >= 4,
            discovered.toString());
        assertTrue(discovered.appliedRuleIds().stream()
            .filter(rule -> rule.startsWith("ast_distribute_"))
            .count() >= 3,
            discovered.toString());
        assertTrue(discovered.appliedRuleIds().contains(
            "ast_canonical_normalize"),
            discovered.toString());
        assertTrue(discovered.appliedRuleApplications().stream()
            .anyMatch(key -> key.startsWith("subtree-v1:")),
            discovered.toString());
        assertTrue(discovered.appliedRuleApplications().stream()
            .filter(key -> key.startsWith("additive-pair-v1:"))
            .count() >= 2,
            discovered.toString());
    }

    private void assertUntargeted(GoalSearchResult result) {
        assertEquals(GoalStatus.UNTARGETED, result.status(),
            result.toString());
        assertNull(result.reachedState(), result.toString());
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

    private boolean containsHistoricalTwoSquareForm(
        List<SearchState> states
    ) {
        return states.stream()
            .map(SearchState::expression)
            .anyMatch(this::matchesHistoricalTwoSquareForm);
    }

    private SearchState historicalTwoSquareFormIn(
        List<SearchState> states
    ) {
        return states.stream()
            .filter(state -> matchesHistoricalTwoSquareForm(
                state.expression()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no historical two-square form was generated by the "
                    + "untargeted search; explored=" + states.size()));
    }

    private boolean matchesHistoricalTwoSquareForm(String expression) {
        List<Expr> terms = flattenTopLevelAddition(
            parser.parseTerm(expression));
        if (terms.size() != 2
                || !isExplicitSquare(terms.get(0))
                || !isExplicitSquare(terms.get(1))) {
            return false;
        }
        return matchesUnorderedPair(
                terms,
                FIRST_FORM_LEFT,
                FIRST_FORM_RIGHT)
            || matchesUnorderedPair(
                terms,
                SECOND_FORM_LEFT,
                SECOND_FORM_RIGHT);
    }

    private boolean matchesUnorderedPair(
        List<Expr> terms,
        String expectedLeft,
        String expectedRight
    ) {
        return equivalent(terms.get(0), expectedLeft)
                && equivalent(terms.get(1), expectedRight)
            || equivalent(terms.get(0), expectedRight)
                && equivalent(terms.get(1), expectedLeft);
    }

    private boolean equivalent(Expr actual, String expected) {
        return support.exactVerifier().verify(
            ExpressionFormatter.format(actual),
            expected).proved();
    }

    private List<Expr> flattenTopLevelAddition(Expr expression) {
        List<Expr> terms = new ArrayList<>();
        collectTopLevelAddition(expression, terms);
        return List.copyOf(terms);
    }

    private void collectTopLevelAddition(
        Expr expression,
        List<Expr> terms
    ) {
        if (expression instanceof BinaryExpr binary
                && binary.operator() == BinaryOperator.ADD) {
            collectTopLevelAddition(binary.left(), terms);
            collectTopLevelAddition(binary.right(), terms);
        } else {
            terms.add(expression);
        }
    }

    private boolean isExplicitSquare(Expr expression) {
        return expression instanceof BinaryExpr power
            && power.operator() == BinaryOperator.POW
            && power.right() instanceof de.regelsuche.ast.NumberExpr exponent
            && Double.compare(exponent.value(), 2.0) == 0;
    }
}
