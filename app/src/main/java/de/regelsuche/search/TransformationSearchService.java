package de.regelsuche.search;

import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.graph.ExpressionGraphStore;
import de.regelsuche.graph.GraphEdge;
import de.regelsuche.graph.GraphSnapshot;
import de.regelsuche.input.InputRequest;
import de.regelsuche.notify.SimplificationNotifier;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.parse.ParsedInput;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
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
    private final ExecutorService executorService;
    private final Set<String> globallyVisited = ConcurrentHashMap.newKeySet();
    private final List<SimplificationSuccess> successes = Collections.synchronizedList(new ArrayList<>());

    public TransformationSearchService(
        TransformationEngine engine,
        ExpressionGraphStore graphStore,
        SearchHeuristic heuristic,
        SimplificationNotifier notifier
    ) {
        this.parser = new ExpressionParser();
        this.engine = engine;
        this.graphStore = graphStore;
        this.heuristic = heuristic;
        this.notifier = notifier;
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public CompletableFuture<Void> submit(InputRequest input) {
        ParsedInput parsed = parser.parse(input);
        List<String> roots = collectRoots(parsed);
        List<CompletableFuture<Void>> jobs = new ArrayList<>();
        for (String root : roots) {
            jobs.add(CompletableFuture.runAsync(() -> explore(root), executorService));
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

    private void explore(String root) {
        Deque<NodeState> queue = new ArrayDeque<>();
        queue.add(new NodeState(root, 0));
        while (!queue.isEmpty()) {
            NodeState current = queue.removeFirst();
            if (!heuristic.withinLimits(current.depth(), globallyVisited.size())) {
                continue;
            }
            if (!globallyVisited.add(current.expression())) {
                continue;
            }

            int currentComplexity = complexity(current.expression());
            graphStore.saveNode(current.expression(), currentComplexity);
            if (current.depth() >= heuristic.maxDepth()) {
                continue;
            }

            for (Transformation transformation : engine.transform(current.expression())) {
                String transformed = transformation.transformedExpression();
                if (transformed.equals(current.expression()) || transformed.isBlank()) {
                    continue;
                }

                int transformedComplexity = complexity(transformed);
                int improvement = currentComplexity - transformedComplexity;

                graphStore.saveNode(transformed, transformedComplexity);
                graphStore.saveEdge(
                    new GraphEdge(current.expression(), transformed, transformation.rule(), current.depth() + 1, improvement)
                );

                if (improvement > 0) {
                    SimplificationSuccess success = new SimplificationSuccess(
                        root,
                        transformed,
                        transformation.rule(),
                        current.depth() + 1,
                        improvement,
                        Instant.now()
                    );
                    successes.add(success);
                    if (heuristic.shouldNotify(currentComplexity, transformedComplexity)) {
                        notifier.onSignificantSimplification(current.expression(), transformed);
                    }
                }

                if (heuristic.withinLimits(current.depth() + 1, globallyVisited.size())) {
                    queue.addLast(new NodeState(transformed, current.depth() + 1));
                }
            }
        }
    }

    static int complexity(String expression) {
        String compact = expression.replaceAll("\\s+", "");
        int operators = 0;
        for (char c : compact.toCharArray()) {
            if (c == '+' || c == '-' || c == '*' || c == '/' || c == '^' || c == '=') {
                operators++;
            }
        }
        return compact.length() + operators;
    }

    private record NodeState(String expression, int depth) {
    }
}
