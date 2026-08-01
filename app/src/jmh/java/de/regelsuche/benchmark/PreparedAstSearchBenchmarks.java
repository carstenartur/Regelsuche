package de.regelsuche.benchmark;

import de.regelsuche.assumption.AssumptionContext;
import de.regelsuche.ast.Expr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalMetrics;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PreparedAstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * End-to-end fixed-work search comparison for the reference and prepared AST
 * transformation engines.
 *
 * <p>The primary metric remains average milliseconds per complete search. JMH
 * auxiliary event counters expose the semantic work performed per operation,
 * so a faster result cannot be mistaken for a backend that silently explores
 * fewer states or produces fewer candidates.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class PreparedAstSearchBenchmarks {
    static final String FIXED_WORK_INPUT =
        "((x + 1) * (x + 2)) + (x * (x + 3))";
    static final SearchHeuristic FIXED_WORK_HEURISTIC =
        new SearchHeuristic(4, 128, 1, 4, 40, 8);

    static final String TARGETED_INPUT =
        "(((x + 0) * 1) + ((y * 0) + 0))";
    static final String TARGETED_OUTPUT = "x";
    static final SearchHeuristic TARGETED_HEURISTIC =
        new SearchHeuristic(6, 512, 1, 4, 40, 8);
    static final List<RewriteRule> TARGETED_RULES =
        AstRewriteTransformationEngine.defaultRules().stream()
            .filter(rule -> !rule.id().equals("ast_canonical_normalize"))
            .toList();

    private BestFirstSearchStrategy strategy;
    private SearchProblem referenceFixedWork;
    private SearchProblem preparedFixedWork;
    private SearchProblem referenceTargeted;
    private SearchProblem preparedTargeted;

    @Setup
    public void setup() {
        strategy = new BestFirstSearchStrategy();
        referenceFixedWork = problem(
            FIXED_WORK_INPUT,
            new AstRewriteTransformationEngine(),
            FIXED_WORK_HEURISTIC);
        preparedFixedWork = problem(
            FIXED_WORK_INPUT,
            new PreparedAstRewriteTransformationEngine(),
            FIXED_WORK_HEURISTIC);
        referenceTargeted = targetedProblem(
            new AstRewriteTransformationEngine(TARGETED_RULES))
            .withTarget(SearchTarget.syntaxExact(TARGETED_OUTPUT));
        preparedTargeted = targetedProblem(
            new PreparedAstRewriteTransformationEngine(TARGETED_RULES))
            .withTarget(SearchTarget.syntaxExact(TARGETED_OUTPUT));
    }

    @Benchmark
    public long referenceFixedWorkSearch(WorkCounters counters) {
        return execute(referenceFixedWork, counters);
    }

    @Benchmark
    public long preparedFixedWorkSearch(WorkCounters counters) {
        return execute(preparedFixedWork, counters);
    }

    @Benchmark
    public long referenceTargetedSearch(WorkCounters counters) {
        return execute(referenceTargeted, counters);
    }

    @Benchmark
    public long preparedTargetedSearch(WorkCounters counters) {
        return execute(preparedTargeted, counters);
    }

    private long execute(SearchProblem problem, WorkCounters counters) {
        GoalSearchResult result = strategy.searchWithDiagnostics(problem);
        GoalMetrics metrics = result.metrics();
        counters.searches++;
        counters.exploredStates += metrics.exploredStates();
        counters.expandedStates += metrics.expandedStates();
        counters.generatedTransformations += metrics.generatedTransformations();
        counters.enqueuedStates += metrics.enqueuedStates();
        counters.reachedTargets += result.reached() ? 1L : 0L;
        return checksum(result);
    }

    static SearchProblem problem(
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

    private static long checksum(GoalSearchResult result) {
        GoalMetrics metrics = result.metrics();
        long value = result.status().ordinal();
        value = 31L * value + metrics.exploredStates();
        value = 31L * value + metrics.generatedTransformations();
        value = 31L * value + metrics.enqueuedStates();
        value = 31L * value + result.bestDistance();
        if (result.bestState() != null) {
            value = 31L * value + result.bestState().canonicalHash().hashCode();
            value = 31L * value + result.bestState().depth();
        }
        return value;
    }

    /**
     * The targeted control measures explicit rewrite sequencing. Strong
     * algebraic value canonicalization would intentionally collapse every
     * equivalent intermediate and reduce the control to a one-edge check.
     */
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

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class WorkCounters {
        public long searches;
        public long exploredStates;
        public long expandedStates;
        public long generatedTransformations;
        public long enqueuedStates;
        public long reachedTargets;

        @Setup(Level.Iteration)
        public void reset() {
            searches = 0L;
            exploredStates = 0L;
            expandedStates = 0L;
            generatedTransformations = 0L;
            enqueuedStates = 0L;
            reachedTargets = 0L;
        }
    }
}
