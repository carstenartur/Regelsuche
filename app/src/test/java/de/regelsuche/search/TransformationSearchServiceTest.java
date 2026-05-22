package de.regelsuche.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.graph.GraphSnapshot;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.notify.SimplificationNotifier;
import de.regelsuche.scoring.cost.TransformationGoal;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.search.strategy.SearchStrategy;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TransformationSearchServiceTest {
    @Test
    void findsAndStoresSimplification() {
        TransformationEngine engine = expression -> expression.equals("x + 0")
            ? List.of(new Transformation("remove_zero", "x"))
            : List.of();

        TransformationSearchService service = new TransformationSearchService(
            engine,
            new InMemoryExpressionGraphStore(),
            new SearchHeuristic(3, 50, 1),
            (from, to) -> {
            }
        );

        service.submit(new InputRequest(InputType.TERM, "x + 0")).join();

        assertTrue(service.getBestSolution().isPresent());
        assertEquals("x", service.getBestSolution().orElseThrow().simplifiedExpression());

        GraphSnapshot snapshot = service.getGraphSnapshot();
        assertTrue(snapshot.nodes().contains("x + 0"));
        assertTrue(snapshot.nodes().contains("x"));
        assertFalse(snapshot.edges().isEmpty());
        assertFalse(snapshot.edges().getFirst().canonicalHash().isBlank());
        assertTrue(snapshot.edges().getFirst().scoreBefore() > snapshot.edges().getFirst().scoreAfter());
        service.shutdown();
    }

    @Test
    void respectsDepthHeuristic() {
        TransformationEngine engine = expression -> {
            if (expression.equals("x + 0")) {
                return List.of(new Transformation("step1", "x"));
            }
            if (expression.equals("x")) {
                return List.of(new Transformation("step2", "x1"));
            }
            return List.of();
        };

        TransformationSearchService service = new TransformationSearchService(
            engine,
            new InMemoryExpressionGraphStore(),
            new SearchHeuristic(1, 50, 1),
            (from, to) -> {
            }
        );

        service.submit(new InputRequest(InputType.TERM, "x + 0")).join();
        GraphSnapshot snapshot = service.getGraphSnapshot();
        assertTrue(snapshot.nodes().contains("x"));
        assertTrue(snapshot.nodes().stream().noneMatch("x1"::equals));
        service.shutdown();
    }

    @Test
    void notifiesOnSignificantImprovement() {
        AtomicInteger notifications = new AtomicInteger();
        TransformationEngine engine = expression -> expression.equals("x + 0 + 0")
            ? List.of(new Transformation("remove_zero", "x"))
            : List.of();
        SimplificationNotifier notifier = (from, to) -> notifications.incrementAndGet();

        TransformationSearchService service = new TransformationSearchService(
            engine,
            new InMemoryExpressionGraphStore(),
            new SearchHeuristic(2, 50, 2),
            notifier
        );

        service.submit(new InputRequest(InputType.TERM, "x + 0 + 0")).join();

        assertEquals(1, notifications.get());
        service.shutdown();
    }

    @Test
    void submitWithGoalAttachesCostModelToSearchProblem() {
        AtomicReference<SearchProblem> seenProblem = new AtomicReference<>();
        SearchStrategy capturingStrategy = problem -> {
            seenProblem.set(problem);
            return List.of(new SearchState(
                problem.rootExpression(),
                0,
                problem.scorer().score(problem.rootExpression()),
                List.of(problem.rootExpression()),
                List.of(),
                java.util.Set.of(),
                0,
                problem.canonicalizer().stableHash(problem.rootExpression()),
                null,
                null,
                de.regelsuche.transform.RewriteKind.NORMALIZE,
                false,
                0,
                true,
                0
            ));
        };
        TransformationSearchService service = new TransformationSearchService(
            expression -> List.of(),
            new InMemoryExpressionGraphStore(),
            new SearchHeuristic(1, 10, 1),
            (from, to) -> {
            },
            capturingStrategy
        );

        service.submit(new InputRequest(InputType.TERM, "x + 0"), TransformationGoal.TEACHING_FRIENDLY).join();
        SearchProblem problem = seenProblem.get();
        assertTrue(problem != null && problem.costModel() != null);
        assertEquals(
            TransformationGoal.TEACHING_FRIENDLY.defaultCostModel().id(),
            problem.costModel().id()
        );
        service.shutdown();
    }
}
