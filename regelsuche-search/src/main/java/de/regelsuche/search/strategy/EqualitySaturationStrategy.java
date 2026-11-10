package de.regelsuche.search.strategy;

import de.regelsuche.ast.Expr;
import de.regelsuche.egraph.EClassId;
import de.regelsuche.egraph.EGraph;
import de.regelsuche.egraph.ENode;
import de.regelsuche.egraph.EqualitySaturation;
import de.regelsuche.egraph.SaturationStats;
import de.regelsuche.egraph.proof.ProofOutcome;
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
    private final EqualitySaturation.ProofOptions proofOptions;
    private ProofOutcome lastProofOutcome = ProofOutcome.notRequested();
    private final ExpressionParser parser = new ExpressionParser();
    private SaturationStats lastStats;
    private SaturationDiagnostics lastDiagnostics = SaturationDiagnostics.empty();
    private SaturationProvenance lastProvenance = SaturationProvenance.empty();
    private ExtractionSelection lastExtractionSelection;

    /** Explicit objective dispatch; fallback still uses the requested cost model. */
    public record ExtractionSelection(String requestedObjective, String effectiveObjective,
            String strategy, boolean fallback, String reason) {}

    public ProofOutcome lastProofOutcome() { return lastProofOutcome; }

    public SaturationDiagnostics lastDiagnostics() { return lastDiagnostics; }
    public SaturationProvenance lastProvenance() { return lastProvenance; }
    public ExtractionSelection lastExtractionSelection() { return lastExtractionSelection; }


    public EqualitySaturationStrategy() {
        this(EqualitySaturation.Config.defaults());
    }

    public EqualitySaturationStrategy(EqualitySaturation.Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.proofOptions = null;
    }

    /** Explicit proof-required mode; unavailable evidence preserves the original state. */
    public EqualitySaturationStrategy(EqualitySaturation.Config config, EqualitySaturation.ProofOptions proofOptions) {
        this.config = Objects.requireNonNull(config, "config");
        this.proofOptions = Objects.requireNonNull(proofOptions, "proofOptions");
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
        lastProofOutcome = ProofOutcome.notRequested();
        lastStats = null;
        lastDiagnostics = SaturationDiagnostics.empty();
        lastProvenance = SaturationProvenance.empty();
        String objective = problem.costModel() == null ? "operator-count" : problem.costModel().id();
        // Identity check matters: a custom model can reuse a built-in ID with different semantics.
        if (problem.costModel() != null && problem.costModel() != StructuralCostModel.OPERATOR_COUNT) {
            if (proofOptions != null) {
                lastProofOutcome = ProofOutcome.unavailable(ProofOutcome.Status.UNSUPPORTED_OBJECTIVE,
                    "Proof-required search supports only operator-count extraction.", 0, 0, 0);
                lastExtractionSelection = new ExtractionSelection(objective, objective, "equality-saturation", false,
                    lastProofOutcome.detail());
                String original = problem.rootExpression().trim().replaceAll("\\s+", " ");
                return List.of(rootState(original, problem.scorer().score(original), problem));
            }
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
            if (proofOptions != null) {
                lastProofOutcome = ProofOutcome.unavailable(ProofOutcome.Status.EXTRACTION_UNAVAILABLE,
                    "Search input could not be parsed.", 0, 0, 0);
                recordProofFailureSelection();
            }
            ExpressionScore rootScore = problem.scorer().score(root);
            return List.of(rootState(root, rootScore, problem));
        }

        if (proofOptions != null) return searchWithProof(problem, root, rootAst);

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

        return extractedStates(problem, root, extracted, rootScore, rootState);
    }

    private List<SearchState> extractedStates(SearchProblem problem, String root, String extracted,
            ExpressionScore rootScore, SearchState rootState) {
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

    private List<SearchState> searchWithProof(SearchProblem problem, String root, Expr input) {
        ExpressionScore rootScore = problem.scorer().score(root);
        SearchState original = rootState(root, rootScore, problem);
        var proved = new EqualitySaturation(resolveRules(problem), config)
            .saturateWithProof(input, EqualitySaturationStrategy::nodeCost, proofOptions);
        lastProofOutcome = proved.proof();
        proved.diagnosticResult().ifPresent(result -> {
            lastStats = result.stats();
            lastDiagnostics = result.diagnostics();
            lastProvenance = result.provenance();
        });
        if (!lastProofOutcome.complete()) {
            recordProofFailureSelection();
            return List.of(original);
        }
        String extracted;
        long[] emitted = {0};
        long[] ownWork = {0};
        long remainingWork = proofOptions.captureLimits().maxWork()
            - lastProofOutcome.captureWork() - lastProofOutcome.buildWork();
        try {
            requireRepresentableSymbols(proved.expression(), remainingWork, ownWork);
            extracted = formatProofExpression(proved.expression(), remainingWork, ownWork, units -> {
                if (units < 0 || units > proofOptions.captureLimits().maxCharacters() - emitted[0]
                        || units > remainingWork - ownWork[0]) throw new SearchRenderingLimit();
                ownWork[0] += units;
                emitted[0] += units;
            });
        } catch (UnrepresentableScalar ex) {
            lastProofOutcome = ProofOutcome.unavailable(ProofOutcome.Status.EXTRACTION_UNAVAILABLE,
                "Search output contains an unrepresentable scalar endpoint.",
                lastProofOutcome.captureWork() + ownWork[0], lastProofOutcome.buildWork(), lastProofOutcome.replayWork());
            recordProofFailureSelection();
            return List.of(original);
        } catch (UnrepresentableSymbol ex) {
            lastProofOutcome = ProofOutcome.unavailable(ProofOutcome.Status.EXTRACTION_UNAVAILABLE,
                "Search output contains an unrepresentable symbol.",
                lastProofOutcome.captureWork() + ownWork[0], lastProofOutcome.buildWork(), lastProofOutcome.replayWork());
            recordProofFailureSelection();
            return List.of(original);
        } catch (RuntimeException ex) {
            lastProofOutcome = ProofOutcome.unavailable(ProofOutcome.Status.LIMIT_EXCEEDED,
                "Search output rendering unavailable within proof limits.",
                lastProofOutcome.captureWork() + ownWork[0], lastProofOutcome.buildWork(), lastProofOutcome.replayWork());
            recordProofFailureSelection();
            return List.of(original);
        }
        lastProofOutcome = ProofOutcome.complete(lastProofOutcome.checked().orElseThrow(),
            lastProofOutcome.captureWork() + ownWork[0], lastProofOutcome.buildWork());
        if (extracted.equals(root)) return List.of(original);
        return extractedStates(problem, root, extracted, rootScore, original);
    }

    /** Parenthesize every binary node so text keeps the checker's exact endpoint tree. */
    private static String formatProofExpression(Expr expression, long work, long[] used,
            java.util.function.LongConsumer emitted) {
        if (!(expression instanceof de.regelsuche.ast.BinaryExpr)
                && !(expression instanceof de.regelsuche.ast.FunctionExpr)) {
            return formatProofLeaf(expression, work, used, emitted);
        }
        chargeSearchWork(work, used, 1);
        var pending = new java.util.ArrayDeque<Object>();
        var output = new StringBuilder();
        pending.push(expression);
        while (!pending.isEmpty()) {
            chargeSearchWork(work, used, 1);
            Object action = pending.pop();
            if (action instanceof String text) {
                emitted.accept(text.length());
                output.append(text);
            } else {
                scheduleProofExpression((Expr) action, pending, output, work, used, emitted);
            }
        }
        chargeSearchWork(work, used, output.length());
        return output.toString();
    }

    private static void scheduleProofExpression(Expr expression, java.util.Deque<Object> pending,
            StringBuilder output, long work, long[] used, java.util.function.LongConsumer emitted) {
        if (expression instanceof de.regelsuche.ast.BinaryExpr binary) {
            chargeSearchWork(work, used, 5);
            pending.push(")");
            pending.push(binary.right());
            pending.push(" " + binary.operator().symbol() + " ");
            pending.push(binary.left());
            pending.push("(");
        } else if (expression instanceof de.regelsuche.ast.FunctionExpr function) {
            scheduleProofFunction(function, pending, work, used);
        } else {
            String text = formatProofLeaf(expression, work, used, emitted);
            chargeSearchWork(work, used, text.length());
            output.append(text);
        }
    }

    private static String formatProofLeaf(Expr expression, long work, long[] used,
            java.util.function.LongConsumer emitted) {
        String text = ExpressionFormatter.formatMeasured(expression, emitted);
        if (expression instanceof de.regelsuche.ast.NumberExpr) {
            // Unary minus and fraction syntax parse into binary nodes, not numeric leaves.
            chargeSearchWork(work, used, text.length());
            if (text.startsWith("-") || text.indexOf('/') >= 0) throw new UnrepresentableScalar();
        }
        return text;
    }

    private static void scheduleProofFunction(de.regelsuche.ast.FunctionExpr function,
            java.util.Deque<Object> pending, long work, long[] used) {
        chargeSearchWork(work, used, 1);
        pending.push(")");
        for (int i = function.arguments().size() - 1; i >= 0; i--) {
            chargeSearchWork(work, used, 1);
            pending.push(function.arguments().get(i));
            if (i > 0) {
                chargeSearchWork(work, used, 1);
                pending.push(", ");
            }
        }
        chargeSearchWork(work, used, 2);
        pending.push("(");
        pending.push(function.name());
    }

    private void recordProofFailureSelection() {
        lastExtractionSelection = new ExtractionSelection(lastExtractionSelection.requestedObjective(),
            lastExtractionSelection.effectiveObjective(), "equality-saturation", false, lastProofOutcome.detail());
    }

    private static void requireRepresentableSymbols(Expr expression, long work, long[] used) {
        chargeSearchWork(work, used, 1);
        var pending = new java.util.ArrayDeque<Expr>();
        pending.push(expression);
        while (!pending.isEmpty()) {
            chargeSearchWork(work, used, 1);
            String name = scheduleSymbolChildren(pending.pop(), pending, work, used);
            if (name != null) requireRepresentableName(name, work, used);
        }
    }

    private static String scheduleSymbolChildren(Expr expression, java.util.Deque<Expr> pending,
            long work, long[] used) {
        if (expression instanceof de.regelsuche.ast.FunctionExpr function) {
            for (Expr child : function.arguments()) {
                chargeSearchWork(work, used, 1);
                pending.push(child);
            }
            return function.name();
        }
        if (expression instanceof de.regelsuche.ast.BinaryExpr binary) {
            chargeSearchWork(work, used, 2);
            pending.push(binary.left());
            pending.push(binary.right());
        }
        if (expression instanceof de.regelsuche.ast.VariableExpr variable) return variable.name();
        return null;
    }

    private static void requireRepresentableName(String name, long work, long[] used) {
        if (name.isEmpty()) throw new UnrepresentableSymbol();
        for (int i = 0; i < name.length(); i++) {
            chargeSearchWork(work, used, 1);
            char c = name.charAt(i);
            if (i == 0 ? !Character.isLetter(c) : !Character.isLetterOrDigit(c) && c != '_')
                throw new UnrepresentableSymbol();
        }
    }

    private static void chargeSearchWork(long work, long[] used, int units) {
        if (work - used[0] < units) throw new SearchRenderingLimit();
        used[0] += units;
    }

    private static final class UnrepresentableScalar extends RuntimeException {}
    private static final class UnrepresentableSymbol extends RuntimeException {}
    private static final class SearchRenderingLimit extends RuntimeException {}

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
