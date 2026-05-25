package de.regelsuche.search;

import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.graph.ExpressionGraphStore;
import de.regelsuche.graph.GraphEdge;
import de.regelsuche.graph.GraphSnapshot;
import de.regelsuche.input.InputRequest;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.notify.SimplificationNotifier;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.parse.ParsedInput;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.scoring.cost.TransformationGoal;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.search.strategy.SearchStrategy;
import de.regelsuche.transform.TransformationEngine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TransformationSearchService {
    private final ExpressionParser parser;
    private final TransformationEngine engine;
    private final ExpressionGraphStore graphStore;
    private final SearchHeuristic heuristic;
    private final SimplificationNotifier notifier;
    private final SearchStrategy searchStrategy;
    private final ExpressionScorer scorer;
    private final ExpressionCanonicalizer canonicalizer;
    private final ExecutorService executorService;
    private final Set<String> globallyVisited = ConcurrentHashMap.newKeySet();
    private final List<SimplificationSuccess> successes = Collections.synchronizedList(new ArrayList<>());

    public TransformationSearchService(
        TransformationEngine engine,
        ExpressionGraphStore graphStore,
        SearchHeuristic heuristic,
        SimplificationNotifier notifier
    ) {
        this(engine, graphStore, heuristic, notifier, new BestFirstSearchStrategy());
    }

    public TransformationSearchService(
        TransformationEngine engine,
        ExpressionGraphStore graphStore,
        SearchHeuristic heuristic,
        SimplificationNotifier notifier,
        SearchStrategy searchStrategy
    ) {
        this.parser = new ExpressionParser();
        this.engine = engine;
        this.graphStore = graphStore;
        this.heuristic = heuristic;
        this.notifier = notifier;
        this.searchStrategy = searchStrategy;
        this.scorer = new ExpressionScorer();
        this.canonicalizer = new ExpressionCanonicalizer();
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public CompletableFuture<Void> submit(InputRequest input) {
        return submit(input, null);
    }

    public CompletableFuture<Void> submit(InputRequest input, TransformationGoal goal) {
        ParsedInput parsed = parser.parse(input);
        List<String> roots = collectRoots(parsed);
        List<CompletableFuture<Void>> jobs = new ArrayList<>();
        for (String root : roots) {
            jobs.add(CompletableFuture.runAsync(() -> explore(root, goal), executorService));
        }
        return CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new));
    }

    public Optional<SimplificationSuccess> getBestSolution() {
        synchronized (successes) {
            return successes.stream().max((a, b) -> Integer.compare(a.improvement(), b.improvement()));
        }
    }

    public List<SimplificationSuccess> getSuccesses() {
        synchronized (successes) {
            return List.copyOf(successes);
        }
    }

    public GraphSnapshot getGraphSnapshot() {
        return graphStore.snapshot();
    }

    public void shutdown() {
        executorService.shutdown();
    }

    private List<String> collectRoots(ParsedInput parsed) {
        List<String> roots = new ArrayList<>();
        for (Expr expr : parsed.terms()) {
            roots.add(ExpressionFormatter.format(expr));
        }
        for (Equation equation : parsed.equations()) {
            roots.add(ExpressionFormatter.format(equation));
        }
        return roots;
    }

    private void explore(String root, TransformationGoal goal) {
        SearchProblem problem = new SearchProblem(root, engine, scorer, canonicalizer, heuristic)
            .withGoal(goal);
        for (SearchState state : searchStrategy.search(problem)) {
            boolean alreadyVisited = !globallyVisited.add(state.canonicalHash() + ":" + state.appliedRuleApplications());
            if (alreadyVisited && state.improvement() <= 0) {
                continue;
            }
            graphStore.saveNode(state.expression(), state.score().weightedTotal());
            if (state.parentExpression() != null && state.appliedRuleId() != null) {
                graphStore.saveEdge(new GraphEdge(
                    state.parentExpression(),
                    state.expression(),
                    state.appliedRuleId(),
                    state.depth(),
                    state.improvement(),
                    root + "#" + state.depth(),
                    state.canonicalHash(),
                    scorer.score(state.parentExpression()).weightedTotal(),
                    state.score().weightedTotal(),
                    state.appliedRuleKind(),
                    state.mayIncreaseComplexity(),
                    state.estimatedCostDelta(),
                    state.equivalencePreservingByConstruction(),
                    CandidateProofStatus.OBSERVED
                ));
            }
            if (state.improvement() > 0) {
                SimplificationSuccess success = new SimplificationSuccess(
                    root,
                    state.expression(),
                    state.appliedRuleId(),
                    state.depth(),
                    state.improvement(),
                    Instant.now()
                );
                successes.add(success);
                int oldComplexity = scorer.score(state.parentExpression()).weightedTotal();
                int newComplexity = state.score().weightedTotal();
                if (heuristic.shouldNotify(oldComplexity, newComplexity)) {
                    notifier.onSignificantSimplification(state.parentExpression(), state.expression());
                }
            }
        }
    }

    static int complexity(String expression) {
        return new ExpressionScorer().score(expression).weightedTotal();
    }
}
