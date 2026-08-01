package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionContext;
import de.regelsuche.ast.Expr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PreparedAstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreparedAstSearchParityTest {
    private static final String FIXED_WORK_INPUT =
        "((x + 1) * (x + 2)) + (x * (x + 3))";
    private static final SearchHeuristic FIXED_WORK_HEURISTIC =
        new SearchHeuristic(4, 128, 1, 4, 40, 8);

    private static final String TARGETED_INPUT =
        "(((x + 0) * 1) + ((y * 0) + 0))";
    private static final String TARGETED_OUTPUT = "x";
    private static final SearchHeuristic TARGETED_HEURISTIC =
        new SearchHeuristic(6, 512, 1, 4, 40, 8);
    private static final List<RewriteRule> TARGETED_RULES =
        AstRewriteTransformationEngine.defaultRules().stream()
            .filter(rule -> !rule.id().equals("ast_canonical_normalize"))
            .toList();

    private final BestFirstSearchStrategy strategy =
        new BestFirstSearchStrategy();

    @Test
    void fixedWorkSearchProducesByteEquivalentSemanticResult() {
        GoalSearchResult reference = strategy.searchWithDiagnostics(problem(
            FIXED_WORK_INPUT,
            new AstRewriteTransformationEngine(),
            FIXED_WORK_HEURISTIC));
        GoalSearchResult prepared = strategy.searchWithDiagnostics(problem(
            FIXED_WORK_INPUT,
            new PreparedAstRewriteTransformationEngine(),
            FIXED_WORK_HEURISTIC));

        assertEquals(reference, prepared);
        assertTrue(reference.metrics().exploredStates() > 1);
        assertTrue(reference.metrics().generatedTransformations() > 0);
    }

    @Test
    void targetedMultiStepSearchProducesTheSameReachedPathAndWork() {
        GoalSearchResult reference = strategy.searchWithDiagnostics(
            targetedProblem(new AstRewriteTransformationEngine(TARGETED_RULES))
                .withTarget(SearchTarget.syntaxExact(TARGETED_OUTPUT)));
        GoalSearchResult prepared = strategy.searchWithDiagnostics(
            targetedProblem(new PreparedAstRewriteTransformationEngine(
                TARGETED_RULES))
                .withTarget(SearchTarget.syntaxExact(TARGETED_OUTPUT)));

        assertEquals(reference, prepared);
        assertTrue(reference.reached());
        assertNotNull(reference.reachedState());
        assertTrue(
            reference.reachedState().depth() >= 3,
            "targeted control must retain an explicit multi-step rewrite path, depth="
                + reference.reachedState().depth());
    }

    private static SearchProblem problem(
        String input,
        TransformationEngine engine,
        SearchHeuristic heuristic
    ) {
        return new SearchProblem(
            input,
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            heuristic);
    }

    private static SearchProblem targetedProblem(TransformationEngine engine) {
        return new SearchProblem(
            TARGETED_INPUT,
            engine,
            new ExpressionScorer(),
            new SyntacticSearchCanonicalizer(),
            TARGETED_HEURISTIC);
    }

    private static final class SyntacticSearchCanonicalizer
            extends ExpressionCanonicalizer {
        @Override
        public Expr canonicalize(Expr expression) {
            return expression;
        }

        @Override
        public Expr canonicalize(
            Expr expression,
            AssumptionContext context
        ) {
            return expression;
        }
    }
}
