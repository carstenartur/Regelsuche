package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.memory.SearchMemory;
import de.regelsuche.search.memory.TranspositionEntry;
import de.regelsuche.search.telemetry.SearchEvent;
import de.regelsuche.search.telemetry.SearchEventType;
import de.regelsuche.search.telemetry.SearchObserver;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
        assertTrue(states.stream().allMatch(
            state -> state.canonicalHash().startsWith(BestFirstSearchStrategy.ValueIdentitySession.HASH_PREFIX)));
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

    @Test
    void valueIdentitySessionCollapsesCanonicalVariantsAndCachesRepeatedInput() {
        ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
        try (BestFirstSearchStrategy.ValueIdentitySession identity =
                new BestFirstSearchStrategy.ValueIdentitySession(canonicalizer)) {
            String first = identity.valueHash("(a + b) + c");
            String equivalent = identity.valueHash("c + a + b");

            assertEquals(first, equivalent);
            assertTrue(first.startsWith(BestFirstSearchStrategy.ValueIdentitySession.HASH_PREFIX));
            assertNotEquals(canonicalizer.stableHash("(a + b) + c"), first);
            assertEquals(2, identity.cacheMisses());
            assertEquals(0, identity.cacheHits());
            assertEquals(2, identity.cachedExpressionCount());
            assertTrue(identity.internedValueCount() >= 4);

            assertEquals(equivalent, identity.valueHash("c + a + b"));
            assertEquals(1, identity.cacheHits());
            assertEquals(2, identity.cacheMisses());
        }
    }

    @Test
    void valueIdentityNeverComputesLegacyHashes() {
        ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer() {
            @Override
            public String stableHash(String expression) {
                throw new AssertionError("BestFirst must not enter a legacy hash domain");
            }
        };
        SearchProblem problem = new SearchProblem(
            "x",
            new KnownStateTransformationEngine(),
            new ExpressionScorer(),
            canonicalizer,
            new SearchHeuristic(1, 8, 1, 2, 4, 8)
        );

        List<SearchState> states = new BestFirstSearchStrategy().search(problem);

        assertEquals(List.of("x", "a"), states.stream().map(SearchState::expression).toList());
        assertTrue(states.stream().allMatch(
            state -> state.canonicalHash().startsWith(BestFirstSearchStrategy.ValueIdentitySession.HASH_PREFIX)));
    }

    @Test
    void malformedTransformationOutputFailsExplicitly() {
        TransformationEngine malformed = expression -> expression.equals("x")
            ? List.of(new Transformation(
                "broken-rule", "broken(", RewriteKind.NORMALIZE,
                false, 0, true, "broken-rule:broken("))
            : List.of();
        SearchProblem problem = new SearchProblem(
            "x",
            malformed,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(1, 8, 1, 2, 4, 8)
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new BestFirstSearchStrategy().search(problem));

        assertTrue(exception.getMessage().contains("ExprValue"));
        assertTrue(exception.getMessage().contains("broken("));
    }

    @Test
    void transpositionPrunedStatesAreNotReportedAsExploredResults() {
        RecordingObserver observer = new RecordingObserver();
        ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
        ExpressionScorer scorer = new ExpressionScorer();
        SearchMemory memory = new SearchMemory();
        rememberKnownAState(memory, canonicalizer, scorer);
        SearchProblem problem = new SearchProblem(
            "x",
            new KnownStateTransformationEngine(),
            scorer,
            canonicalizer,
            new SearchHeuristic(1, 8, 1, 2, 4, 8)
        ).withMemory(memory).withObserver(observer);

        List<SearchState> states = new BestFirstSearchStrategy().search(problem);

        assertEquals(List.of("x"), states.stream().map(SearchState::expression).toList());
        assertFalse(memory.decisions().isEmpty());
        assertTrue(observer.events().stream().anyMatch(
            event -> event.type() == SearchEventType.STATE_PRUNED_TRANSPOSITION));
        try (BestFirstSearchStrategy.ValueIdentitySession identity =
                new BestFirstSearchStrategy.ValueIdentitySession(canonicalizer)) {
            assertTrue(memory.table().lookup(identity.valueHash("a")).isPresent(),
                "the state must remain recorded under its ValueKey-derived identity");
        }
    }

    private void rememberKnownAState(
            SearchMemory memory,
            ExpressionCanonicalizer canonicalizer,
            ExpressionScorer scorer) {
        String expression = "a";
        String hash;
        try (BestFirstSearchStrategy.ValueIdentitySession identity =
                new BestFirstSearchStrategy.ValueIdentitySession(canonicalizer)) {
            hash = identity.valueHash(expression);
        }
        memory.table().record(new TranspositionEntry(
            hash,
            expression,
            scorer.score(expression).weightedTotal(),
            1,
            "known#a",
            Set.of("known_rule"),
            1,
            Instant.EPOCH,
            Instant.EPOCH
        ));
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

    private static final class KnownStateTransformationEngine implements TransformationEngine {
        @Override
        public List<Transformation> transform(String expression) {
            if (!"x".equals(expression)) {
                return List.of();
            }
            return List.of(
                new Transformation("known_rule", "a", RewriteKind.NORMALIZE, false, 0, true, "known_rule:a")
            );
        }
    }
}
