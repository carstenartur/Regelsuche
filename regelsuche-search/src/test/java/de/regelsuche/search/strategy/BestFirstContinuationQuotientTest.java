package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class BestFirstContinuationQuotientTest {
    @Test void collapsesDiamondHistoriesAndPreservesSelectedPrimitiveLineage() {
        TransformationEngine engine = expression -> switch (expression) {
            case "x" -> List.of(step("left", "a"), step("right", "b"));
            case "a", "b" -> List.of(step("join-" + expression, "y"));
            case "y" -> List.of(step("finish", "z"));
            default -> List.of();
        };
        var problem = problem(engine, 5, 3);
        var old = new BestFirstSearchStrategy().search(problem);
        var result = quotient().searchWithExecutionDiagnostics(problem);
        assertEquals(2, old.stream().filter(s -> s.expression().equals("y")).count());
        assertEquals(1, result.search().states().stream().filter(s -> s.expression().equals("y")).count());
        assertEquals(1, result.search().states().stream().filter(s -> s.expression().equals("z")).count());
        var z = result.search().states().stream().filter(s -> s.expression().equals("z")).findFirst().orElseThrow();
        assertEquals(List.of("x", "a", "y", "z"), z.path());
        assertEquals(List.of("left", "join-a", "finish"), z.appliedRuleIds());
        assertTrue(z.recordedExecution().isPresent());
        assertEquals(3, z.transformations().size());
        assertTrue(result.quotient().duplicateArrivals() > 0);
        assertEquals("continuation-quotient/v1", result.policy().version());
    }

    @Test void laterShallowerArrivalRetainsItsRemainingDepth() {
        TransformationEngine engine = expression -> switch (expression) {
            case "x" -> List.of(step("long", "a"), step("short", "b"));
            case "a" -> List.of(step("long2", "c"));
            case "c", "b" -> List.of(step("join-" + expression, "y"));
            case "y" -> List.of(step("finish", "z"));
            default -> List.of();
        };
        var scorer = new ExpressionScorer() {
            @Override public ExpressionScore score(String expression) {
                return new ExpressionScore(expression.equals("b") ? 100 : 0, 0, 0, 0, 0);
            }
        };
        var problem = new SearchProblem("x", engine, scorer, new ExpressionCanonicalizer(),
            new SearchHeuristic(3, 100, 1, 3, 20, 20));
        var result = quotient().search(problem);
        assertEquals(List.of(3, 2), result.stream().filter(s -> s.expression().equals("y"))
            .map(SearchState::depth).toList());
        assertEquals(List.of("x", "b", "y", "z"), result.stream().filter(s -> s.expression().equals("z"))
            .findFirst().orElseThrow().path());
    }

    @Test void shallowerButExpansionExhaustedPathDoesNotDominateAUsefulLabel() {
        TransformationEngine engine = expression -> switch (expression) {
            case "x" -> List.of(expand("short", "y"), step("long", "a"));
            case "a" -> List.of(step("long2", "y"));
            case "y" -> List.of(expand("finish", "z"));
            default -> List.of();
        };
        var result = quotient().search(problem(engine, 4, 1));
        assertEquals(2, result.stream().filter(s -> s.expression().equals("y")).count());
        assertEquals(List.of("x", "a", "y", "z"), result.stream().filter(s -> s.expression().equals("z"))
            .findFirst().orElseThrow().path());
    }

    @Test void syntaxSensitiveContinuationsAndExactTargetsRemainSeparate() {
        TransformationEngine engine = expression -> switch (expression) {
            case "x" -> List.of(step("a", "a + b"), step("b", "b + a"));
            case "b + a" -> List.of(step("finish", "z"));
            default -> List.of();
        };
        var result = quotient().searchWithExecutionDiagnostics(problem(engine, 3, 3));
        assertTrue(result.search().states().stream().anyMatch(s -> s.expression().equals("z")));
        assertEquals(3, result.quotient().semanticNodes());
        assertEquals(4, result.quotient().syntaxObservations());
        assertTrue(quotient().searchWithDiagnostics(problem(engine, 3, 3)
            .withTarget(SearchProblem.SearchTarget.syntaxExact("b + a"))).reached());
    }

    @Test void keepsOppositeAssumptionsSeparate() {
        TransformationEngine engine = expression -> switch (expression) {
            case "x" -> List.of(conditional("positive", "y", "x > 0"), conditional("negative", "y", "x < 0"));
            case "y" -> List.of(step("finish", "z"));
            default -> List.of();
        };
        var states = quotient().search(problem(engine, 3, 3));
        assertEquals(List.of(List.of("x < 0"), List.of("x > 0")), states.stream()
            .filter(s -> s.expression().equals("z")).map(SearchState::assumptions)
            .sorted((a,b) -> a.toString().compareTo(b.toString())).toList());
    }

    @Test void assumptionSensitiveProviderRegeneratesExactlyOncePerStateContext() {
        var provider = new ContextualProvider();
        var result = BestFirstSearchStrategy.preparedQuotient().searchWithExecutionDiagnostics(problem(provider, 3, 3));

        assertTrue(result.search().states().stream().anyMatch(state -> state.expression().equals("positiveResult")));
        assertTrue(result.search().states().stream().anyMatch(state -> state.expression().equals("negativeResult")));
        assertEquals(2, provider.joinCalls);
    }

    @Test void preparedQuotientRejectsAnUndeclaredProviderBeforeCallingIt() {
        var problem = problem(source -> { throw new AssertionError("provider was called"); }, 3, 3);
        assertThrows(IllegalArgumentException.class, () -> BestFirstSearchStrategy.preparedQuotient().search(problem));
    }

    @Test void preparedRootRetainsParserIdentityAndBindsItsExactSource() {
        var bound = new ExpressionParser().parseExactTerm(" ((x)) ");
        StateLocalTransformationEngine engine = (source, ast, assumptions) -> {
            assertEquals(" ((x)) ", source);
            assertSame(bound.expression(), ast);
            assertEquals(List.of("x != 0"), assumptions);
            return List.of();
        };
        var strategy = BestFirstSearchStrategy.preparedQuotient();
        var problem = new SearchProblem(bound.source(), engine, new ExpressionScorer(), new ExpressionCanonicalizer(),
            new SearchHeuristic(2, 20, 1));
        var states = strategy.searchPrepared(problem, bound, List.of("x≠0")).search().states();
        assertEquals(List.of("x != 0"), states.getFirst().assumptions());
        assertThrows(IllegalArgumentException.class, () -> strategy.searchPrepared(problem,
            new ExpressionParser().parseExactTerm("x"), List.of()));
    }

    @Test void preparedQuotientPreservesNonDominatedExpansionBudget() {
        StateLocalTransformationEngine engine = (source, ast, assumptions) -> switch (source) {
            case "x" -> List.of(expand("short", "y"), step("long", "a"));
            case "a" -> List.of(step("long2", "y"));
            case "y" -> List.of(expand("finish", "z"));
            default -> List.of();
        };
        var result = BestFirstSearchStrategy.preparedQuotient().searchWithExecutionDiagnostics(problem(engine, 4, 1));
        assertEquals(List.of("x", "a", "y", "z"), result.search().states().stream()
            .filter(s -> s.expression().equals("z")).findFirst().orElseThrow().path());
        assertTrue(result.quotient().successorCacheHits() > 0);
    }

    private static final class ContextualProvider implements StateLocalTransformationEngine {
        private int joinCalls;

        @Override public List<Transformation> transform(String expression) {
            return expression.equals("x")
                ? List.of(conditional("positive", "y", "x > 0"), conditional("negative", "y", "x < 0"))
                : List.of();
        }

        @Override public List<Transformation> transformPrepared(String expression, Expr parsed, List<String> assumptions) {
            assertEquals(expression, ExpressionFormatter.format(parsed));
            if (!expression.equals("y")) return transform(expression);
            joinCalls++;
            return assumptions.equals(List.of("x > 0")) ? List.of(step("finish", "positiveResult"))
                : List.of(step("finish", "negativeResult"));
        }
    }

    @Test void repeatedApplicationIdentifierDoesNotBlockAChangedState() {
        TransformationEngine engine = expression -> switch (expression) {
            case "x" -> List.of(new Transformation("step", "y", RewriteKind.NORMALIZE, false, 0, true, "same-id"));
            case "y" -> List.of(new Transformation("step", "z", RewriteKind.NORMALIZE, false, 0, true, "same-id"));
            default -> List.of();
        };
        assertFalse(new BestFirstSearchStrategy().search(problem(engine, 4, 3)).stream()
            .anyMatch(s -> s.expression().equals("z")));
        var z = quotient().search(problem(engine, 4, 3)).stream().filter(s -> s.expression().equals("z"))
            .findFirst().orElseThrow();
        assertEquals(2, z.transformations().size());
        assertEquals(List.of("step", "step"), z.appliedRuleIds());
    }

    @Test void equivalentCycleTerminatesByDominanceWithoutHistoryInTheKey() {
        TransformationEngine engine = expression -> expression.equals("x")
            ? List.of(step("out", "y")) : List.of(step("back", "x"));
        var result = quotient().searchWithExecutionDiagnostics(problem(engine, 100, 100));
        assertEquals(List.of("x", "y"), result.search().states().stream().map(SearchState::expression).toList());
        assertEquals(2, result.quotient().retainedLabels());
    }

    @Test void unverifiedLineageDoesNotDominateVerifiedLineage() {
        TransformationEngine engine = expression -> switch (expression) {
            case "x" -> List.of(new Transformation("a-unknown", "y", RewriteKind.NORMALIZE, false, 0, false, "unknown"),
                step("b-checked", "y"));
            case "y" -> List.of(step("finish", "z"));
            default -> List.of();
        };
        var result = quotient().search(problem(engine, 3, 3));
        assertTrue(result.stream().anyMatch(s -> s.expression().equals("z")
            && s.equivalencePreservingFlags().stream().allMatch(Boolean::booleanValue)));
    }

    @Test void historicalPolicyRemainsTheDefault() {
        var result = new BestFirstSearchStrategy().searchWithExecutionDiagnostics(problem(e -> List.of(), 2, 2));
        assertEquals(BestFirstSearchStrategy.IdentityPolicy.APPLICATION_HISTORY_V1, result.policy());
        assertEquals(0, result.quotient().semanticNodes());
    }

    @Test void arenaControlPreservesHistoryWithTheSameDeclaredTieOrder() {
        TransformationEngine engine = expression -> switch (expression) {
            case "x" -> List.of(step("left", "a"), step("right", "b"));
            case "a", "b" -> List.of(step("join-" + expression, "y"));
            case "y" -> List.of(step("finish", "z"));
            default -> List.of();
        };
        var order = BestFirstSearchStrategy.TieOrder.SYNTAX_V1;
        var old = new BestFirstSearchStrategy(BestFirstSearchStrategy.IdentityPolicy.APPLICATION_HISTORY_V1, order)
            .search(problem(engine, 5, 3));
        var arena = new BestFirstSearchStrategy(BestFirstSearchStrategy.IdentityPolicy.ARENA_APPLICATION_HISTORY_V1, order)
            .searchWithExecutionDiagnostics(problem(engine, 5, 3));
        assertEquals(old.stream().map(SearchState::path).toList(), arena.search().states().stream().map(SearchState::path).toList());
        assertEquals(order, arena.tieOrder());
        assertTrue(arena.quotient().arenaStructuralBytes() > 0);
        assertEquals(arena.quotient().arenaEntries(), arena.quotient().digestComputations());
    }

    @Test void historyDependentSubclassCannotClaimTheStateLocalContinuationPolicy() {
        TransformationEngine engine = expression -> switch (expression) {
            case "x" -> List.of(step("left", "a"), step("right", "b"));
            case "a", "b" -> List.of(step("join-" + expression, "y"));
            case "y" -> List.of(step("dead1", "d"), step("dead2", "e"), step("finish", "z"));
            default -> List.of();
        };
        var problem = new SearchProblem("x", engine, new ExpressionScorer(), new ExpressionCanonicalizer(),
            new SearchHeuristic(5, 100, 1, 3, 2, 20));
        for (var policy : List.of(BestFirstSearchStrategy.IdentityPolicy.APPLICATION_HISTORY_V1,
                BestFirstSearchStrategy.IdentityPolicy.ARENA_APPLICATION_HISTORY_V1)) {
            assertTrue(new HistoryOrderedSearch(policy).search(problem).stream().anyMatch(s -> s.expression().equals("z")));
        }
        assertThrows(IllegalArgumentException.class, () ->
            new HistoryOrderedSearch(BestFirstSearchStrategy.IdentityPolicy.CONTINUATION_QUOTIENT_V1));
    }

    private static final class HistoryOrderedSearch extends BestFirstSearchStrategy {
        HistoryOrderedSearch(IdentityPolicy policy) { super(policy, TieOrder.SYNTAX_V1); }
        @Override protected List<Transformation> orderTransformations(SearchProblem problem, SearchState state,
                List<Transformation> moves, boolean target, ToIntFunction<Transformation> distance) {
            moves.sort(Comparator.comparing(t -> state.path().contains("b") && t.transformedExpression().equals("z")
                ? "0" : t.transformedExpression()));
            return moves;
        }
    }

    private static BestFirstSearchStrategy quotient() {
        return new BestFirstSearchStrategy(BestFirstSearchStrategy.IdentityPolicy.CONTINUATION_QUOTIENT_V1);
    }
    private static SearchProblem problem(TransformationEngine engine, int depth, int expansions) {
        return new SearchProblem("x", engine, new ExpressionScorer(), new ExpressionCanonicalizer(),
            new SearchHeuristic(depth, 100, 1, expansions, 20, 20));
    }
    private static Transformation step(String id, String target) { return new Transformation(id, target); }
    private static Transformation expand(String id, String target) {
        return new Transformation(id, target, RewriteKind.EXPAND, true, 1, true, id + ":" + target);
    }
    private static Transformation conditional(String id, String target, String assumption) {
        return new Transformation(id, target, RewriteKind.NORMALIZE, false, 0, true, id, List.of(assumption));
    }
}
