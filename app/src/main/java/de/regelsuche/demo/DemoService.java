package de.regelsuche.demo;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.graph.ExpressionGraphStore;
import de.regelsuche.graph.GraphEdge;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.TransformationEngine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a single curated demo: explores the search space for a fixed expression
 * using a deterministic atomic-rule engine and a chosen {@link de.regelsuche.search.SearchProfile},
 * persisting nodes, edges and discovered transformations into the supplied
 * {@link ExpressionGraphStore}. This is what powers the {@code /api/demo/*}
 * endpoints exposed by the web workbench.
 *
 * <p>The service intentionally reuses the same logic as
 * {@link de.regelsuche.mining.RuleDiscoveryService} for a single root so all
 * downstream services ({@code SearchGraphAssembler}, {@code MacroRuleMiner},
 * report renderers) work without further adaptation.</p>
 */
public final class DemoService {

    private final ExpressionGraphStore graphStore;
    private final TransformationEngine engine;
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    public DemoService(ExpressionGraphStore graphStore) {
        this(graphStore, new AstRewriteTransformationEngine());
    }

    public DemoService(ExpressionGraphStore graphStore, TransformationEngine engine) {
        this.graphStore = graphStore;
        this.engine = engine;
    }

    /**
     * Runs the demo identified by {@code id} and returns a structured result
     * suitable for JSON serialization in the HTTP layer.
     */
    public DemoRunResult run(String id) {
        DemoCatalog.Demo demo = DemoCatalog.byId(id);
        if (demo == null) {
            throw new IllegalArgumentException("Unknown demo: " + id);
        }
        return run(demo);
    }

    public DemoRunResult run(DemoCatalog.Demo demo) {
        long started = System.nanoTime();
        String root = canonicalizer.canonicalize(demo.expression());
        ExpressionScore before = scorer.score(root);
        graphStore.saveNode(root, before.weightedTotal());

        SearchProblem problem = new SearchProblem(
            root, engine, scorer, canonicalizer, demo.profile().heuristic());
        List<SearchState> states = demo.profile().newStrategy().search(problem);

        int nodesSaved = 1;
        int edgesSaved = 0;
        int paths = 0;
        DiscoveredTransformation best = null;
        for (SearchState state : states) {
            graphStore.saveNode(state.expression(), state.score().weightedTotal());
            nodesSaved++;
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
                edgesSaved++;
            }
            if (state.depth() == 0) {
                continue;
            }
            DiscoveredTransformation transformation =
                toDiscovered(stablePathId(root, state), root, state, before);
            graphStore.saveDiscoveredTransformation(transformation);
            paths++;
            if (best == null || transformation.totalImprovement() > best.totalImprovement()) {
                best = transformation;
            }
        }

        long elapsedNanos = System.nanoTime() - started;
        return new DemoRunResult(
            demo,
            root,
            nodesSaved,
            edgesSaved,
            paths,
            best,
            elapsedNanos / 1_000_000L
        );
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
        return "demo-" + Long.toHexString(Integer.toUnsignedLong(builder.toString().hashCode()));
    }

    private DiscoveredTransformation toDiscovered(
        String pathId, String root, SearchState state, ExpressionScore before
    ) {
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
            String beforeExpr = expressionPath.get(i);
            String after = expressionPath.get(i + 1);
            int scoreBefore = scorer.score(beforeExpr).weightedTotal();
            int scoreAfter = scorer.score(after).weightedTotal();
            RewriteKind kind = i < ruleKinds.size() && ruleKinds.get(i) != null
                ? ruleKinds.get(i)
                : RewriteKind.NORMALIZE;
            boolean equivalencePreserving = i >= equivalenceFlags.size() || equivalenceFlags.get(i);
            steps.add(new TransformationStep(
                i,
                beforeExpr,
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

    /**
     * Outcome of a single demo run – used by the HTTP layer to build the
     * response bundle (graph metrics, best path, links to existing endpoints).
     */
    public record DemoRunResult(
        DemoCatalog.Demo demo,
        String rootExpression,
        int nodesSaved,
        int edgesSaved,
        int pathsDiscovered,
        DiscoveredTransformation bestPath,
        long elapsedMillis
    ) {
    }
}
