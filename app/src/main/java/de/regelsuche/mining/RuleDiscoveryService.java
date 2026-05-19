package de.regelsuche.mining;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.example.AlgebraicExampleGenerator;
import de.regelsuche.graph.ExpressionGraphStore;
import de.regelsuche.graph.GraphEdge;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.search.strategy.SearchStrategy;
import de.regelsuche.transform.TransformationEngine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RuleDiscoveryService {
    private final AlgebraicExampleGenerator exampleGenerator;
    private final TransformationEngine transformationEngine;
    private final EquivalenceService equivalenceService;
    private final ExpressionScorer expressionScorer;
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final ExpressionGraphStore graphStore;
    private final RuleCandidateMiner miner;
    private final RuleCandidateListener listener;
    private final SearchStrategy searchStrategy;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Set<String> announcedCandidateHashes = ConcurrentHashMap.newKeySet();

    public RuleDiscoveryService(
        AlgebraicExampleGenerator exampleGenerator,
        TransformationEngine transformationEngine,
        EquivalenceService equivalenceService,
        ExpressionScorer expressionScorer,
        ExpressionGraphStore graphStore,
        RuleCandidateMiner miner,
        RuleCandidateListener listener
    ) {
        this(
            exampleGenerator,
            transformationEngine,
            equivalenceService,
            expressionScorer,
            graphStore,
            miner,
            listener,
            new BestFirstSearchStrategy()
        );
    }

    public RuleDiscoveryService(
        AlgebraicExampleGenerator exampleGenerator,
        TransformationEngine transformationEngine,
        EquivalenceService equivalenceService,
        ExpressionScorer expressionScorer,
        ExpressionGraphStore graphStore,
        RuleCandidateMiner miner,
        RuleCandidateListener listener,
        SearchStrategy searchStrategy
    ) {
        this.exampleGenerator = exampleGenerator;
        this.transformationEngine = transformationEngine;
        this.equivalenceService = equivalenceService;
        this.expressionScorer = expressionScorer;
        this.graphStore = graphStore;
        this.miner = miner;
        this.listener = listener;
        this.searchStrategy = searchStrategy;
    }

    public CompletableFuture<List<RuleCandidate>> discoverAsync(int min, int max) {
        return CompletableFuture.supplyAsync(() -> discover(min, max), executorService);
    }

    public List<RuleCandidate> discover(int min, int max) {
        List<SuccessfulTransformationPath> paths = new ArrayList<>();
        SearchHeuristic discoveryHeuristic = new SearchHeuristic(7, 400, 1, 5, 120, 25);
        for (String expression : exampleGenerator.generateSmallIntegerExamples(min, max)) {
            String root = canonicalizer.canonicalize(expression);
            ExpressionScore before = expressionScorer.score(root);
            graphStore.saveNode(root, before.weightedTotal());
            SearchProblem problem = new SearchProblem(root, transformationEngine, expressionScorer, canonicalizer, discoveryHeuristic);
            for (SearchState state : searchStrategy.search(problem)) {
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
                        expressionScorer.score(state.parentExpression()).weightedTotal(),
                        state.score().weightedTotal(),
                        state.appliedRuleKind(),
                        state.mayIncreaseComplexity(),
                        state.estimatedCostDelta(),
                        state.equivalencePreservingByConstruction(),
                        CandidateProofStatus.OBSERVED
                    ));
                }
                if (state.depth() == 0) {
                    continue;
                }
                int totalImprovement = before.improvementTo(state.score());
                boolean equivalent = equivalenceService.areEquivalent(root, state.expression());
                if (equivalent && totalImprovement > 0) {
                    paths.add(new SuccessfulTransformationPath(
                        root,
                        state.expression(),
                        state.path(),
                        state.appliedRuleIds(),
                        before,
                        state.score(),
                        equivalent,
                        equivalenceService.evidence(root, state.expression()),
                        Map.of("variable", "x")
                    ));
                }
            }
        }
        List<RuleCandidate> candidates = miner.mine(paths);
        for (RuleCandidate candidate : candidates) {
            if (announcedCandidateHashes.add(candidate.canonicalHash())) {
                listener.onRuleCandidateDiscovered(new RuleCandidateDiscoveredEvent(candidate, Instant.now()));
            }
        }
        return candidates;
    }

    public void shutdown() {
        executorService.shutdown();
    }
}
