package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.util.*;
import org.junit.jupiter.api.Test;

class SearchDerivationTest {
    @Test void originalSourceProducesAContiguousReplayablePathWithoutATarget() {
        for (NumericKind kind : List.of(NumericKind.INT, NumericKind.LONG)) {
            var f = fixture(kind);
            var trace = trace(f);
            assertFalse(trace.steps().isEmpty());
            assertEquals(f.candidate.evidence(), trace.evidence());
            Expr cursor = f.request.plan().expression();
            for (var step : trace.steps()) {
                assertEquals(cursor, step.before());
                assertFalse(step.rule().isBlank());
                var before = f.request.plan().withExpression(step.before());
                var after = f.request.plan().withExpression(step.after());
                assertInstanceOf(VerificationResult.Verified.class, new ComputationOptimizer().verify(request(before, kind), after, CancellationToken.NONE));
                cursor = step.after();
            }
            assertEquals(f.candidate.plan().expression(), cursor);
            assertInstanceOf(VerificationResult.Verified.class, new ComputationOptimizer().reverify(f.request, f.candidate, CancellationToken.NONE));
            System.out.println("ACTUAL_SEARCH_DERIVATION " + kind + " " + trace.steps());
        }
    }
    @Test void missingPathCannotExplainAnActuallyChangedPlan() {
        var f = fixture(NumericKind.INT);
        reject(f, new SearchDerivation(f.candidate.evidence(), 64, List.of()));
    }
    @Test void ruleLabelMustBeAnActualGeneratedEdgeNotAFabricatedExplanation() {
        var f = fixture(NumericKind.INT);
        var steps = new ArrayList<>(trace(f).steps());
        var first = steps.getFirst();
        steps.set(0, new SearchDerivation.Step("fabricated-production-demo", first.before(), first.after()));
        reject(f, new SearchDerivation(f.candidate.evidence(), 64, steps));
    }
    @Test void discontinuousOrChangedEndpointsAreRejected() {
        var f = fixture(NumericKind.INT);
        var original = trace(f);
        var steps = new ArrayList<>(original.steps());
        var first = steps.getFirst();
        steps.set(0, new SearchDerivation.Step(first.rule(), first.after(), first.after()));
        reject(f, new SearchDerivation(f.candidate.evidence(), 64, steps));
        steps = new ArrayList<>(original.steps());
        var last = steps.getLast();
        Expr wrong = f.request.plan().withOutputs(List.of(JavaExpressions.literal(123))).expression();
        steps.set(steps.size() - 1, new SearchDerivation.Step(last.rule(), last.before(), wrong));
        reject(f, new SearchDerivation(f.candidate.evidence(), 64, steps));
    }
    @Test void sameEndpointsCannotHideAForgedIntermediate() {
        var f = fixture(NumericKind.INT);
        Expr wrong = f.request.plan().withOutputs(List.of(JavaExpressions.literal(123))).expression();
        String rule = trace(f).steps().getFirst().rule();
        reject(f, new SearchDerivation(f.candidate.evidence(), 64, List.of(
                new SearchDerivation.Step(rule, f.request.plan().expression(), wrong),
                new SearchDerivation.Step(rule, wrong, f.candidate.plan().expression()))));
    }
    @Test void legacyCandidateDoesNotInventASearchHistory() {
        var f = fixture(NumericKind.INT); var c = f.candidate;
        var legacy = new OptimizationResult.Candidate(c.plan(), c.prepared(), c.evidence(), c.obligations(), c.cost(), c.searchCompletion(), c.work());
        assertTrue(legacy.derivation().isEmpty());
        assertTrue(ComputationExplanations.describe(f.request, legacy, CancellationToken.NONE).explanation().isPresent());
    }
    @Test void derivationCollectionsAreDefensiveAndBounded() {
        var f = fixture(NumericKind.INT);
        var list = new ArrayList<>(trace(f).steps());
        var copy = new SearchDerivation(f.candidate.evidence(), 64, list);
        list.clear(); assertFalse(copy.steps().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> copy.steps().clear());
        assertThrows(IllegalArgumentException.class, () -> new SearchDerivation(f.candidate.evidence(), 64,
                Collections.nCopies(SearchDerivation.MAX_STEPS + 1, copy.steps().getFirst())));
    }
    @Test void cancellationAndInsufficientBudgetCannotReturnPartialExplanation() {
        var f = fixture(NumericKind.INT);
        assertTrue(ComputationExplanations.describe(f.request, f.candidate, () -> true).explanation().isEmpty());
        var r = f.request;
        var tiny = new OptimizationRequest(r.plan(), r.sourceTrace(), r.selectedKinds(), r.semanticsRevision(), r.assumptions(),
                r.safetyProfile(), r.goal(), new OptimizationBudget(1, 100, 64, 5000), r.checkedPolicy());
        var result = ComputationExplanations.describe(tiny, f.candidate, CancellationToken.NONE);
        assertInstanceOf(VerificationResult.BudgetExceeded.class, result.verification());
        assertTrue(result.explanation().isEmpty());
    }
    private static SearchDerivation trace(Fixture f) {
        return f.candidate.derivation().orElseThrow(() -> new AssertionError("The retained search witness is lost at the SDK boundary"));
    }
    private static void reject(Fixture f, SearchDerivation trace) {
        var c = f.candidate;
        var changed = new OptimizationResult.Candidate(c.plan(), c.prepared(), c.evidence(), c.obligations(), c.cost(), c.searchCompletion(), c.work(), Optional.of(trace));
        var result = ComputationExplanations.describe(f.request, changed, CancellationToken.NONE);
        assertFalse(result.verification() instanceof VerificationResult.Verified, result.toString());
        assertTrue(result.explanation().isEmpty());
    }
    static Fixture fixture(NumericKind kind) {
        Expr x = new VariableExpr("distance"), a = new VariableExpr("scale"), b = new VariableExpr("bias");
        Expr expression = op(kind, NumericOperation.SUBTRACT,
                op(kind, NumericOperation.MULTIPLY, x, op(kind, NumericOperation.ADD, a, b)),
                op(kind, NumericOperation.MULTIPLY, x, a));
        var plan = new JointComputationPlan(Map.of("distance", kind.type(), "scale", kind.type(), "bias", kind.type()), Map.of(),
                List.of(new JointComputationPlan.Output("out", kind.type(), expression)));
        var request = request(plan, kind);
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, new ComputationOptimizer().optimize(request, CancellationToken.NONE));
        return new Fixture(request, candidate);
    }
    private static OptimizationRequest request(JointComputationPlan plan, NumericKind kind) {
        return new OptimizationRequest(plan, SourceEvaluationTrace.fromPlan(plan), Set.of(kind), ComputationOptimizer.SEMANTICS_REVISION,
                Set.of(), SafetyProfile.PRESERVE_JAVA, OptimizationGoal.READABILITY, new OptimizationBudget(2_000_000, 20_000, 64, 5000), CheckedPolicy.NONE);
    }
    private static Expr op(NumericKind kind, NumericOperation operation, Expr... operands) {
        return JavaExpressions.operation(kind, operation, operands);
    }
    record Fixture(OptimizationRequest request, OptimizationResult.Candidate candidate) {}
}
