package de.regelsuche.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.api.searchgraph.SearchExpression;
import de.regelsuche.assumption.Assumption;
import de.regelsuche.export.MatrixLatexRenderer;
import de.regelsuche.graph.GraphEdge;
import de.regelsuche.graph.GraphSnapshot;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inequality.Comparator;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.proof.ProofBridge;
import de.regelsuche.proof.ProofBridgeService;
import de.regelsuche.proof.ProverExecutor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration tests pinning that the math-domain demos
 * (equation, inequality, derivative, matrix) all run through the same
 * unified workbench pipeline (graph store, replay-ready steps, LaTeX
 * rendering, optional proof bridge), instead of living as separate
 * semantic side-channels.
 */
class UnifiedMathDomainWorkbenchTest {

    @Test
    void equationAppearsInSearchGraph() {
        InMemoryExpressionGraphStore store = new InMemoryExpressionGraphStore();
        UnifiedMathDomainWorkbench workbench = new UnifiedMathDomainWorkbench(store);
        UnifiedMathDomainWorkbench.DemoExecution execution = workbench.runLinearEquation();

        // The result must be the real solver outcome…
        assertEquals("x = 4", execution.resultExpression());
        // …and the corresponding nodes/edges must be present in the
        // shared graph store (i.e. discoverable by the regular
        // SearchGraphAssembler / replay / export pipeline).
        GraphSnapshot snapshot = store.snapshot();
        assertTrue(snapshot.nodes().contains(execution.inputExpression()),
            "original equation must be persisted as a graph node");
        assertTrue(snapshot.nodes().contains(execution.resultExpression()),
            "solved equation must be persisted as a graph node");
        assertFalse(snapshot.edges().isEmpty(),
            "equation solver steps must show up as graph edges");
        // Every edge carries a ruleId — i.e. the equation rewrite
        // identifies itself like any other transformation rule.
        for (GraphEdge edge : snapshot.edges()) {
            assertFalse(edge.transformationRule().isBlank(),
                "edges from the equation adapter must carry a ruleId");
            assertNotNull(edge.validationStatus());
        }
        // The discovered transformation is registered in the store too.
        assertFalse(store.discoveredTransformations().isEmpty(),
            "equation demo must register a DiscoveredTransformation");
        // And the node is typed as an equation (not a plain term).
        assertEquals(SearchExpression.EQUATION, execution.expressionType());
    }

    @Test
    void inequalityReplayShowsComparatorFlip() {
        UnifiedMathDomainWorkbench workbench = new UnifiedMathDomainWorkbench();
        UnifiedMathDomainWorkbench.DemoExecution execution = workbench.runInequalitySignFlip();

        assertEquals("x > -2", execution.resultExpression());
        assertTrue(execution.comparatorFlipped(),
            "demo metadata must surface the comparator flip");
        assertFalse(execution.steps().isEmpty(),
            "inequality steps must be replay-ready (non-empty)");
        // The first replay step's ruleId must signal the division step
        // that produced the flip — that is the marker the UI uses to
        // highlight "Vergleichszeichen gedreht".
        assertEquals("inequality_divide_both_sides", execution.steps().get(0).ruleId());
        assertEquals(SearchExpression.INEQUALITY, execution.expressionType());

        // The adapter also exposes the comparator transition explicitly
        // so the replay can render a contrasted before/after pair.
        de.regelsuche.inequality.InequalityTransformationRuleAdapter adapter =
            new de.regelsuche.inequality.InequalityTransformationRuleAdapter();
        var trace = adapter.trace(
            new de.regelsuche.inequality.Inequality(
                new de.regelsuche.parse.ExpressionParser().parseTerm("-2*x"),
                Comparator.LT,
                new de.regelsuche.parse.ExpressionParser().parseTerm("4")),
            "x"
        ).orElseThrow();
        assertTrue(trace.anyComparatorFlipped(),
            "comparator flip must be visible in the trace metadata");
        assertEquals(Comparator.LT, trace.steps().get(0).comparatorBefore());
        assertEquals(Comparator.GT, trace.steps().get(0).comparatorAfter());
    }

    @Test
    void derivativeRulesParticipateInEqualitySaturation() {
        // The derivative rules are real RewriteRule instances and can
        // therefore be plugged into the same EqualitySaturation engine
        // that the algebraic rules use — the key integration point.
        de.regelsuche.calculus.CalculusDerivativeRules.rules().forEach(rule -> {
            assertNotNull(rule.id());
            assertNotNull(rule.kind());
        });

        de.regelsuche.egraph.EGraph eGraph = new de.regelsuche.egraph.EGraph();
        de.regelsuche.ast.Expr diffExpr =
            de.regelsuche.calculus.CalculusDerivativeRules.derivative(
                new de.regelsuche.parse.ExpressionParser().parseTerm("x^3"), "x");
        de.regelsuche.egraph.EClassId root = eGraph.addExpression(diffExpr);
        de.regelsuche.egraph.EqualitySaturation saturation =
            new de.regelsuche.egraph.EqualitySaturation(
                de.regelsuche.calculus.CalculusDerivativeRules.rules());
        // Make diff() prohibitively expensive so the extractor prefers
        // any rewritten (derivative-free) representative — that is the
        // whole point of running saturation: collapse equivalents to
        // their cheapest non-derivative form.
        de.regelsuche.egraph.EqualitySaturation.Result result =
            saturation.saturate(eGraph, root, node ->
                "fn:diff".equals(node.symbol()) ? 100 : 1);

        // Saturation reaches the textbook result 3 * x^(3-1) (or simpler).
        String extracted = de.regelsuche.parse.ExpressionFormatter.format(result.expression());
        assertFalse(extracted.contains("diff("),
            "equality saturation should have eliminated the diff() operator, got: " + extracted);
        assertTrue(result.stats().appliedRules().containsKey("calculus_diff_power_rule"),
            "the calculus_diff_power_rule must have fired during saturation");
        assertTrue(result.stats().totalApplications() > 0,
            "at least one derivative rule must have fired in the e-graph");
    }

    @Test
    void matrixExpressionsRenderInLatex() {
        MatrixLatexRenderer renderer = new MatrixLatexRenderer();
        String latex = renderer.renderLiteral("[[1, 2], [3, 4]]");
        assertNotNull(latex);
        assertTrue(latex.startsWith("\\begin{bmatrix}"),
            "matrix LaTeX must start with bmatrix: " + latex);
        assertTrue(latex.endsWith("\\end{bmatrix}"),
            "matrix LaTeX must end with bmatrix: " + latex);
        // Row separator
        assertTrue(latex.contains("\\\\"), "rows must be separated by \\\\");
        // Column separator
        assertTrue(latex.contains("&"), "cells must be separated by &");

        String vectorLatex = renderer.renderLiteral("[7, 8, 9]");
        assertTrue(vectorLatex.startsWith("\\begin{bmatrix}"));
        assertTrue(vectorLatex.contains("\\\\"));

        // And via the unified workbench: the matrix demo emits LaTeX that
        // uses the bmatrix block.
        UnifiedMathDomainWorkbench workbench = new UnifiedMathDomainWorkbench();
        UnifiedMathDomainWorkbench.DemoExecution matrixDemo = workbench.runMatrixDistributivity();
        assertTrue(matrixDemo.inputLatex().contains("\\begin{bmatrix}"),
            "matrix demo input must render with bmatrix");
    }

    @Test
    void proofBridgeStatusVisibleInWorkbench() {
        ProofBridge stub = (left, right, assumptions) -> new ProofBridge.ProofAttempt(
            CandidateProofStatus.FORMALLY_PROVABLE,
            "theorem eq : " + left + " = " + right + " := by trivial\n",
            "lean4"
        );
        ProverExecutor success = new ProverExecutor(
            List.of("true"),
            "lean4",
            ".lean",
            Duration.ofSeconds(5),
            (exit, out, err) -> exit == 0
        );
        ProofBridgeService service = new ProofBridgeService(stub, null, success);

        UnifiedMathDomainWorkbench workbench = new UnifiedMathDomainWorkbench(
            new InMemoryExpressionGraphStore(), service);
        UnifiedMathDomainWorkbench.DemoExecution execution = workbench.runLinearEquation();

        // The workbench surface exposes the proof status (whether the
        // candidate was confirmed by an external prover or only
        // generated as a script) so the UI can render it.
        assertNotNull(execution.proofOutcome(),
            "proof bridge must be invoked when a service is configured");
        assertEquals(CandidateProofStatus.FORMALLY_PROVED, execution.proofStatus(),
            "successful executor must lift the candidate to FORMALLY_PROVED");
        assertTrue(execution.formallyProved(),
            "DemoExecution.formallyProved() must return true after PROVER_CONFIRMED");
        assertTrue(execution.proofArtifact().isPresent(),
            "the generated proof script must be exposed for the UI");
    }

    @Test
    void discoveryPlusLearnsEquationMacroRule() {
        // The unified workbench surfaces equation rewrites as
        // RuleCandidate(s) so the macro-rule learning loop sees them
        // exactly like any other discovered transformation.
        InMemoryExpressionGraphStore store = new InMemoryExpressionGraphStore();
        UnifiedMathDomainWorkbench workbench = new UnifiedMathDomainWorkbench(store);
        workbench.runLinearEquation();
        List<RuleCandidate> candidates = store.ruleCandidates();
        assertFalse(candidates.isEmpty(),
            "equation rewrite must produce at least one RuleCandidate");
        RuleCandidate candidate = candidates.get(0);
        assertEquals(RuleStatus.NEW, candidate.status());
        assertTrue(candidate.equivalenceVerified());
        // The candidate captures the source/result pair – which is what
        // RuleCandidateMiner/MacroRuleLearningService consume.
        assertEquals("x + 3 = 7", candidate.leftPattern());
        assertEquals("x = 4", candidate.rightPattern());
    }

    @Test
    void allMathDomainDemosUseUnifiedWorkbench() {
        InMemoryExpressionGraphStore store = new InMemoryExpressionGraphStore();
        UnifiedMathDomainWorkbench workbench = new UnifiedMathDomainWorkbench(store);
        Map<String, UnifiedMathDomainWorkbench.DemoExecution> all = workbench.runAll();

        assertEquals(4, all.size(),
            "the unified workbench must expose all four math-domain demos");
        for (var entry : all.entrySet()) {
            UnifiedMathDomainWorkbench.DemoExecution e = entry.getValue();
            // Each demo went through the shared graph store…
            // …emitted at least one edge (replay-ready)…
            assertFalse(e.edges().isEmpty(),
                entry.getKey() + " must produce graph edges");
            // …carries a typed SearchExpression (no implicit fallback to TERM
            // for equations/inequalities/matrices)…
            assertNotNull(e.expressionType());
            // …registered a DiscoveredTransformation for the replay
            // pipeline…
            assertNotNull(e.discoveredTransformation());
            // …and exposed at least one TransformationStep.
            assertFalse(e.steps().isEmpty(),
                entry.getKey() + " must produce TransformationSteps");
        }
        // The shared store contains contributions from every demo —
        // proving there is no separate side-path: a single snapshot
        // covers all four.
        GraphSnapshot snapshot = store.snapshot();
        assertTrue(snapshot.edges().size() >= 4,
            "shared graph store must aggregate edges from all four demos");
        assertEquals(4, store.discoveredTransformations().size(),
            "shared store must record one DiscoveredTransformation per demo");

        // Sanity: the type assignments differentiate the domains
        assertEquals(SearchExpression.EQUATION,
            all.get(UnifiedMathDomainWorkbench.DEMO_EQUATION).expressionType());
        assertEquals(SearchExpression.INEQUALITY,
            all.get(UnifiedMathDomainWorkbench.DEMO_INEQUALITY).expressionType());
        assertEquals(SearchExpression.MATRIX,
            all.get(UnifiedMathDomainWorkbench.DEMO_MATRIX).expressionType());

        // Unused import guard.
        new Assumption(Assumption.Kind.CUSTOM, "ok", List.of());
    }
}
