package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.learning.MacroLearningPipeline;
import de.regelsuche.learning.MacroLearningResult;
import de.regelsuche.mining.DynamicOperatorCompiler;
import de.regelsuche.mining.DynamicPatternOperator;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.mining.UntargetedEquivalentPathExtractor;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.cost.TransformationGoal;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.ExactMonomialSquareExposureOperator;
import de.regelsuche.transform.HypothesisOperator;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.SumOfSquaresCompletionOperator;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Learns both generic precursor rules from searches that never receive a
 * concrete endpoint, freezes them, validates transfer holdouts and then uses
 * them in a second untargeted search for the Sophie-Germain factorization.
 */
class SophieGermainUntargetedRuleFormationIntegrationTest {
    private static final String SOURCE = "x^4 + 4*y^4";
    private static final String HISTORICAL_FACTORIZATION =
        "(x^2 + 2*y^2 - 2*x*y) * (x^2 + 2*y^2 + 2*x*y)";
    private static final SearchHeuristic TRAIN_BUDGET =
        new SearchHeuristic(1, 32, 1, 4, 8, 16);
    private static final SearchHeuristic FINAL_BUDGET =
        new SearchHeuristic(5, 256, 1, 8, 32, 64);

    private final HistoricalPrecursorTestSupport support =
        new HistoricalPrecursorTestSupport();
    private final ExpressionParser parser = new ExpressionParser();
    private final UntargetedEquivalentPathExtractor pathExtractor =
        new UntargetedEquivalentPathExtractor();

    @Test
    @Timeout(240)
    void targetFreeTrainingProducesRulesUsedByTargetFreeRediscovery() {
        String trainingMaterial = String.join("\n",
            "p^2 + q^2",
            "r^2 - s^2",
            "(m + 1)^2 + n^2",
            "sin(t)^2 + z^2",
            "(m + 1)^2 - n^2",
            "sin(t)^2 - z^2");
        assertFalse(trainingMaterial.contains(SOURCE));
        assertFalse(trainingMaterial.contains(HISTORICAL_FACTORIZATION));

        SumOfSquaresCompletionOperator completionPrimitive =
            new SumOfSquaresCompletionOperator();
        FrozenUntargetedRule completion = learnAndFreeze(
            "complete-visible-square-sum",
            "p^2 + q^2",
            support.engine(List.of(completionPrimitive)),
            TransformationGoal.PROOF_FRIENDLY,
            SumOfSquaresCompletionOperator.RULE_ID);
        assertTrue(completion.learningPath().scoreImprovement() <= 0,
            "the representation bridge should not require immediate "
                + "improvement: " + completion.learningPath());
        verifyCompletionHoldouts(completion.operator());

        RewriteRule differencePrimitive =
            AstRewriteTransformationEngine.allBuiltInRules().stream()
                .filter(rule -> "ast_square_difference_factor".equals(
                    rule.id()))
                .findFirst()
                .orElseThrow();
        FrozenUntargetedRule difference = learnAndFreeze(
            "factor-visible-square-difference",
            "r^2 - s^2",
            new AstRewriteTransformationEngine(List.of(
                differencePrimitive)),
            TransformationGoal.FACTORIZE,
            differencePrimitive.id());
        verifyDifferenceHoldouts(difference.operator());

        assertFalse(completion.operator().ruleId().equals(
            difference.operator().ruleId()));

        ExactMonomialSquareExposureOperator exposure =
            new ExactMonomialSquareExposureOperator();
        GoalSearchResult baseline = support.searchUntargeted(
            support.engine(List.of(exposure)),
            FINAL_BUDGET,
            SOURCE,
            TransformationGoal.FACTORIZE);
        GoalSearchResult accumulated = support.searchUntargeted(
            support.engine(List.of(
                exposure,
                completion.operator(),
                difference.operator())),
            FINAL_BUDGET,
            SOURCE,
            TransformationGoal.FACTORIZE);

        assertEquals(GoalStatus.UNTARGETED, baseline.status());
        assertEquals(GoalStatus.UNTARGETED, accumulated.status());
        assertFalse(containsHistoricalFactorization(baseline.states()),
            baseline.toString());
        SearchState discovered = historicalFactorizationIn(
            accumulated.states());
        assertTrue(accumulated.states().size()
            < FINAL_BUDGET.maxVisitedExpressions(), accumulated.toString());
        assertTrue(support.exactVerifier().verify(
            discovered.expression(),
            HISTORICAL_FACTORIZATION).proved());
        assertTrue(discovered.appliedRuleIds().contains(
            completion.operator().ruleId()), discovered.toString());
        assertTrue(discovered.appliedRuleIds().contains(
            difference.operator().ruleId()), discovered.toString());
        assertTrue(discovered.appliedRuleIds().stream()
            .filter(ExactMonomialSquareExposureOperator.RULE_ID::equals)
            .count() >= 3,
            discovered.toString());
        assertEquals(5, discovered.depth(), discovered.toString());
    }

    private FrozenUntargetedRule learnAndFreeze(
        String caseId,
        String source,
        TransformationEngine primitiveEngine,
        TransformationGoal objective,
        String expectedPrimitiveRule
    ) {
        GoalSearchResult discovery = support.searchUntargeted(
            primitiveEngine,
            TRAIN_BUDGET,
            source,
            objective);
        assertEquals(GoalStatus.UNTARGETED, discovery.status(),
            discovery.toString());
        assertFalse(discovery.reached(), discovery.toString());

        List<SuccessfulTransformationPath> paths =
            pathExtractor.extract(discovery);
        SuccessfulTransformationPath learningPath = paths.stream()
            .filter(path -> path.rules().equals(List.of(
                expectedPrimitiveRule)))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no exact one-step untargeted learning path for "
                    + expectedPrimitiveRule + ": " + paths));
        assertEquals(2, learningPath.expressionPath().size());
        assertTrue(learningPath.equivalenceVerified());

        InMemoryRuleInventoryRepository inventory =
            new InMemoryRuleInventoryRepository();
        MacroLearningResult learning = new MacroLearningPipeline(inventory)
            .learn(List.of(learningPath));
        assertEquals(1, learning.newlyActivated().size(),
            learning.stageEvidence().toString());
        ReusableRule learned = learning.newlyActivated().getFirst();
        assertFalse(learning.validationExamples().isEmpty(),
            learning.stageEvidence().toString());
        assertTrue(learning.validationExamples().stream()
            .allMatch(example -> example.equivalent()),
            learning.validationExamples().toString());
        assertTrue(learning.counterexampleSearches().stream()
            .noneMatch(result -> result.counterexample().isPresent()),
            learning.counterexampleSearches().toString());

        DynamicOperatorCompiler.CompilationResult compilation =
            new DynamicOperatorCompiler().compile(
                "untargeted-" + caseId,
                "frozen-v1",
                learned.leftPattern(),
                learned.rightPattern());
        assertTrue(compilation.isSuccess(), compilation.rejectionReason());
        DynamicPatternOperator operator = compilation.operator()
            .orElseThrow();
        assertNotNull(operator.ruleId());
        assertNotNull(operator.provenanceHash());
        return new FrozenUntargetedRule(
            learningPath,
            learned,
            operator);
    }

    private void verifyCompletionHoldouts(
        DynamicPatternOperator operator
    ) {
        assertPositive(operator,
            "(m + 1)^2 + n^2",
            "((m + 1) + n)^2 - 2*(m + 1)*n");
        assertPositive(operator,
            "sin(t)^2 + z^2",
            "(sin(t) + z)^2 - 2*sin(t)*z");
        assertNegative(operator, "m^3 + n^2");
        assertNegative(operator, "m^2 - n^2");
    }

    private void verifyDifferenceHoldouts(
        DynamicPatternOperator operator
    ) {
        assertPositive(operator,
            "(m + 1)^2 - n^2",
            "((m + 1) - n) * ((m + 1) + n)");
        assertPositive(operator,
            "sin(t)^2 - z^2",
            "(sin(t) - z) * (sin(t) + z)");
        assertNegative(operator, "m^2 + n^2");
        assertNegative(operator, "m^3 - n^2");
    }

    private void assertPositive(
        HypothesisOperator operator,
        String source,
        String expected
    ) {
        List<Transformation> candidates = operator.generateCandidates(source);
        assertTrue(candidates.stream().anyMatch(candidate ->
            support.exactVerifier().verify(
                candidate.transformedExpression(),
                expected).proved()),
            source + " -> " + candidates);
    }

    private void assertNegative(
        HypothesisOperator operator,
        String source
    ) {
        assertTrue(operator.generateCandidates(source).isEmpty(), source);
    }

    private boolean containsHistoricalFactorization(
        List<SearchState> states
    ) {
        return states.stream()
            .map(SearchState::expression)
            .anyMatch(this::matchesHistoricalFactorizationForm);
    }

    private SearchState historicalFactorizationIn(
        List<SearchState> states
    ) {
        return states.stream()
            .filter(state -> matchesHistoricalFactorizationForm(
                state.expression()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "historical factorization not generated; states=" + states));
    }

    private boolean matchesHistoricalFactorizationForm(
        String expression
    ) {
        Expr expected = parser.parseTerm(HISTORICAL_FACTORIZATION);
        Expr actual = parser.parseTerm(expression);
        if (actual.equals(expected)) {
            return true;
        }
        if (actual instanceof BinaryExpr actualProduct
                && expected instanceof BinaryExpr expectedProduct
                && actualProduct.operator() == BinaryOperator.MUL
                && expectedProduct.operator() == BinaryOperator.MUL) {
            return actualProduct.left().equals(expectedProduct.right())
                && actualProduct.right().equals(expectedProduct.left());
        }
        return false;
    }

    private record FrozenUntargetedRule(
        SuccessfulTransformationPath learningPath,
        ReusableRule learned,
        DynamicPatternOperator operator
    ) {
    }
}
