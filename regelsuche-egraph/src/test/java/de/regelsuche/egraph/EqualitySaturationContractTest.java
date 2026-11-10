package de.regelsuche.egraph;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class EqualitySaturationContractTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final PatternExpr.Placeholder a = new PatternExpr.Placeholder("a");

    @Test void oversizedRhsStopsWithinIterationAndLeavesGraphUsable() {
        var graph = new EGraph();
        var root = graph.addExpression(parser.parseTerm("x"));
        var rule = new PatternRewriteRule("grow", a,
            new PatternExpr.Function("f", List.of(new PatternExpr.Function("g", List.of(a)))));
        var result = new EqualitySaturation(List.of(rule), new EqualitySaturation.Config(1, 2))
            .saturate(graph, root, node -> 1);
        assertTrue(graph.nodeCount() <= 2);
        assertEquals(SaturationStats.Reason.NODE_BUDGET, result.stats().stopReason());
        assertFalse(result.stats().saturated());
        assertEquals(parser.parseTerm("x"), result.expression());
        graph.rebuild();
        assertEquals(root, graph.addExpression(parser.parseTerm("x")));
        graph.addExpression(parser.parseTerm("z")); // run's cap must be released
    }

    @Test void oversizedConcreteRhsAlsoStopsWithoutAnEquality() {
        var graph = new EGraph();
        var root = graph.addExpression(parser.parseTerm("x"));
        var rule = new PatternRewriteRule("concrete-grow", a, a) {
            @Override public Expr apply(Expr expression) { return parser.parseTerm("f(g(x))"); }
        };
        var result = new EqualitySaturation(List.of(rule), new EqualitySaturation.Config(1, 2))
            .saturate(graph, root, node -> 1);
        assertEquals(2, graph.nodeCount());
        assertEquals(SaturationStats.Reason.NODE_BUDGET, result.stats().stopReason());
        assertEquals(0, result.stats().totalApplications());
        assertEquals(0, result.diagnostics().count(SaturationDiagnostics.Outcome.RULE_FAILURE));
        assertEquals(parser.parseTerm("x"), result.expression());
        graph.rebuild();
        assertFalse(graph.areEquivalent(root, graph.addExpression(parser.parseTerm("g(x)"))));
    }

    @Test void reachingTheCapStopsBeforeCallingAnotherRule() {
        var graph = new EGraph();
        var root = graph.addExpression(parser.parseTerm("x"));
        var grow = new PatternRewriteRule("grow", a, new PatternExpr.Function("f", List.of(a)));
        var shouldNotRun = new PatternRewriteRule("later", a, a) {
            @Override public boolean matches(Expr expression) { throw new AssertionError("rule after budget"); }
        };
        var result = new EqualitySaturation(List.of(grow, shouldNotRun), new EqualitySaturation.Config(1, 2))
            .saturate(graph, root, node -> 1);
        assertEquals(2, graph.nodeCount());
        assertEquals(1, result.stats().totalApplications());
        assertEquals(SaturationStats.Reason.NODE_BUDGET, result.stats().stopReason());
    }

    @Test void ruleFailuresAreStructuredForEachCallbackStage() {
        for (var stage : List.of(SaturationDiagnostics.Stage.MATCHES,
                SaturationDiagnostics.Stage.ASSUMPTIONS, SaturationDiagnostics.Stage.APPLY)) {
            var rule = new PatternRewriteRule("broken", a, a) {
                @Override public boolean matches(Expr expr) {
                    if (stage == SaturationDiagnostics.Stage.MATCHES) throw new IllegalArgumentException("match failure");
                    return true;
                }
                @Override public List<de.regelsuche.assumption.Assumption> assumptions(Expr expr) {
                    if (stage == SaturationDiagnostics.Stage.ASSUMPTIONS) throw new IllegalArgumentException("guard failure");
                    return List.of();
                }
                @Override public Expr apply(Expr expr) { throw new IllegalArgumentException("apply failure"); }
            };
            var result = run("x", rule);
            assertEquals(1, result.diagnostics().count(SaturationDiagnostics.Outcome.RULE_FAILURE));
            assertEquals(stage, result.diagnostics().events().getFirst().stage());
            assertEquals(SaturationStats.Reason.RULE_FAILURES, result.stats().stopReason());
            assertFalse(result.stats().saturated());
        }
    }

    @Test void diagnosticRetentionIsBoundedWithoutLosingCounts() {
        var graph = new EGraph();
        var root = graph.addExpression(parser.parseTerm("x"));
        var rules = java.util.stream.IntStream.range(0, 200).mapToObj(i -> (RewriteRule)
            new PatternRewriteRule("broken" + i, a, a) {
                @Override public Expr apply(Expr expr) { throw new IllegalStateException("broken"); }
            }).toList();
        var result = new EqualitySaturation(rules).saturate(graph, root, node -> 1);
        assertEquals(200, result.diagnostics().count(SaturationDiagnostics.Outcome.RULE_FAILURE));
        assertEquals(SaturationDiagnostics.MAX_EVENTS, result.diagnostics().events().size());
        assertTrue(result.diagnostics().droppedEvents() > 0);
    }

    @Test void customRulesSeeAlternativeRootRepresentatives() {
        var graph = new EGraph();
        var root = graph.addExpression(parser.parseTerm("x + 0"));
        graph.union(root, graph.addExpression(parser.parseTerm("x")));
        graph.rebuild();
        var rule = new PatternRewriteRule("alternative", a, a) {
            @Override public boolean matches(Expr expr) { return expr.equals(parser.parseTerm("x + 0")); }
            @Override public Expr apply(Expr expr) { return parser.parseTerm("0 + x"); }
        };
        var result = new EqualitySaturation(List.of(rule)).saturate(graph, root, node -> 1);
        assertTrue(result.stats().appliedRules().containsKey("alternative"));
        assertTrue(result.provenance().witnesses().stream().anyMatch(w ->
            w.before().equals(parser.parseTerm("x + 0")) && w.replay(rule)));
        assertFalse(result.provenance().hasExtractionProof());
    }

    @Test void guardedRulesRemainUnmergedAndDiagnoseConditions() {
        var rule = AstRewriteTransformationEngine.defaultRules().stream()
            .filter(r -> r.id().equals("ast_cancel_division_factor")).findFirst().orElseThrow();
        var result = run("(x * y) / x", rule);
        assertEquals(parser.parseTerm("(x * y) / x"), result.expression());
        assertTrue(result.diagnostics().count(SaturationDiagnostics.Outcome.UNRESOLVED_CONDITIONS) > 0);
        assertTrue(result.provenance().witnesses().isEmpty());
    }

    @Test void nativeWitnessReplaysActualRuleButDoesNotClaimCongruenceProof() {
        var rule = new PatternRewriteRule("zero", new PatternExpr.Operation(
            de.regelsuche.ast.BinaryOperator.ADD, a,
            new PatternExpr.LiteralNumber(de.regelsuche.scalar.ExactRational.ZERO)), a);
        var result = run("f(x + 0)", rule);
        assertEquals(parser.parseTerm("f(x)"), result.expression());
        assertTrue(result.provenance().witnesses().getFirst().replay(rule));
        assertFalse(result.provenance().hasExtractionProof());
    }

    @Test void acRecognitionProfilesUseTheirConcreteMatchingContract() {
        var rule = new PatternRewriteRule("ac-zero", new PatternExpr.Operation(
            de.regelsuche.ast.BinaryOperator.ADD, a,
            new PatternExpr.LiteralNumber(de.regelsuche.scalar.ExactRational.ZERO)), a,
            RecognitionProfile.arithmeticAc());
        var result = run("0 + x", rule);
        assertEquals(parser.parseTerm("x"), result.expression());
        assertTrue(result.provenance().witnesses().getFirst().replay(rule));
    }

    @Test void unavailableChildDoesNotHideFiniteConcreteRepresentatives() {
        var graph = new EGraph();
        var root = graph.addExpression(parser.parseTerm("x"));
        graph.union(root, graph.addExpression(parser.parseTerm("x + 0")));
        graph.rebuild();
        java.util.function.ToIntFunction<ENode> cost = node ->
            node.symbol().equals("num:0") ? Integer.MAX_VALUE : 1;
        assertEquals(List.of(parser.parseTerm("x")), graph.representatives(root, cost));
        var rule = new PatternRewriteRule("finite-alternative", a, a) {
            @Override public boolean matches(Expr expression) { return expression.equals(parser.parseTerm("x")); }
            @Override public Expr apply(Expr expression) { return parser.parseTerm("x * 1"); }
        };
        var result = new EqualitySaturation(List.of(rule), new EqualitySaturation.Config(2, 100))
            .saturate(graph, root, cost);
        assertTrue(result.stats().appliedRules().containsKey(rule.id()));
        assertTrue(result.provenance().witnesses().stream().anyMatch(witness -> witness.replay(rule)));
        assertEquals(0, result.diagnostics().count(SaturationDiagnostics.Outcome.RULE_FAILURE));
        assertTrue(result.diagnostics().count(SaturationDiagnostics.Outcome.NON_APPLICABLE) > 0);
    }

    @Test void whollyUnavailableCandidateHasNoConcreteRepresentatives() {
        var graph = new EGraph();
        var root = graph.addExpression(parser.parseTerm("0"));
        assertEquals(List.of(), graph.representatives(root, node -> Integer.MAX_VALUE));
    }

    @Test void unavailableStartRootCanBecomeFiniteThroughNativeRewrite() {
        var graph = new EGraph();
        var root = graph.addExpression(parser.parseTerm("x + 0"));
        var rule = new PatternRewriteRule("add-zero",
            PatternExpr.op(de.regelsuche.ast.BinaryOperator.ADD, a, PatternExpr.num(0)), a);
        var result = new EqualitySaturation(List.of(rule), new EqualitySaturation.Config(2, 100))
            .saturate(graph, root, node -> node.symbol().equals("op:ADD") ? Integer.MAX_VALUE : 1);
        assertEquals(parser.parseTerm("x"), result.expression());
        assertEquals(1, result.stats().totalApplications());
        assertTrue(result.provenance().witnesses().getFirst().replay(rule));
    }

    @Test void trivialPatternSubclassMatchesEvenWhenTheObjectiveExcludesItsSource() {
        var source = PatternExpr.op(de.regelsuche.ast.BinaryOperator.ADD, a, PatternExpr.num(0));
        var nativeRule = new PatternRewriteRule("native-zero", source, a);
        var subclassRule = new PatternRewriteRule("subclass-zero", source, a) {};
        assertSimplifiesExcludedAddition("x + 0", nativeRule);
        assertSimplifiesExcludedAddition("x + 0", subclassRule);
    }

    @Test void acPatternMatchesEvenWhenTheObjectiveExcludesItsSource() {
        var nativeRule = new PatternRewriteRule("native-left-zero",
            PatternExpr.op(de.regelsuche.ast.BinaryOperator.ADD, PatternExpr.num(0), a), a);
        var acRule = new PatternRewriteRule("ac-right-zero",
            PatternExpr.op(de.regelsuche.ast.BinaryOperator.ADD, a, PatternExpr.num(0)), a,
            RecognitionProfile.arithmeticAc());
        assertSimplifiesExcludedAddition("0 + x", nativeRule);
        assertSimplifiesExcludedAddition("0 + x", acRule);
    }

    private void assertSimplifiesExcludedAddition(String expression, RewriteRule rule) {
        var graph = new EGraph();
        var root = graph.addExpression(parser.parseTerm(expression));
        var result = new EqualitySaturation(List.of(rule), new EqualitySaturation.Config(2, 100))
            .saturate(graph, root, node -> node.symbol().equals("op:ADD") ? Integer.MAX_VALUE : 1);
        assertEquals(parser.parseTerm("x"), result.expression());
        assertEquals(1, result.stats().totalApplications());
        assertEquals(0, result.diagnostics().count(SaturationDiagnostics.Outcome.RULE_FAILURE));
        assertTrue(result.provenance().witnesses().getFirst().replay(rule));
    }

    @Test void nativeWitnessRetentionCannotChangeRewriteApplicability() {
        for (int fillers : List.of(0, SaturationProvenance.MAX_WITNESSES)) {
            var graph = new EGraph();
            var root = graph.addExpression(parser.parseTerm("0 * x"));
            graph.union(root, graph.addExpression(parser.parseTerm("0 + 0")));
            for (int i = 0; i < fillers; i++) {
                graph.addExpression(parser.parseTerm("v" + i + " * 1"));
            }
            graph.rebuild();
            var unit = new PatternRewriteRule("unit",
                PatternExpr.op(de.regelsuche.ast.BinaryOperator.MUL, a, PatternExpr.num(1)), a);
            var zero = new PatternRewriteRule("zero-mul",
                PatternExpr.op(de.regelsuche.ast.BinaryOperator.MUL, PatternExpr.num(0), a), PatternExpr.num(0));
            var result = new EqualitySaturation(List.of(unit, zero), new EqualitySaturation.Config(2, 2000))
                .saturate(graph, root, node -> node.symbol().equals("var:x") ? Integer.MAX_VALUE : 1);
            assertEquals(parser.parseTerm("0"), result.expression());
            assertEquals(1, result.stats().appliedRules().get("zero-mul"));
            assertEquals(0, result.diagnostics().count(SaturationDiagnostics.Outcome.RULE_FAILURE));
            if (fillers == 0) {
                assertTrue(result.provenance().witnesses().getFirst().replay(zero));
                assertEquals(1, result.provenance().nativeWitnessExtractions());
            } else {
                assertEquals(SaturationProvenance.MAX_WITNESSES, result.provenance().witnesses().size());
                assertEquals(1, result.provenance().droppedWitnesses());
                assertEquals(fillers, result.provenance().nativeWitnessExtractions());
            }
        }
    }

    @Test void unavailableWitnessExplanationDoesNotPreventANativeEquality() {
        var graph = new EGraph();
        var zeroId = graph.addExpression(parser.parseTerm("0"));
        var large = graph.addExpression(parser.parseTerm("x"));
        // A small DAG with a finite tree larger than the extractor's integer cost range.
        // Uniform witness extraction must stop before attempting to materialize that tree.
        for (int i = 0; i < 31; i++) large = graph.add(new ENode("op:ADD", List.of(large, large)));
        var root = graph.add(new ENode("op:MUL", List.of(zeroId, large)));
        graph.union(root, graph.addExpression(parser.parseTerm("0 + 0")));
        graph.rebuild();
        var rule = new PatternRewriteRule("zero-mul",
            PatternExpr.op(de.regelsuche.ast.BinaryOperator.MUL, PatternExpr.num(0), a), PatternExpr.num(0));
        var result = new EqualitySaturation(List.of(rule), new EqualitySaturation.Config(1, 100))
            .saturate(graph, root, node -> 1);
        assertEquals(parser.parseTerm("0"), result.expression());
        assertEquals(1, result.stats().totalApplications());
        assertEquals(1, result.provenance().nativeWitnessExtractions());
        assertTrue(result.provenance().witnesses().isEmpty());
        assertEquals(1, result.provenance().droppedWitnesses());
        assertEquals(1, result.diagnostics().count(SaturationDiagnostics.Outcome.RULE_FAILURE));
        assertEquals(SaturationDiagnostics.Stage.EXTRACTION, result.diagnostics().events().getFirst().stage());
        assertTrue(graph.areEquivalent(root, zeroId));
    }

    @Test void alreadyEquivalentNativeMatchesDoNotExtractUnusedWitnesses() {
        var graph = new EGraph();
        var root = graph.addExpression(parser.parseTerm("x0"));
        for (int i = 0; i < 16; i++) {
            var variable = graph.addExpression(parser.parseTerm("x" + i));
            graph.union(variable, graph.addExpression(parser.parseTerm("x" + i + " + 0")));
        }
        graph.rebuild();
        var rule = new PatternRewriteRule("add-zero",
            PatternExpr.op(de.regelsuche.ast.BinaryOperator.ADD, a, PatternExpr.num(0)), a);
        var result = new EqualitySaturation(List.of(rule)).saturate(graph, root, node -> 1);
        assertEquals(0, result.stats().totalApplications());
        assertTrue(result.provenance().witnesses().isEmpty());
        assertEquals(0, result.provenance().droppedWitnesses());
        assertEquals(0, result.provenance().nativeWitnessExtractions());
    }

    @Test void negativeCostsAreRejectedBeforeAnyRewriteMutatesTheGraph() {
        var graph = new EGraph();
        var root = graph.addExpression(parser.parseTerm("x + 0"));
        var rule = new PatternRewriteRule("add-zero",
            PatternExpr.op(de.regelsuche.ast.BinaryOperator.ADD, a, PatternExpr.num(0)), a);
        long version = graph.version();
        assertThrows(IllegalArgumentException.class, () -> new EqualitySaturation(List.of(rule))
            .saturate(graph, root, node -> node.symbol().equals("var:x") ? -1 : 1));
        assertEquals(version, graph.version());
        assertEquals(parser.parseTerm("x + 0"), graph.extract(root, node -> 1));
    }

    @Test void negativeAdditiveCostsFailExplicitly() {
        var graph = new EGraph();
        var root = graph.addExpression(parser.parseTerm("x"));
        assertThrows(IllegalArgumentException.class, () -> graph.extract(root, node -> -1));
    }

    private EqualitySaturation.Result run(String expression, RewriteRule rule) {
        var graph = new EGraph();
        var root = graph.addExpression(parser.parseTerm(expression));
        return new EqualitySaturation(List.of(rule), new EqualitySaturation.Config(3, 100))
            .saturate(graph, root, node -> 1);
    }
}
