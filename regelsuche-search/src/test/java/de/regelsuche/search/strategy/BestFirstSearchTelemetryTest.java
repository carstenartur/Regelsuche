package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.telemetry.SearchEvent;
import de.regelsuche.search.telemetry.SearchEventType;
import de.regelsuche.search.telemetry.SearchObserver;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BestFirstSearchTelemetryTest {

    @Test
    void bestFirstEmitsDeterministicRuntimeEvents() {
        RecordingObserver observer = new RecordingObserver();
        SearchProblem problem = baseProblem().withObserver(observer);

        List<SearchState> states = new BestFirstSearchStrategy().search(problem);

        assertEquals(List.of("x", "a"), states.stream().map(SearchState::expression).toList());
        assertFalse(observer.events().isEmpty());
        for (int i = 0; i < observer.events().size(); i++) {
            assertEquals(i, observer.events().get(i).sequence());
        }
        assertEquals(SearchEventType.SEARCH_STARTED, observer.events().getFirst().type());
        assertEquals(SearchEventType.SEARCH_FINISHED, observer.events().getLast().type());
        assertTrue(observer.events().stream().anyMatch(event -> event.type() == SearchEventType.STATE_DEQUEUED));
        assertTrue(observer.events().stream().anyMatch(event -> event.type() == SearchEventType.STATE_VISITED));
        assertTrue(observer.events().stream().anyMatch(event -> event.type() == SearchEventType.STATE_EXPANDED));
        assertTrue(observer.events().stream().anyMatch(event -> event.type() == SearchEventType.TRANSFORMATION_GENERATED));
        assertTrue(observer.events().stream().anyMatch(event -> event.type() == SearchEventType.STATE_ENQUEUED));
        assertTrue(observer.events().stream().anyMatch(event -> event.type() == SearchEventType.STATE_PRUNED_DEPTH));
        assertTrue(observer.events().stream().anyMatch(event -> event.type() == SearchEventType.STATE_PRUNED_BUDGET));
        SearchEvent budgetEvent = observer.events().stream()
            .filter(event -> event.type() == SearchEventType.STATE_PRUNED_BUDGET)
            .findFirst()
            .orElseThrow();
        assertEquals("x", budgetEvent.expression());
        assertEquals("max-candidates-per-state", budgetEvent.pruningReason());
    }

    @Test
    void telemetryObserverDoesNotChangeSearchResult() {
        RecordingObserver observer = new RecordingObserver();

        List<String> withoutObserver = new BestFirstSearchStrategy().search(baseProblem()).stream()
            .map(SearchState::expression)
            .toList();
        List<String> withObserver = new BestFirstSearchStrategy().search(baseProblem().withObserver(observer)).stream()
            .map(SearchState::expression)
            .toList();

        assertEquals(withoutObserver, withObserver);
        assertFalse(observer.events().isEmpty());
    }

    private SearchProblem baseProblem() {
        return new SearchProblem(
            "x",
            new UnorderedTransformationEngine(),
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(1, 8, 1, 2, 1, 8)
        );
    }

    private static final class RecordingObserver implements SearchObserver {
        private final List<SearchEvent> events = new ArrayList<>();

        @Override
        public void onEvent(SearchEvent event) {
            events.add(event);
        }

        List<SearchEvent> events() {
            return List.copyOf(events);
        }
    }

    private static final class UnorderedTransformationEngine implements TransformationEngine {
        @Override
        public List<Transformation> transform(String expression) {
            if (!"x".equals(expression)) {
                return List.of();
            }
            return List.of(
                new Transformation("rule_z", "z", RewriteKind.NORMALIZE, false, 0, true, "rule_z:z"),
                new Transformation("rule_a", "a", RewriteKind.NORMALIZE, false, 0, true, "rule_a:a")
            );
        }
    }
}
