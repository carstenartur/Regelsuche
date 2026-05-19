package de.regelsuche.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.graph.GraphSnapshot;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.notify.SimplificationNotifier;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
}
