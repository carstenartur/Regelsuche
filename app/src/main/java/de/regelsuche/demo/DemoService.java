package de.regelsuche.demo;

import de.regelsuche.assumption.Assumption;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
    private final de.regelsuche.search.memory.SearchMemory searchMemory;

    public DemoService(ExpressionGraphStore graphStore) {
        this(graphStore, new AstRewriteTransformationEngine(DemoRuleSet.rules()), null);
    }

    public DemoService(ExpressionGraphStore graphStore, TransformationEngine engine) {
        this(graphStore, engine, null);
    }

    public DemoService(
        ExpressionGraphStore graphStore,
        TransformationEngine engine,
        de.regelsuche.search.memory.SearchMemory searchMemory
    ) {
        this.graphStore = graphStore;
        this.engine = engine;
        this.searchMemory = searchMemory;
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
            root, engine, scorer, canonicalizer, demo.profile().heuristic(),
            demo.profile().usesTranspositionTable() ? searchMemory : null);
        List<SearchState> states = demo.profile().newStrategy().search(problem);

        // Target expression in canonical form so we can compare on equal terms
        // with whatever the search emits.
        String canonicalTarget = demo.expectedResultExpression() == null
            ? null
            : canonicalizer.canonicalize(demo.expectedResultExpression());

        int nodesSaved = 1;
        int edgesSaved = 0;
        int paths = 0;
        DiscoveredTransformation best = null;
        DiscoveredTransformation targetPath = null;
        Set<String> appliedRuleIdsOnTargetPath = new LinkedHashSet<>();
        Set<String> allAppliedRuleIds = new LinkedHashSet<>();
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
                allAppliedRuleIds.add(state.appliedRuleId());
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
            // Prefer the SHORTEST path that lands on the canonical target.
            if (canonicalTarget != null && transformation.improvedExpression().equals(canonicalTarget)) {
                if (targetPath == null || transformation.steps().size() < targetPath.steps().size()) {
                    targetPath = transformation;
                    appliedRuleIdsOnTargetPath.clear();
                    appliedRuleIdsOnTargetPath.addAll(state.appliedRuleIds());
                }
            }
        }

        boolean targetReached = targetPath != null;
        DiscoveredTransformation selected = targetReached ? targetPath : best;
        List<String> assumptions = collectAssumptions(demo, appliedRuleIdsOnTargetPath);

        long elapsedNanos = System.nanoTime() - started;
        return new DemoRunResult(
            demo,
            root,
            canonicalTarget,
            nodesSaved,
            edgesSaved,
            paths,
            best,
            targetPath,
            selected,
            targetReached,
            new ArrayList<>(allAppliedRuleIds),
            assumptions,
            elapsedNanos / 1_000_000L
        );
    }

    /**
     * Returns the set of assumptions that must hold for the target path to be
     * valid. Today this only covers the rational demo: when
     * {@code rational_cancel_common_factor} fires, the cancelled factor must
     * be non-zero (here: {@code x}). The set is intentionally small and
     * explicit; if/when more demos depend on side conditions, extend here.
     */
    private List<String> collectAssumptions(DemoCatalog.Demo demo, Set<String> targetRuleIds) {
        if (!targetRuleIds.contains("rational_cancel_common_factor")) {
            return List.of();
        }
        // For the curated rational demo `(x*y)/(x*z)` we cancel the common
        // factor `x`, so the assumption surfaced to the user is `x != 0`.
        if ("rational".equals(demo.id())) {
            return List.of(Assumption.nonZero("x").expression());
        }
        return List.of();
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
        String canonicalTargetExpression,
        int nodesSaved,
        int edgesSaved,
        int pathsDiscovered,
        DiscoveredTransformation bestPath,
        DiscoveredTransformation targetPath,
        DiscoveredTransformation selectedPath,
        boolean targetReached,
        List<String> appliedRuleIds,
        List<String> assumptions,
        long elapsedMillis
    ) {
    }
}
