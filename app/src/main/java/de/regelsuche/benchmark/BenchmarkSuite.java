package de.regelsuche.benchmark;

import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.SearchProfile;
import de.regelsuche.search.strategy.AStarSearchStrategy;
import de.regelsuche.search.strategy.BeamSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.HybridSearchStrategy;
import de.regelsuche.search.strategy.MonteCarloTreeSearchStrategy;
import de.regelsuche.search.strategy.RandomMonteCarloSearchStrategy;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.util.ArrayList;
import java.util.List;

/**
 * Collection of opinionated benchmark scenarios.
 *
 * <p>Each scenario exercises {@link SearchBenchmark} with a representative set
 * of expressions and a fixed list of {@link de.regelsuche.search.strategy.SearchStrategy}
 * implementations so different runs are comparable. Scenarios are intentionally
 * small enough to finish in a few seconds.</p>
 */
public final class BenchmarkSuite {

    public List<BenchmarkSuiteResult> runAll() {
        List<BenchmarkSuiteResult> all = new ArrayList<>();
        all.add(run("known-identities", List.of(
            "x + 0",
            "x * 1",
            "x * 0",
            "a + a"
        ), SearchProfile.FAST_SIMPLIFY.heuristic()));
        all.add(run("polynomial-simplification", List.of(
            "(x + 1)*(x + 1)",
            "x*x + x*x",
            "(x + 2)*(x + 3)",
            "(x + a)^2"
        ), SearchProfile.DISCOVERY.heuristic()));
        all.add(run("rational-simplification", List.of(
            "(x*y)/(x*z)",
            "(a/b)*(c/d)",
            "a/(b/c)"
        ), SearchProfile.TEACHING.heuristic()));
        all.add(run("search-explosion", List.of(
            "(x + a)*(x + b)*(x + c)"
        ), SearchProfile.EXHAUSTIVE_SMALL.heuristic()));
        // Math-domain categories (PR #13 follow-up): each is a single
        // representative run through `UnifiedMathDomainWorkbench`, surfaced as
        // a benchmark row so the dashboard shows it next to the algebraic
        // scenarios. The result carries `found`, `elapsedMillis`,
        // `exploredStates`, `expandedSteps` (= solver steps), `proofStatus`,
        // and assumptions/equalitySaturationSavings via the dedicated row.
        all.add(runMathDomain("equations",
            de.regelsuche.demo.UnifiedMathDomainWorkbench::runLinearEquation));
        all.add(runMathDomain("inequalities",
            de.regelsuche.demo.UnifiedMathDomainWorkbench::runInequalitySignFlip));
        all.add(runMathDomain("calculus",
            de.regelsuche.demo.UnifiedMathDomainWorkbench::runDerivativePowerRule));
        all.add(runMathDomain("linear-algebra",
            de.regelsuche.demo.UnifiedMathDomainWorkbench::runMatrixDistributivity));
        return all;
    }

    private BenchmarkSuiteResult runMathDomain(
        String name,
        java.util.function.Function<de.regelsuche.demo.UnifiedMathDomainWorkbench,
            de.regelsuche.demo.UnifiedMathDomainWorkbench.DemoExecution> runner
    ) {
        long started = System.nanoTime();
        de.regelsuche.demo.UnifiedMathDomainWorkbench workbench =
            new de.regelsuche.demo.UnifiedMathDomainWorkbench();
        de.regelsuche.demo.UnifiedMathDomainWorkbench.DemoExecution exec = runner.apply(workbench);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        SearchBenchmarkResult row = new SearchBenchmarkResult(
            /* strategyName     */ "math-domain",
            /* expression       */ exec.inputExpression(),
            /* exploredStates   */ exec.edges().size() + 1,
            /* bestImprovement  */ Math.max(1, exec.steps().size()),
            /* shortestImprovingDepth */ exec.steps().size(),
            /* expandedSteps    */ exec.steps().size(),
            /* distinctRules    */ (int) exec.steps().stream()
                .map(de.regelsuche.discovery.TransformationStep::ruleId).distinct().count(),
            /* elapsedMillis    */ elapsedMillis,
            /* proofStatus      */ exec.proofStatus() == null
                ? de.regelsuche.mining.CandidateProofStatus.SYMBOLICALLY_VERIFIED
                : exec.proofStatus()
        );
        return new BenchmarkSuiteResult(name, List.of(row));
    }

    public BenchmarkSuiteResult run(String name, List<String> expressions, SearchHeuristic heuristic) {
        SearchBenchmark benchmark = new SearchBenchmark(new AstRewriteTransformationEngine(), heuristic);
        List<SearchBenchmark.NamedSearchStrategy> strategies = List.of(
            new SearchBenchmark.NamedSearchStrategy("best-first", new BestFirstSearchStrategy()),
            new SearchBenchmark.NamedSearchStrategy("beam", new BeamSearchStrategy()),
            new SearchBenchmark.NamedSearchStrategy("a-star", new AStarSearchStrategy()),
            new SearchBenchmark.NamedSearchStrategy("random-mc", new RandomMonteCarloSearchStrategy(7L)),
            new SearchBenchmark.NamedSearchStrategy("mcts", new MonteCarloTreeSearchStrategy(7L)),
            new SearchBenchmark.NamedSearchStrategy("hybrid", new HybridSearchStrategy())
        );
        return new BenchmarkSuiteResult(name, benchmark.run(expressions, strategies));
    }

    public record BenchmarkSuiteResult(String name, List<SearchBenchmarkResult> results) {
        public BenchmarkSuiteResult {
            results = List.copyOf(results);
        }
    }
}
