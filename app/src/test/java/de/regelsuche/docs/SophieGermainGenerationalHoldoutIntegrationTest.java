package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.docs.HiddenRulePilotRunner.CandidateSnapshot;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeResult;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.evolution.ExactPolynomialPatternVerificationService;
import de.regelsuche.mining.DynamicOperatorCompiler;
import de.regelsuche.mining.DynamicPatternOperator;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * First historical generation-transfer slice.
 *
 * <p>The TRAIN task may use the bounded hand-written Sophie-Germain preparation
 * operator, but the held-out alpha-distinct instance is first exposed after the
 * learned candidate has been frozen. The one-step ablation then asks whether the
 * learned rule, rather than the two-step primitive path, makes the held-out
 * factorization reachable under the same depth budget.</p>
 *
 * <p>This characterizes same-family transfer only. It deliberately does not yet
 * claim rediscovery from independent historical precursor families.</p>
 */
class SophieGermainGenerationalHoldoutIntegrationTest {
    private static final String TRAIN_CASE_ID = "case-002";
    private static final String HOLDOUT_INPUT = "a^4 + 4*b^4";
    private static final String EXPECTED_HOLDOUT_FACTORIZATION =
        "(a^2 + 2*b^2 - 2*a*b) * (a^2 + 2*b^2 + 2*a*b)";
    private static final SearchHeuristic HOLDOUT_HEURISTIC =
        new SearchHeuristic(1, 160, 1, 16, 160, 160);

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ExactPolynomialPatternVerificationService exactVerifier =
        new ExactPolynomialPatternVerificationService();

    @Test
    @Timeout(120)
    void frozenGenerationZeroRuleTransfersToAnUnseenAlphaDistinctHoldout() {
        RuntimeTask trainingTask = HiddenRulePilotRuntimeCatalog.tasks().stream()
            .filter(task -> TRAIN_CASE_ID.equals(task.opaqueCaseId()))
            .findFirst()
            .orElseThrow();
        assertFalse(trainingTask.observableInput().contains(HOLDOUT_INPUT));
        assertFalse(trainingTask.observableInput()
            .contains(EXPECTED_HOLDOUT_FACTORIZATION));

        RuntimeResult training = new HiddenRulePilotRunner().run(trainingTask);
        assertTrue(training.frozen(), training.toString());
        assertTrue(training.validationEvidence().passed(), training.toString());
        assertTrue(training.holdouts().allPassed(), training.toString());

        CandidateSnapshot candidate = training.candidate();
        assertNotNull(candidate, training.toString());
        var learnedIdentity = exactVerifier.verify(
            candidate.leftPattern(),
            candidate.rightPattern());
        assertTrue(learnedIdentity.proved(), learnedIdentity.toString());

        DynamicOperatorCompiler.CompilationResult compilation =
            new DynamicOperatorCompiler().compile(
                "sophie-germain-generation-zero",
                "sophie-germain-train-freeze-v1",
                candidate.leftPattern(),
                candidate.rightPattern());
        assertTrue(compilation.isSuccess(), compilation.rejectionReason());
        DynamicPatternOperator learnedRule = compilation.operator().orElseThrow();

        List<Transformation> heldOutApplications =
            learnedRule.generateCandidates(HOLDOUT_INPUT);
        assertFalse(heldOutApplications.isEmpty(),
            candidate.leftPattern() + " -> " + candidate.rightPattern());
        String learnedTarget = heldOutApplications.getFirst()
            .transformedExpression();
        var historicalIdentity = exactVerifier.verify(
            learnedTarget,
            EXPECTED_HOLDOUT_FACTORIZATION);
        assertTrue(historicalIdentity.proved(),
            learnedTarget + " != " + EXPECTED_HOLDOUT_FACTORIZATION);

        TransformationEngine primitiveEngine = trainingTask.primitiveEngine();
        GoalSearchResult baseline = search(
            primitiveEngine,
            HOLDOUT_INPUT,
            learnedTarget);
        GoalSearchResult accumulated = search(
            new HypothesisTransformationEngine(
                primitiveEngine,
                List.of(learnedRule),
                8),
            HOLDOUT_INPUT,
            learnedTarget);

        assertFalse(baseline.reached(), baseline.toString());
        assertTrue(accumulated.reached(), accumulated.toString());
        assertNotNull(accumulated.reachedState(), accumulated.toString());
        assertTrue(accumulated.reachedState().appliedRuleIds()
            .contains(learnedRule.ruleId()), accumulated.reachedState().toString());
    }

    private GoalSearchResult search(
        TransformationEngine engine,
        String input,
        String target
    ) {
        SearchTarget syntaxTarget = SearchTarget.syntaxExact(
            ExpressionFormatter.format(parser.parseTerm(target)));
        SearchProblem problem = new SearchProblem(
            input,
            engine,
            scorer,
            canonicalizer,
            HOLDOUT_HEURISTIC).withTarget(syntaxTarget);
        return new BestFirstSearchStrategy().searchWithDiagnostics(problem);
    }
}
