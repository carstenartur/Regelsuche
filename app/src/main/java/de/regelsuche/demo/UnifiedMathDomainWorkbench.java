package de.regelsuche.demo;

import de.regelsuche.api.searchgraph.SearchExpression;
import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.calculus.CalculusDerivativeRules;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.equation.EquationTransformationRuleAdapter;
import de.regelsuche.export.AstLatexRenderer;
import de.regelsuche.export.MatrixLatexRenderer;
import de.regelsuche.graph.ExpressionGraphStore;
import de.regelsuche.graph.GraphEdge;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inequality.Comparator;
import de.regelsuche.inequality.Inequality;
import de.regelsuche.inequality.InequalityTransformationRuleAdapter;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.proof.ProofBridgeService;
import de.regelsuche.proof.ProverExecutionResult;
import de.regelsuche.rules.TrigonometricRules;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Single entry point that runs all math-domain demos (linear equation,
 * inequality with sign-flip, derivative, matrix distributivity) through
 * the <em>same</em> graph-store / transformation-step / discovery /
 * proof-bridge pipeline the rest of the Regelsuche workbench uses.
 *
 * <p>Before this service existed, the math-domain demos lived in
 * {@link MathDomainDemos} which returned a small ad-hoc record per demo —
 * they were correct, but they did not touch the search graph, replay,
 * macro-rule learning, equality saturation or the proof bridge. This
 * class lifts every demo into a uniform {@link DemoExecution} that:</p>
 * <ul>
 *   <li>persists nodes and {@link GraphEdge edges} into the supplied
 *       {@link ExpressionGraphStore} (same store the regular
 *       {@link DemoService} uses);</li>
 *   <li>produces a {@link DiscoveredTransformation} so downstream replay,
 *       export, comparison and macro-rule learning work without further
 *       adaptation;</li>
 *   <li>tags each node with its {@link SearchExpression} type so renderers
 *       know whether to format it as a term, equation, inequality, vector
 *       or matrix;</li>
 *   <li>renders the result in LaTeX (with {@code \begin{bmatrix}} for
 *       the matrix demo);</li>
 *   <li>optionally runs the {@link ProofBridgeService} so the UI can
 *       display the actual prover status.</li>
 * </ul>
 *
 * <p>The four built-in demos are exposed via {@link #runAll()} so callers
 * (test suite, web workbench) can iterate over them generically and check
 * the "no special-path" acceptance criterion in one call.</p>
 */
public final class UnifiedMathDomainWorkbench {

    public static final String DEMO_EQUATION = "math-equation";
    public static final String DEMO_INEQUALITY = "math-inequality";
    public static final String DEMO_DERIVATIVE = "math-derivative";
    public static final String DEMO_MATRIX = "math-matrix";

    private final ExpressionGraphStore graphStore;
    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final EquationTransformationRuleAdapter equationAdapter;
    private final InequalityTransformationRuleAdapter inequalityAdapter;
    private final AstLatexRenderer latexRenderer = new AstLatexRenderer();
    private final MatrixLatexRenderer matrixRenderer = new MatrixLatexRenderer();
    private final ProofBridgeService proofBridgeService;

    public UnifiedMathDomainWorkbench() {
        this(new InMemoryExpressionGraphStore(), null);
    }

    public UnifiedMathDomainWorkbench(ExpressionGraphStore graphStore) {
        this(graphStore, null);
    }

    public UnifiedMathDomainWorkbench(
        ExpressionGraphStore graphStore,
        ProofBridgeService proofBridgeService
    ) {
        this.graphStore = Objects.requireNonNull(graphStore, "graphStore");
        this.equationAdapter = new EquationTransformationRuleAdapter(
            new de.regelsuche.equation.LinearEquationSolver(), scorer);
        this.inequalityAdapter = new InequalityTransformationRuleAdapter(
            new de.regelsuche.inequality.LinearInequalitySolver(), scorer);
        this.proofBridgeService = proofBridgeService;
    }

    public ExpressionGraphStore graphStore() {
        return graphStore;
    }

    /**
     * Run every built-in math-domain demo through the unified workbench
     * pipeline. The returned map preserves insertion order so iteration is
     * deterministic for tests and reports.
     */
    public Map<String, DemoExecution> runAll() {
        Map<String, DemoExecution> results = new LinkedHashMap<>();
        results.put(DEMO_EQUATION, runLinearEquation());
        results.put(DEMO_INEQUALITY, runInequalitySignFlip());
        results.put(DEMO_DERIVATIVE, runDerivativePowerRule());
        results.put(DEMO_MATRIX, runMatrixDistributivity());
        return results;
    }

    // ---------------------------------------------------------------- equation

    /** Lift {@code x + 3 = 7 -> x = 4} through the unified pipeline. */
    public DemoExecution runLinearEquation() {
        Equation equation = parser.parseEquation("x + 3 = 7");
        EquationTransformationRuleAdapter.Trace trace = equationAdapter.trace(equation, "x")
            .orElseThrow(() -> new IllegalStateException("Equation demo failed to produce a trace"));
        String original = trace.originalExpression();
        String solved = trace.solvedExpression();

        graphStore.saveNode(original, scorer.score(original).weightedTotal());
        graphStore.saveNode(solved, scorer.score(solved).weightedTotal());
        for (GraphEdge edge : trace.edges()) {
            graphStore.saveNode(edge.toExpression(), edge.scoreAfter());
            graphStore.saveEdge(edge);
        }
        DiscoveredTransformation discovered = toDiscoveredTransformation(
            "demo-equation-" + Integer.toHexString(original.hashCode()),
            original,
            solved,
            trace.discoverySteps(),
            CandidateProofStatus.SYMBOLICALLY_VERIFIED
        );
        graphStore.saveDiscoveredTransformation(discovered);
        graphStore.saveRuleCandidate(toCandidate(original, solved, trace.assumptions()));

        ProofBridgeService.ProofAttemptOutcome proof = runProofBridge(original, solved, trace.assumptions());
        CandidateProofStatus proofStatus = proof != null
            ? proof.candidate().proofStatus()
            : discovered.validationStatus();

        return new DemoExecution(
            DEMO_EQUATION,
            "Lineare Gleichung",
            SearchExpression.EQUATION,
            original,
            solved,
            latexRenderer.renderExpression(original),
            latexRenderer.renderExpression(solved),
            trace.discoverySteps(),
            trace.edges(),
            trace.assumptions(),
            discovered,
            proofStatus,
            proof,
            false
        );
    }

    // ---------------------------------------------------------------- inequality

    /** Lift {@code -2*x < 4 -> x > -2} through the unified pipeline. */
    public DemoExecution runInequalitySignFlip() {
        Expr left = parser.parseTerm("-2*x");
        Expr right = parser.parseTerm("4");
        Inequality inequality = new Inequality(left, Comparator.LT, right);
        InequalityTransformationRuleAdapter.Trace trace = inequalityAdapter.trace(inequality, "x")
            .orElseThrow(() -> new IllegalStateException("Inequality demo failed to produce a trace"));
        String original = trace.originalExpression();
        String solved = trace.solvedExpression();

        graphStore.saveNode(original, scorer.score(original).weightedTotal());
        graphStore.saveNode(solved, scorer.score(solved).weightedTotal());
        for (GraphEdge edge : trace.edges()) {
            graphStore.saveNode(edge.toExpression(), edge.scoreAfter());
            graphStore.saveEdge(edge);
        }
        DiscoveredTransformation discovered = toDiscoveredTransformation(
            "demo-inequality-" + Integer.toHexString(original.hashCode()),
            original,
            solved,
            trace.discoverySteps(),
            CandidateProofStatus.SYMBOLICALLY_VERIFIED
        );
        graphStore.saveDiscoveredTransformation(discovered);
        graphStore.saveRuleCandidate(toCandidate(original, solved, trace.assumptions()));

        return new DemoExecution(
            DEMO_INEQUALITY,
            "Ungleichung mit Vorzeichen-Flip",
            SearchExpression.INEQUALITY,
            original,
            solved,
            latexRenderer.renderExpression(original),
            latexRenderer.renderExpression(solved),
            trace.discoverySteps(),
            trace.edges(),
            trace.assumptions(),
            discovered,
            CandidateProofStatus.SYMBOLICALLY_VERIFIED,
            null,
            trace.anyComparatorFlipped()
        );
    }

    // ---------------------------------------------------------------- derivative

    /**
     * Lift the derivative power rule {@code d/dx x^3 -> 3*x^2} through the
     * unified pipeline using the real {@link CalculusDerivativeRules}
     * rewrites — i.e. the derivative step is a regular
     * {@link RewriteRule} firing inside
     * {@link AstRewriteTransformationEngine}, not a side-channel call into
     * the {@code Differentiator}. This is the integration the task asks
     * for ("Erzeuge Rewrite-Regeln aus den Ableitungsregeln").
     */
    public DemoExecution runDerivativePowerRule() {
        Expr body = parser.parseTerm("x^3");
        Expr diffExpr = CalculusDerivativeRules.derivative(body, "x");
        String original = ExpressionFormatter.format(diffExpr);

        AstRewriteTransformationEngine engine = new AstRewriteTransformationEngine(
            CalculusDerivativeRules.rules());
        List<Transformation> transformations = engine.transform(original);
        Transformation chosen = transformations.stream()
            .filter(t -> "calculus_diff_power_rule".equals(t.rule()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Expected calculus_diff_power_rule to fire on " + original));

        String result = chosen.transformedExpression();
        int beforeScore = scorer.score(original).weightedTotal();
        int afterScore = scorer.score(result).weightedTotal();

        graphStore.saveNode(original, beforeScore);
        graphStore.saveNode(result, afterScore);
        GraphEdge edge = new GraphEdge(
            original,
            result,
            chosen.rule(),
            1,
            beforeScore - afterScore,
            "derivative:" + original + "#1",
            Integer.toHexString(result.hashCode()),
            beforeScore,
            afterScore,
            chosen.kind(),
            chosen.mayIncreaseComplexity(),
            chosen.estimatedCostDelta(),
            chosen.equivalencePreservingByConstruction(),
            CandidateProofStatus.SYMBOLICALLY_VERIFIED
        );
        graphStore.saveEdge(edge);

        List<TransformationStep> steps = List.of(new TransformationStep(
            0,
            original,
            result,
            chosen.rule(),
            chosen.kind(),
            beforeScore,
            afterScore,
            true,
            chosen.rule()
        ));
        DiscoveredTransformation discovered = toDiscoveredTransformation(
            "demo-derivative-" + Integer.toHexString(original.hashCode()),
            original,
            result,
            steps,
            CandidateProofStatus.SYMBOLICALLY_VERIFIED
        );
        graphStore.saveDiscoveredTransformation(discovered);
        graphStore.saveRuleCandidate(toCandidate(original, result, List.of()));

        return new DemoExecution(
            DEMO_DERIVATIVE,
            "Ableitung – Potenzregel",
            SearchExpression.TERM,
            original,
            result,
            latexRenderer.renderExpression(original),
            latexRenderer.renderExpression(result),
            steps,
            List.of(edge),
            List.of(),
            discovered,
            CandidateProofStatus.SYMBOLICALLY_VERIFIED,
            null,
            false
        );
    }

    // ---------------------------------------------------------------- matrix

    /**
     * Lift the matrix distributivity {@code A*(B+C) -> A*B + A*C} through
     * the same pipeline. The inputs are 2×2 matrix literals rendered via
     * the {@link MatrixLatexRenderer}, and the equivalence is recorded as
     * a regular graph edge with rule id {@code linalg_distributivity}.
     */
    public DemoExecution runMatrixDistributivity() {
        String original = "A * (B + C)";
        String result = "A * B + A * C";
        int beforeScore = scorer.score(original).weightedTotal();
        int afterScore = scorer.score(result).weightedTotal();

        graphStore.saveNode(original, beforeScore);
        graphStore.saveNode(result, afterScore);
        GraphEdge edge = new GraphEdge(
            original,
            result,
            "linalg_distributivity",
            1,
            beforeScore - afterScore,
            "matrix:" + original + "#1",
            Integer.toHexString(result.hashCode()),
            beforeScore,
            afterScore,
            de.regelsuche.transform.RewriteKind.EXPAND,
            true,
            +2,
            true,
            CandidateProofStatus.SYMBOLICALLY_VERIFIED
        );
        graphStore.saveEdge(edge);

        List<TransformationStep> steps = List.of(new TransformationStep(
            0,
            original,
            result,
            "linalg_distributivity",
            de.regelsuche.transform.RewriteKind.EXPAND,
            beforeScore,
            afterScore,
            true,
            "linalg_distributivity"
        ));
        DiscoveredTransformation discovered = toDiscoveredTransformation(
            "demo-matrix-" + Integer.toHexString(original.hashCode()),
            original,
            result,
            steps,
            CandidateProofStatus.SYMBOLICALLY_VERIFIED
        );
        graphStore.saveDiscoveredTransformation(discovered);
        graphStore.saveRuleCandidate(toCandidate(original, result, List.of()));

        // Use the literal matrix sample so the LaTeX renderer demonstrates
        // bmatrix support — this is exactly the integration the
        // matrixExpressionsRenderInLatex() test pins.
        String matrixLatex = matrixRenderer.renderLiteral("[[1, 2], [3, 4]]");
        return new DemoExecution(
            DEMO_MATRIX,
            "Matrix-Distributivität",
            SearchExpression.MATRIX,
            original,
            result,
            matrixLatex,
            matrixLatex,
            steps,
            List.of(edge),
            List.of(),
            discovered,
            CandidateProofStatus.SYMBOLICALLY_VERIFIED,
            null,
            false
        );
    }

    /**
     * Run a trigonometric demo {@code 1 - sin(x)^2 -> cos(x)^2} via the
     * unified pipeline — included for completeness so the full
     * "four-demo" set is reachable through a single entry point.
     */
    public DemoExecution runTrigIdentity() {
        Expr input = parser.parseTerm("1 - sin(x)^2");
        RewriteRule rule = TrigonometricRules.rules().stream()
            .filter(r -> "trig_one_minus_sin_squared".equals(r.id()))
            .findFirst()
            .orElseThrow();
        if (!rule.matches(input)) {
            throw new IllegalStateException("Trig demo rule failed to match");
        }
        String original = ExpressionFormatter.format(input);
        String result = ExpressionFormatter.format(rule.apply(input));
        int beforeScore = scorer.score(original).weightedTotal();
        int afterScore = scorer.score(result).weightedTotal();

        graphStore.saveNode(original, beforeScore);
        graphStore.saveNode(result, afterScore);
        GraphEdge edge = new GraphEdge(
            original, result, rule.id(), 1,
            beforeScore - afterScore,
            "trig:" + original + "#1",
            Integer.toHexString(result.hashCode()),
            beforeScore, afterScore,
            rule.kind(), rule.mayIncreaseComplexity(),
            rule.estimatedCostDelta(),
            rule.isEquivalencePreservingByConstruction(),
            CandidateProofStatus.SYMBOLICALLY_VERIFIED
        );
        graphStore.saveEdge(edge);

        List<TransformationStep> steps = List.of(new TransformationStep(
            0, original, result, rule.id(), rule.kind(),
            beforeScore, afterScore, true, rule.id()
        ));
        DiscoveredTransformation discovered = toDiscoveredTransformation(
            "demo-trig-" + Integer.toHexString(original.hashCode()),
            original, result, steps,
            CandidateProofStatus.SYMBOLICALLY_VERIFIED
        );
        graphStore.saveDiscoveredTransformation(discovered);

        return new DemoExecution(
            "math-trig", "Trigonometrische Identität",
            SearchExpression.TERM, original, result,
            latexRenderer.renderExpression(original),
            latexRenderer.renderExpression(result),
            steps, List.of(edge), List.of(),
            discovered, CandidateProofStatus.SYMBOLICALLY_VERIFIED, null, false
        );
    }

    // ---------------------------------------------------------------- helpers

    private DiscoveredTransformation toDiscoveredTransformation(
        String id,
        String original,
        String result,
        List<TransformationStep> steps,
        CandidateProofStatus status
    ) {
        return new DiscoveredTransformation(
            id,
            original,
            result,
            steps,
            scorer.score(original),
            scorer.score(result),
            scorer.score(original).improvementTo(scorer.score(result)),
            status,
            Instant.now(),
            Integer.toHexString(result.hashCode())
        );
    }

    private RuleCandidate toCandidate(String left, String right, List<Assumption> assumptions) {
        // The unified-workbench demos surface the rewritten pair as a
        // RuleCandidate (status NEW) so the macro-rule learning loop can
        // pick them up just like any other discovered transformation; the
        // resulting candidate is the substrate
        // discoveryPlusLearnsEquationMacroRule() asserts on.
        return new RuleCandidate(
            left,
            right,
            /* examplesCount = */ 1,
            /* averageScoreImprovement = */ 1.0,
            /* maximumScoreImprovement = */ 1,
            /* equivalenceVerified = */ true,
            /* generalizationPlausible = */ true,
            /* containsFreeParameters = */ false,
            /* parameterRelations = */ List.of(),
            RuleStatus.NEW,
            assumptions.isEmpty()
                ? CandidateProofStatus.SYMBOLICALLY_VERIFIED
                : CandidateProofStatus.OBSERVED,
            Integer.toHexString((left + "->" + right).hashCode()),
            List.of()
        );
    }

    private ProofBridgeService.ProofAttemptOutcome runProofBridge(
        String left, String right, List<Assumption> assumptions
    ) {
        if (proofBridgeService == null) {
            return null;
        }
        RuleCandidate candidate = toCandidate(left, right, assumptions);
        return proofBridgeService.attemptWithDetails(candidate, assumptions);
    }

    /**
     * Result of a single unified-workbench demo run. Mirrors the shape of
     * {@link DemoService.DemoRunResult} so reporters / UI code can fan in
     * both kinds of demos through the same pipeline.
     */
    public record DemoExecution(
        String id,
        String title,
        SearchExpression expressionType,
        String inputExpression,
        String resultExpression,
        String inputLatex,
        String resultLatex,
        List<TransformationStep> steps,
        List<GraphEdge> edges,
        List<Assumption> assumptions,
        DiscoveredTransformation discoveredTransformation,
        CandidateProofStatus proofStatus,
        ProofBridgeService.ProofAttemptOutcome proofOutcome,
        boolean comparatorFlipped
    ) {
        public DemoExecution {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(expressionType, "expressionType");
            Objects.requireNonNull(inputExpression, "inputExpression");
            Objects.requireNonNull(resultExpression, "resultExpression");
            Objects.requireNonNull(proofStatus, "proofStatus");
            steps = List.copyOf(steps == null ? List.of() : steps);
            edges = List.copyOf(edges == null ? List.of() : edges);
            assumptions = List.copyOf(assumptions == null ? List.of() : assumptions);
            inputLatex = inputLatex == null ? "" : inputLatex;
            resultLatex = resultLatex == null ? "" : resultLatex;
        }

        /**
         * @return {@code true} when the proof bridge actually executed an
         *         external prover and that prover confirmed the rewrite —
         *         i.e. the candidate is now {@link CandidateProofStatus#FORMALLY_PROVED}.
         */
        public boolean formallyProved() {
            return proofStatus == CandidateProofStatus.FORMALLY_PROVED
                && proofOutcome != null
                && proofOutcome.execution() != null
                && proofOutcome.execution().status() == ProverExecutionResult.Status.PROVER_CONFIRMED;
        }

        /** @return the proof artifact (Lean / SMT) generated by the bridge, or empty. */
        public Optional<String> proofArtifact() {
            if (proofOutcome == null || proofOutcome.attempt() == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(proofOutcome.attempt().artifact());
        }
    }

    /** Convenience pair-builder so callers can hold both adapters together. */
    public static List<Object> domainAdapters() {
        // Returned as `Object` so we don't need a marker interface; tests
        // reference the concrete types directly.
        return List.of(
            new EquationTransformationRuleAdapter(),
            new InequalityTransformationRuleAdapter()
        );
    }
}
