package de.regelsuche.mining;

import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.example.AlgebraicExampleGenerator;
import de.regelsuche.graph.ExpressionGraphStore;
import de.regelsuche.graph.GraphEdge;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RuleDiscoveryService {
    private final AlgebraicExampleGenerator exampleGenerator;
    private final TransformationEngine transformationEngine;
    private final EquivalenceService equivalenceService;
    private final ExpressionScorer expressionScorer;
    private final ExpressionGraphStore graphStore;
    private final RuleCandidateMiner miner;
    private final RuleCandidateListener listener;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Set<String> announcedCandidateHashes = new HashSet<>();

    public RuleDiscoveryService(
        AlgebraicExampleGenerator exampleGenerator,
        TransformationEngine transformationEngine,
        EquivalenceService equivalenceService,
        ExpressionScorer expressionScorer,
        ExpressionGraphStore graphStore,
        RuleCandidateMiner miner,
        RuleCandidateListener listener
    ) {
        this.exampleGenerator = exampleGenerator;
        this.transformationEngine = transformationEngine;
        this.equivalenceService = equivalenceService;
        this.expressionScorer = expressionScorer;
        this.graphStore = graphStore;
        this.miner = miner;
        this.listener = listener;
    }

    public CompletableFuture<List<RuleCandidate>> discoverAsync(int min, int max) {
        return CompletableFuture.supplyAsync(() -> discover(min, max), executorService);
    }

    public List<RuleCandidate> discover(int min, int max) {
        List<SuccessfulTransformationPath> paths = new ArrayList<>();
        for (String expression : exampleGenerator.generateSmallIntegerExamples(min, max)) {
            ExpressionScore before = expressionScorer.score(expression);
            graphStore.saveNode(expression, before.weightedTotal());
            for (Transformation transformation : transformationEngine.transform(expression)) {
                String target = transformation.transformedExpression();
                ExpressionScore after = expressionScorer.score(target);
                int improvement = before.improvementTo(after);
                boolean equivalent = equivalenceService.areEquivalent(expression, target);

                graphStore.saveNode(target, after.weightedTotal());
                graphStore.saveEdge(new GraphEdge(expression, target, transformation.rule(), 1, improvement));

                if (equivalent && improvement > 0) {
                    paths.add(new SuccessfulTransformationPath(
                        expression,
                        target,
                        List.of(expression, target),
                        List.of(transformation.rule()),
                        before,
                        after,
                        equivalenceService.evidence(expression, target),
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
