package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.memory.SearchMemory;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class HeuristicMemoryContinuationTest {
    @ParameterizedTest
    @MethodSource("strategies")
    void observationsFromAnotherControllerAndRunDoNotPruneTheCurrentSearch(SearchStrategy strategy) {
        var problem = ruleProblem("x + 0", "ast_add_zero_right", 4);
        var expected = strategy.search(problem);
        assertEquals(List.of("x + 0", "x"), expected.stream().map(SearchState::expression).toList());

        var memory = new SearchMemory();
        var shallow = ruleProblem("x + 0", "ast_add_zero_right", 1);
        SearchStrategy otherController = strategy instanceof BeamSearchStrategy
            ? new RandomMonteCarloSearchStrategy(7) : new BeamSearchStrategy();
        otherController.search(shallow.withMemory(memory));
        assertTrue(memory.table().lookup(expected.getLast().canonicalHash()).isPresent());

        assertEquals(expected, strategy.search(problem.withMemory(memory)));
        assertEquals(expected, strategy.search(problem.withMemory(memory)));
        assertTrue(memory.decisions().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("pathRetainingStrategies")
    void freshMemoryPreservesTheReductionViaTheOtherRuleApplication(SearchStrategy strategy) {
        var problem = ruleProblem("((x^2)^2)^2", "ast_power_of_power", 4);
        var expected = strategy.search(problem);
        var reduction = expected.stream().filter(state -> state.expression().equals("x ^ 8"))
            .findFirst().orElseThrow();
        assertEquals(List.of("((x^2)^2)^2", "(x ^ 4) ^ 2", "x ^ 8"), reduction.path());

        var memory = new SearchMemory();
        assertEquals(expected, strategy.search(problem.withMemory(memory)));
        assertFalse(memory.table().entries().isEmpty());
    }

    @Test
    void runLocalKeysCompareExactSyntaxAssumptionsAndApplicationSets() {
        var visits = new RunVisitedStates(null);
        var applications = Set.of("a,b", "c");
        var first = state("x + y", applications, List.of("x != 0"));

        assertTrue(visits.admit(first));
        assertTrue(visits.admit(state("y + x", applications, List.of("x != 0"))));
        assertTrue(visits.admit(state("x + y", applications, List.of("y != 0"))));
        assertTrue(visits.admit(state("x + y", Set.of("a", "b,c"), List.of("x != 0"))));
        assertTrue(visits.contains(first));
        assertFalse(visits.admit(first));
    }

    private static Stream<SearchStrategy> strategies() {
        return Stream.concat(pathRetainingStrategies(), Stream.of(new StructuralDiversitySearchStrategy()));
    }

    private static Stream<SearchStrategy> pathRetainingStrategies() {
        return Stream.of(new BeamSearchStrategy(), new RandomMonteCarloSearchStrategy(7));
    }

    private static SearchProblem ruleProblem(String root, String ruleId, int maxDepth) {
        var engine = new AstRewriteTransformationEngine(
            AstRewriteTransformationEngine.defaultRules().stream()
                .filter(rule -> rule.id().equals(ruleId)).toList());
        return new SearchProblem(root, engine, new ExpressionScorer(), new ExpressionCanonicalizer(),
            new SearchHeuristic(maxDepth, 32, 1, 4, 32, 16));
    }

    private static SearchState state(String expression, Set<String> applications, List<String> assumptions) {
        return new SearchState(expression, 2, new ExpressionScorer().score(expression),
            List.of("root", "middle", expression), List.of("rule", "rule"), applications, 0,
            "same-value-hash", "middle", "rule", RewriteKind.NORMALIZE, false, 0, true, 0,
            List.of(), List.of(), assumptions);
    }
}
