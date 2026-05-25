package de.regelsuche.mining;

import de.regelsuche.validation.CandidateProofStatus;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
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
import de.regelsuche.transform.RewriteKind;
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
    private final DiscoverySettings discoverySettings;
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
            new BestFirstSearchStrategy(),
            DiscoverySettings.defaults()
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
        this(
            exampleGenerator,
            transformationEngine,
            equivalenceService,
            expressionScorer,
            graphStore,
            miner,
            listener,
            searchStrategy,
            DiscoverySettings.defaults()
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
        SearchStrategy searchStrategy,
        DiscoverySettings discoverySettings
    ) {
        this.exampleGenerator = exampleGenerator;
        this.transformationEngine = transformationEngine;
        this.equivalenceService = equivalenceService;
        this.expressionScorer = expressionScorer;
        this.graphStore = graphStore;
        this.miner = miner;
        this.listener = listener;
        this.searchStrategy = searchStrategy;
        this.discoverySettings = discoverySettings;
    }

    public CompletableFuture<List<RuleCandidate>> discoverAsync(int min, int max) {
        return CompletableFuture.supplyAsync(() -> discover(min, max), executorService);
    }

    public List<RuleCandidate> discover(int min, int max) {
        List<SuccessfulTransformationPath> paths = new ArrayList<>();
        SearchHeuristic discoveryHeuristic = discoverySettings.includeNonImprovingEquivalentPaths()
            ? new SearchHeuristic(7, 1500, 1, 10, 200, 200)
            : new SearchHeuristic(7, 400, 1, 5, 120, 25);
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
                if (state.depth() > discoverySettings.maxPathLengthForCandidateMining()) {
                    continue;
                }
                int totalImprovement = before.improvementTo(state.score());
                boolean equivalent = equivalenceService.areEquivalent(root, state.expression());
                if (!equivalent) {
                    continue;
                }
                boolean improving = totalImprovement > 0;
                if (!improving && !discoverySettings.includeNonImprovingEquivalentPaths()) {
                    continue;
                }
                String pathId = stablePathId(root, state);
                paths.add(new SuccessfulTransformationPath(
                    pathId,
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
                graphStore.saveDiscoveredTransformation(toDiscovered(pathId, root, state, before));
            }
        }
        List<RuleCandidate> candidates = miner.mine(paths, discoverySettings);
        for (RuleCandidate candidate : candidates) {
            graphStore.saveRuleCandidate(candidate);
            if (announcedCandidateHashes.add(candidate.canonicalHash())) {
                listener.onRuleCandidateDiscovered(new RuleCandidateDiscoveredEvent(candidate, Instant.now()));
            }
        }
        return candidates;
    }

    private String stablePathId(String root, SearchState state) {
        StringBuilder builder = new StringBuilder();
        builder.append(root).append('\u0001');
        builder.append(state.canonicalHash()).append('\u0001');
        for (String step : state.path()) {
            builder.append(step).append('\u0002');
        }
        builder.append('\u0001');
        for (String rule : state.appliedRuleIds()) {
            builder.append(rule).append('\u0003');
        }
        return "path-" + Long.toHexString(Integer.toUnsignedLong(builder.toString().hashCode()));
    }

    private DiscoveredTransformation toDiscovered(String pathId, String root, SearchState state, ExpressionScore before) {
        List<TransformationStep> steps = buildSteps(state);
        return new DiscoveredTransformation(
            pathId,
            root,
            state.expression(),
            steps,
            before,
            state.score(),
            before.improvementTo(state.score()),
            CandidateProofStatus.OBSERVED,
            Instant.now(),
            state.canonicalHash()
        );
    }

    private List<TransformationStep> buildSteps(SearchState state) {
        List<String> expressionPath = state.path();
        List<String> ruleIds = state.appliedRuleIds();
        List<RewriteKind> ruleKinds = state.appliedRuleKinds();
        List<Boolean> equivalenceFlags = state.equivalencePreservingFlags();
        if (expressionPath.size() < 2 || ruleIds.isEmpty()) {
            return List.of();
        }
        List<TransformationStep> steps = new ArrayList<>();
        int stepCount = Math.min(ruleIds.size(), expressionPath.size() - 1);
        for (int i = 0; i < stepCount; i++) {
            String before = expressionPath.get(i);
            String after = expressionPath.get(i + 1);
            int scoreBefore = expressionScorer.score(before).weightedTotal();
            int scoreAfter = expressionScorer.score(after).weightedTotal();
            RewriteKind kind = i < ruleKinds.size() && ruleKinds.get(i) != null
                ? ruleKinds.get(i)
                : RewriteKind.NORMALIZE;
            boolean equivalencePreserving = i >= equivalenceFlags.size() || equivalenceFlags.get(i);
            steps.add(new TransformationStep(
                i,
                before,
                after,
                ruleIds.get(i),
                kind,
                scoreBefore,
                scoreAfter,
                equivalencePreserving,
                ruleIds.get(i)
            ));
        }
        return steps;
    }

    public void shutdown() {
        executorService.shutdown();
    }
}
