package de.regelsuche.egraph;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.transform.EquivalenceAwarePatternMatcher;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class EqualitySaturationMatchLimitTest {
    private final ExpressionParser parser = new ExpressionParser();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void commutativeOperandLimitCannotCertifyAFixedPoint(boolean dirtyWorklist) {
        PatternExpr target = placeholderSum(8);
        PatternExpr source = PatternExpr.op(ADD, target, PatternExpr.num(0));
        var before = parser.parseTerm("x1 + x2 + x3 + x4 + x5 + x6 + x7 + x8 + 0");
        var rule = new PatternRewriteRule("remove-zero-from-nine-operands", source, target,
            RecognitionProfile.arithmeticAc());

        // The rewrite exists; the opted-in AC matcher cannot complete its own attempt.
        assertTrue(EquivalenceAwarePatternMatcher.matchDetailed(source, before, Map.of(),
            RecognitionProfile.exact()).matched());
        var attempt = EquivalenceAwarePatternMatcher.matchDetailed(source, before, Map.of(),
            rule.recognitionProfile());
        assertTrue(attempt.inconclusive());
        assertEquals("COMMUTATIVE_OPERAND_LIMIT", attempt.limitCode());

        var graph = new EGraph();
        var root = graph.addExpression(before);
        var result = new EqualitySaturation(List.of(rule), new EqualitySaturation.Config(3, 100, dirtyWorklist))
            .saturate(graph, root, node -> 1);

        assertAll(
            () -> assertFalse(result.stats().saturated(),
                () -> "An owned matcher limit was reported as saturated: " + result.stats()
                    + "; diagnostics=" + result.diagnostics()),
            () -> assertNotEquals(SaturationStats.Reason.FIX_POINT, result.stats().stopReason())
        );
        assertInconclusive(result, rule.id(), root, "COMMUTATIVE_OPERAND_LIMIT");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void commutativeBacktrackingLimitCannotCertifyAFixedPoint(boolean dirtyWorklist) {
        PatternExpr target = new PatternExpr.Function("f", List.of(placeholderSum(7), PatternExpr.var("a1")));
        PatternExpr source = PatternExpr.op(ADD, target, PatternExpr.num(0));
        var before = parser.parseTerm("f(x1 + x2 + x3 + x4 + x5 + x6 + x7, x7) + 0");
        var rule = new PatternRewriteRule("remove-zero-after-ac-binding", source, target,
            RecognitionProfile.arithmeticAc());

        // The repeated a1 constraint needs a late permutation under the real default limit.
        var attempt = EquivalenceAwarePatternMatcher.matchDetailed(source, before, Map.of(),
            rule.recognitionProfile());
        assertTrue(attempt.inconclusive());
        assertEquals("COMMUTATIVE_BACKTRACKING_LIMIT", attempt.limitCode());
        assertEquals(10_000, attempt.visitedBranches());
        assertTrue(EquivalenceAwarePatternMatcher.matchDetailed(source, before, Map.of(),
            rule.recognitionProfile(), 20_000).matched());

        var graph = new EGraph();
        var root = graph.addExpression(before);
        var result = new EqualitySaturation(List.of(rule), new EqualitySaturation.Config(3, 100, dirtyWorklist))
            .saturate(graph, root, node -> 1);

        assertAll(
            () -> assertFalse(result.stats().saturated(),
                () -> "An owned matcher limit was reported as saturated: " + result.stats()
                    + "; diagnostics=" + result.diagnostics()),
            () -> assertNotEquals(SaturationStats.Reason.FIX_POINT, result.stats().stopReason())
        );
        assertInconclusive(result, rule.id(), root, "COMMUTATIVE_BACKTRACKING_LIMIT");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void independentProgressCannotEraseAnEarlierIncompleteMatch(boolean dirtyWorklist) {
        var graph = new EGraph();
        var root = graph.addExpression(operandInput());
        var independent = graph.addExpression(parser.parseTerm("g(y) + 0"));
        var marker = new PatternExpr.Function("g", List.of(PatternExpr.var("y")));
        var useful = new PatternRewriteRule("remove-y-zero",
            PatternExpr.op(ADD, marker, PatternExpr.num(0)), marker);

        var result = new EqualitySaturation(List.of(operandLimitedRule("limited"), useful),
            new EqualitySaturation.Config(3, 100, dirtyWorklist)).saturate(graph, root, node -> 1);

        assertTrue(graph.areEquivalent(independent, graph.addExpression(parser.parseTerm("g(y)"))));
        assertEquals(1, result.stats().appliedRules().get("remove-y-zero"));
        assertEquals(2, result.stats().iterations());
        assertFalse(result.stats().saturated());
        assertEquals(SaturationStats.Reason.MATCHER_LIMIT, result.stats().stopReason());
        assertTrue(result.diagnostics().count(SaturationDiagnostics.Outcome.MATCH_INCONCLUSIVE) > 0);
        assertEquals(0, result.diagnostics().count(SaturationDiagnostics.Outcome.RULE_FAILURE));
    }

    @Test
    void inconclusiveCountsSurviveAFullEventBuffer() {
        var graph = new EGraph();
        var root = graph.addExpression(operandInput());
        var rules = new ArrayList<RewriteRule>();
        for (int i = 0; i < SaturationDiagnostics.MAX_EVENTS; i++) {
            rules.add(new PatternRewriteRule("rejected-" + i, PatternExpr.var("a"), PatternExpr.var("a"),
                RewriteKind.NORMALIZE, false, 0, false));
        }
        rules.add(operandLimitedRule("limited"));

        var result = new EqualitySaturation(rules).saturate(graph, root, node -> 1);

        assertEquals(SaturationDiagnostics.MAX_EVENTS, result.diagnostics().events().size());
        assertTrue(result.diagnostics().events().stream().noneMatch(event ->
            event.outcome() == SaturationDiagnostics.Outcome.MATCH_INCONCLUSIVE));
        assertEquals(1, result.diagnostics().count(SaturationDiagnostics.Outcome.MATCH_INCONCLUSIVE));
        assertEquals(1, result.diagnostics().droppedEvents());
        assertFalse(result.stats().saturated());
        assertEquals(SaturationStats.Reason.MATCHER_LIMIT, result.stats().stopReason());
    }

    @Test
    void actualRuleFailuresRetainPrecedenceOverMatcherLimits() {
        var graph = new EGraph();
        var root = graph.addExpression(operandInput());
        var broken = new PatternRewriteRule("broken", PatternExpr.var("a"), PatternExpr.var("a")) {
            @Override public boolean matches(Expr expression) { throw new IllegalStateException("broken callback"); }
        };

        var result = new EqualitySaturation(List.of(operandLimitedRule("limited"), broken))
            .saturate(graph, root, node -> 1);

        assertFalse(result.stats().saturated());
        assertEquals(SaturationStats.Reason.RULE_FAILURES, result.stats().stopReason());
        assertEquals(1, result.diagnostics().count(SaturationDiagnostics.Outcome.MATCH_INCONCLUSIVE));
        assertTrue(result.diagnostics().count(SaturationDiagnostics.Outcome.RULE_FAILURE) > 0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void graphBudgetsRetainPrecedenceAndMatchingDiagnostics(boolean nodeBudget) {
        var graph = new EGraph();
        var root = graph.addExpression(operandInput());
        graph.addExpression(parser.parseTerm("g(y)"));
        var marker = new PatternExpr.Function("g", List.of(PatternExpr.var("y")));
        var useful = new PatternRewriteRule("add-y-zero", marker, PatternExpr.op(ADD, marker, PatternExpr.num(0)));
        var config = new EqualitySaturation.Config(nodeBudget ? 3 : 1,
            nodeBudget ? graph.nodeCount() + 1 : 100);

        var result = new EqualitySaturation(List.of(operandLimitedRule("limited"), useful), config)
            .saturate(graph, root, node -> 1);

        assertEquals(1, result.stats().totalApplications());
        assertFalse(result.stats().saturated());
        assertEquals(nodeBudget ? SaturationStats.Reason.NODE_BUDGET : SaturationStats.Reason.ITERATION_BUDGET,
            result.stats().stopReason());
        assertEquals(1, result.diagnostics().count(SaturationDiagnostics.Outcome.MATCH_INCONCLUSIVE));
        assertEquals("COMMUTATIVE_OPERAND_LIMIT", result.diagnostics().events().getFirst().message());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void subclassesAndCustomRulesKeepTheirCallbackOrder(boolean subclass) {
        var callbacks = new ArrayList<String>();
        var after = parser.parseTerm("x + 0");
        RewriteRule custom = new RewriteRule() {
            @Override public String id() { return "custom"; }
            @Override public RewriteKind kind() { return RewriteKind.NORMALIZE; }
            @Override public boolean mayIncreaseComplexity() { return true; }
            @Override public int estimatedCostDelta() { return 1; }
            @Override public boolean isEquivalencePreservingByConstruction() { callbacks.add("eligible"); return true; }
            @Override public boolean matches(Expr expression) { callbacks.add("matches"); return true; }
            @Override public List<Assumption> assumptions(Expr expression) { callbacks.add("assumptions"); return List.of(); }
            @Override public Expr apply(Expr expression) { callbacks.add("apply"); return after; }
        };
        // This declared pattern does not match x. Overridden callbacks are the contract.
        var pattern = operandLimitedRule("ignored-pattern");
        RewriteRule rule = subclass ? new PatternRewriteRule("custom", pattern.source(), pattern.target(),
                RecognitionProfile.arithmeticAc()) {
            @Override public boolean isEquivalencePreservingByConstruction() { return custom.isEquivalencePreservingByConstruction(); }
            @Override public boolean matches(Expr expression) { return custom.matches(expression); }
            @Override public List<Assumption> assumptions(Expr expression) { return custom.assumptions(expression); }
            @Override public Expr apply(Expr expression) { return custom.apply(expression); }
        } : custom;
        var graph = new EGraph();
        var root = graph.addExpression(parser.parseTerm("x"));

        var result = new EqualitySaturation(List.of(rule), new EqualitySaturation.Config(1, 100))
            .saturate(graph, root, node -> 1);

        assertEquals(List.of("eligible", "matches", "assumptions", "apply"), callbacks);
        assertEquals(1, result.stats().totalApplications());
        assertTrue(graph.areEquivalent(root, graph.addExpression(after)));
        assertEquals(0, result.diagnostics().count(SaturationDiagnostics.Outcome.MATCH_INCONCLUSIVE));
    }

    @Test
    void opaqueBooleanMissDoesNotInventAnOwnedLimitDiagnostic() {
        var pattern = operandLimitedRule("opaque");
        var opaque = new PatternRewriteRule(pattern.id(), pattern.source(), pattern.target(),
            pattern.recognitionProfile()) { };
        var graph = new EGraph();
        var root = graph.addExpression(operandInput());

        var result = new EqualitySaturation(List.of(opaque)).saturate(graph, root, node -> 1);

        assertTrue(result.stats().saturated());
        assertEquals(SaturationStats.Reason.FIX_POINT, result.stats().stopReason());
        assertEquals(0, result.diagnostics().count(SaturationDiagnostics.Outcome.MATCH_INCONCLUSIVE));
        assertTrue(result.diagnostics().events().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"6, z, NOT_MATCHED", "7, x6, MATCHED", "7, x7, INCONCLUSIVE"})
    void completedBranchesAreDebitedOnceForEveryReturnedOutcome(int operands, String repeated,
            EquivalenceAwarePatternMatcher.AttemptStatus status) {
        var rule = branchRule(operands);
        var before = branchInput(operands, repeated);
        var expected = EquivalenceAwarePatternMatcher.matchDetailed(rule.source(), before, Map.of(), rule.recognitionProfile());
        assertEquals(status, expected.status());
        // These branch totals exceed every structural debit in these small ASTs.
        assertTrue(expected.visitedBranches() > 1_000);
        var observation = new BranchObservation(expected.visitedBranches());
        var graph = new EGraph();
        var root = graph.addExpression(before);
        EqualitySaturation.Result result;

        try (var scope = RetainedOperation.open(observation)) {
            result = new EqualitySaturation(List.of(rule), new EqualitySaturation.Config(1, 100))
                .saturate(graph, root, node -> 1);
        }

        assertEquals(1, observation.branchDebits);
        assertEquals(status == EquivalenceAwarePatternMatcher.AttemptStatus.MATCHED ? 1 : 0,
            result.stats().totalApplications());
        assertEquals(status == EquivalenceAwarePatternMatcher.AttemptStatus.INCONCLUSIVE ? 1 : 0,
            result.diagnostics().count(SaturationDiagnostics.Outcome.MATCH_INCONCLUSIVE));
        if (status == EquivalenceAwarePatternMatcher.AttemptStatus.NOT_MATCHED) {
            assertTrue(result.stats().saturated());
            assertEquals(SaturationStats.Reason.FIX_POINT, result.stats().stopReason());
        } else if (status == EquivalenceAwarePatternMatcher.AttemptStatus.MATCHED) {
            assertEquals(parser.parseTerm("f(x6 + x1 + x2 + x3 + x4 + x5 + x7, x6)"),
                result.provenance().witnesses().getFirst().after());
            assertTrue(result.provenance().witnesses().getFirst().replay(rule));
            assertEquals(0, result.provenance().nativeWitnessExtractions());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void branchDebitFailurePrecedesStatusHandlingAndIsNotPaidAgain(boolean matched) {
        var rule = branchRule(7);
        var before = branchInput(7, matched ? "x6" : "x7");
        var expected = EquivalenceAwarePatternMatcher.matchDetailed(rule.source(), before, Map.of(), rule.recognitionProfile());
        var observation = new BranchObservation(expected.visitedBranches());
        observation.abortDebit = true;
        var graph = new EGraph();
        var root = graph.addExpression(before);
        EqualitySaturation.Result result;

        try (var scope = RetainedOperation.open(observation)) {
            result = new EqualitySaturation(List.of(rule)).saturate(graph, root, node -> 1);
        }

        assertEquals(1, observation.branchDebits);
        assertEquals(0, result.stats().totalApplications());
        assertEquals(0, result.diagnostics().count(SaturationDiagnostics.Outcome.MATCH_INCONCLUSIVE));
        assertMatcherObservationFailure(result);
    }

    @Test
    void matcherFailureBeforeReturnSettlesItsOwnBranchesOnce() {
        var rule = branchRule(7);
        var before = branchInput(7, "x7");
        var observation = new BranchObservation(10_000);
        observation.abortPublication = true;
        var graph = new EGraph();
        var root = graph.addExpression(before);
        EqualitySaturation.Result result;

        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            result = new EqualitySaturation(List.of(rule)).saturate(graph, root, node -> 1);
        }

        assertTrue(observation.publicationAborted);
        assertEquals(1, observation.branchDebits);
        assertEquals(0, result.diagnostics().count(SaturationDiagnostics.Outcome.MATCH_INCONCLUSIVE));
        assertMatcherObservationFailure(result);
    }

    private static void assertMatcherObservationFailure(EqualitySaturation.Result result) {
        assertFalse(result.stats().saturated());
        assertEquals(SaturationStats.Reason.RULE_FAILURES, result.stats().stopReason());
        assertEquals(1, result.diagnostics().count(SaturationDiagnostics.Outcome.RULE_FAILURE));
        var event = result.diagnostics().events().getFirst();
        assertEquals(SaturationDiagnostics.Stage.MATCHES, event.stage());
        assertEquals(IllegalStateException.class.getName(), event.exceptionType());
        assertEquals("observation aborted", event.message());
    }

    private static void assertInconclusive(EqualitySaturation.Result result, String rule, EClassId root, String code) {
        assertEquals(SaturationStats.Reason.MATCHER_LIMIT, result.stats().stopReason());
        assertEquals(1, result.diagnostics().count(SaturationDiagnostics.Outcome.MATCH_INCONCLUSIVE));
        assertEquals(0, result.diagnostics().count(SaturationDiagnostics.Outcome.RULE_FAILURE));
        assertEquals(List.of(new SaturationDiagnostics.Event(rule, root,
            SaturationDiagnostics.Outcome.MATCH_INCONCLUSIVE, SaturationDiagnostics.Stage.MATCHES, "", code)),
            result.diagnostics().events());
    }

    private Expr operandInput() {
        return parser.parseTerm("x1 + x2 + x3 + x4 + x5 + x6 + x7 + x8 + 0");
    }

    private static PatternRewriteRule operandLimitedRule(String id) {
        var target = placeholderSum(8);
        return new PatternRewriteRule(id, PatternExpr.op(ADD, target, PatternExpr.num(0)), target,
            RecognitionProfile.arithmeticAc());
    }

    private static PatternRewriteRule branchRule(int operands) {
        var target = new PatternExpr.Function("f", List.of(placeholderSum(operands), PatternExpr.var("a1")));
        return new PatternRewriteRule("remove-zero-after-ac-binding", PatternExpr.op(ADD, target, PatternExpr.num(0)),
            target, RecognitionProfile.arithmeticAc());
    }

    private Expr branchInput(int operands, String repeated) {
        String sum = IntStream.rangeClosed(1, operands).mapToObj(i -> "x" + i).collect(Collectors.joining(" + "));
        return parser.parseTerm("f(" + sum + ", " + repeated + ") + 0");
    }

    private static final class BranchObservation implements RetainedOperation.Sink {
        final int branches;
        int branchDebits;
        boolean abortDebit, abortPublication, publicationAborted;
        RetainedOperation scope;

        BranchObservation(int branches) { this.branches = branches; }
        @Override public void executionWork(long units) {
            if (units == branches) {
                branchDebits++;
                if (abortDebit) throw new IllegalStateException("observation aborted");
            }
            if (abortPublication && !publicationAborted && scope != null && hasPublishedAttempt()) {
                publicationAborted = true;
                throw new IllegalStateException("observation aborted");
            }
        }
        @Override public void validationWork(long units) { }
        @Override public void checkpoint() { }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); }

        private boolean hasPublishedAttempt() {
            var pending = new ArrayDeque<Object>();
            var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.add(value); }
                @Override public void requireExact(Object value, Class<?> type) { assertEquals(type, value.getClass()); }
            };
            scope.retainedReferences(visitor);
            while (!pending.isEmpty()) {
                Object value = pending.remove();
                if (!seen.add(value)) continue;
                if (value instanceof EquivalenceAwarePatternMatcher.MatchAttempt attempt
                        && attempt.visitedBranches() == branches) return true;
                if (value instanceof RetainedOperation.Frame frame) frame.retainedReferences(visitor);
                else if (value instanceof Object[] array) {
                    for (Object item : array) visitor.reference(item);
                }
            }
            return false;
        }
    }

    private static PatternExpr placeholderSum(int count) {
        PatternExpr sum = PatternExpr.var("a1");
        for (int i = 2; i <= count; i++) {
            sum = PatternExpr.op(ADD, sum, PatternExpr.var("a" + i));
        }
        return sum;
    }
}
