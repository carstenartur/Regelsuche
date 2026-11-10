package de.regelsuche.search.strategy;

import de.regelsuche.ast.Expr;
import de.regelsuche.egraph.EClassId;
import de.regelsuche.egraph.EGraph;
import de.regelsuche.egraph.ENode;
import de.regelsuche.egraph.EqualitySaturation;
import de.regelsuche.egraph.SaturationStats;
import de.regelsuche.egraph.SaturationDiagnostics;
import de.regelsuche.egraph.SaturationProvenance;
import de.regelsuche.scoring.cost.StructuralCostModel;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Equality-saturation search strategy.
 *
 * <p>This strategy delegates exploration to {@link EqualitySaturation}
 * rather than to the path-based BFS / Best-First / A-star / MCTS
 * strategies. It adds the root expression to a fresh {@link EGraph},
 * saturates it with the {@link RewriteRule}s exposed by the {@link
 * AstRewriteTransformationEngine} (or with the default rule set if the
 * problem's engine is not the AST engine), extracts the cheapest
 * representative, and returns two {@link SearchState}s: the root and
 * the extracted best form.</p>
 *
 * <p>The applied-rule id of the extracted state is set to {@value
 * #SATURATION_RULE_ID} so the UI / report layer can detect that equality
 * saturation was used (instead of an ordered list of rewrites). The
 * detailed per-iteration {@link SaturationStats} of the most recent run
 * are exposed via {@link #lastStats()} so callers can surface them.</p>
 */
public final class EqualitySaturationStrategy implements SearchStrategy {

    /**
     * Sentinel rule id used on the extracted {@link SearchState} so the
     * UI / report layer can detect that equality saturation produced the
     * result rather than an ordered chain of rewrites. This is an observational
     * provenance marker, never an executable or construction-proven rule step.
     */
    public static final String SATURATION_RULE_ID = "equality-saturation";

    private final EqualitySaturation.Config config;
    private final ExpressionParser parser = new ExpressionParser();
    private SaturationStats lastStats;
    private SaturationDiagnostics lastDiagnostics = SaturationDiagnostics.empty();
    private SaturationProvenance lastProvenance = SaturationProvenance.empty();
    private ExtractionSelection lastExtractionSelection;

    /** Explicit objective dispatch; fallback still uses the requested cost model. */
    public record ExtractionSelection(String requestedObjective, String effectiveObjective,
            String strategy, boolean fallback, String reason) {}

    public SaturationDiagnostics lastDiagnostics() { return lastDiagnostics; }
    public SaturationProvenance lastProvenance() { return lastProvenance; }
    public ExtractionSelection lastExtractionSelection() { return lastExtractionSelection; }


    public EqualitySaturationStrategy() {
        this(EqualitySaturation.Config.defaults());
    }

    public EqualitySaturationStrategy(EqualitySaturation.Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Statistics from the most recent {@link #search} invocation, or
     * {@code null} if the strategy has not been run yet.
     */
    public SaturationStats lastStats() {
        return lastStats;
    }

    @Override
    public List<SearchState> search(SearchProblem problem) {
        lastStats = null;
        lastDiagnostics = SaturationDiagnostics.empty();
        lastProvenance = SaturationProvenance.empty();
        String objective = problem.costModel() == null ? "operator-count" : problem.costModel().id();
        // Identity check matters: a custom model can reuse a built-in ID with different semantics.
        if (problem.costModel() != null && problem.costModel() != StructuralCostModel.OPERATOR_COUNT) {
            lastExtractionSelection = new ExtractionSelection(objective, objective, "best-first", true,
                "No exact e-graph extractor for this cost model; best-first search uses the requested objective.");
            return new BestFirstSearchStrategy().search(problem);
        }
        lastExtractionSelection = new ExtractionSelection(objective, "operator-count", "equality-saturation", false,
            "Exact additive operator-count extraction.");
        String root = problem.rootExpression().trim().replaceAll("\\s+", " ");
        Expr rootAst;
        try {
            rootAst = parser.parse(new InputRequest(InputType.TERM, root)).terms().getFirst();
        } catch (RuntimeException ex) {
            // Unparseable input: behave like the other strategies and
            // return just the root state with the input as-is.
            ExpressionScore rootScore = problem.scorer().score(root);
            return List.of(rootState(root, rootScore, problem));
        }

        EGraph eGraph = new EGraph();
        EClassId rootId = eGraph.addExpression(rootAst);

        List<RewriteRule> rules = resolveRules(problem);
        EqualitySaturation saturation = new EqualitySaturation(rules, config);
        EqualitySaturation.Result result = saturation.saturate(
            eGraph,
            rootId,
            EqualitySaturationStrategy::nodeCost
        );
        lastStats = result.stats();
        lastDiagnostics = result.diagnostics();
        lastProvenance = result.provenance();

        String extracted = ExpressionFormatter.format(result.expression());
        ExpressionScore rootScore = problem.scorer().score(root);
        SearchState rootState = rootState(root, rootScore, problem);

        if (extracted.equals(root)) {
            // No useful rewrite — still return the root so callers see a
            // result. The lastStats() expose how much work was done.
            return List.of(rootState);
        }

        ExpressionScore extractedScore = problem.scorer().score(extracted);
        int improvement = rootScore.weightedTotal() - extractedScore.weightedTotal();
        SearchState extractedState = new SearchState(
            extracted,
            1,
            extractedScore,
            List.of(root, extracted),
            List.of(SATURATION_RULE_ID),
            Set.of(SATURATION_RULE_ID + ":" + extracted),
            0,
            problem.canonicalizer().stableHash(extracted),
            root,
            SATURATION_RULE_ID,
            RewriteKind.NORMALIZE,
            false,
            -Math.max(0, improvement),
            false,
            improvement,
            List.of(RewriteKind.NORMALIZE),
            List.of(Boolean.FALSE)
        );
        return List.of(rootState, extractedState);
    }

    private SearchState rootState(String root, ExpressionScore rootScore, SearchProblem problem) {
        return new SearchState(
            root,
            0,
            rootScore,
            List.of(root),
            List.of(),
            Set.of(),
            0,
            problem.canonicalizer().stableHash(root),
            null,
            null,
            RewriteKind.NORMALIZE,
            false,
            0,
            true,
            0
        );
    }

    private List<RewriteRule> resolveRules(SearchProblem problem) {
        if (problem.engine() instanceof AstRewriteTransformationEngine astEngine) {
            return astEngine.rules();
        }
        return AstRewriteTransformationEngine.defaultRules();
    }

    /** Additive operator-count objective; other objectives use the explicit dispatch above. */
    static int nodeCost(ENode node) {
        return node.symbol().startsWith("op:") || node.symbol().startsWith("fn:") ? 1 : 0;
    }
}
