package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Established facts discharge core premises; they never prove the resulting Java rewrite. */
class EstablishedPremiseTest {
    private static final Expr X = new VariableExpr("x"), Y = new VariableExpr("y"), D = new VariableExpr("d");
    private static final long MAX_WORK = 2_000_000;
    private final ComputationOptimizer optimizer = new ComputationOptimizer();

    @Test void establishedPositiveDivisorEnablesAnOrdinaryCoreRuleAndReplays() {
        var kind = NumericKind.BIG_INTEGER;
        var source = op(kind, NumericOperation.DIVIDE, op(kind, NumericOperation.MULTIPLY, X, Y), Y);
        var request = request(kind, source, positive("y"));
        var originalFacts = Set.copyOf(request.assumptions());
        var result = optimizer.optimize(request, CancellationToken.NONE);
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, result, result.toString());
        assertEquals(List.of(X), candidate.plan().outputExpressions());
        assertTrue(candidate.cost().estimatedRuntimeImprovement());
        assertTrue(candidate.evidence().proofMethods().contains("EXACT_INTEGER_QUOTIENT_IDENTITY"));
        assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, candidate, CancellationToken.NONE));
        assertEquals(originalFacts, request.assumptions());
        for (long x : new long[] {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE}) {
            var values = values(x, 17, 1);
            assertEquals(ComputationOptimizer.prepare(request.plan()).execute(values), candidate.prepared().execute(values));
        }
        assertThrows(RuntimeChecks.AssumptionGuardFailure.class, () ->
                RuntimeChecks.checked(request, candidate.plan(), candidate.obligations(), values(7, 0, 1)));
    }

    @Test void nonnegativeInputProvesACompositeDivisorForTheExistingCoreRule() {
        var kind = NumericKind.BIG_INTEGER;
        var one = literal(kind, 1);
        var divisor = op(kind, NumericOperation.ADD, Y, one);
        var numerator = op(kind, NumericOperation.MULTIPLY, X, divisor);
        var request = request(kind, op(kind, NumericOperation.DIVIDE, numerator, divisor), nonnegative("y"));
        var expected = request.plan().withOutputs(List.of(X));
        assertInstanceOf(VerificationResult.Verified.class, optimizer.verify(request, expected, CancellationToken.NONE));
        var proposals = generated(request);
        assertTrue(proposals.proposals().stream().anyMatch(p -> p.rule().contains("cancel_division_factor")), proposals.toString());
        var result = optimizer.optimize(request, CancellationToken.NONE);
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, result, result.toString());
        assertTrue(candidate.cost().estimatedRuntimeImprovement());
        assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, candidate, CancellationToken.NONE));
        for (long x : new long[] {0, 1, 2, 37, Long.MAX_VALUE}) {
            var inputs = values(x, 0, 1);
            assertEquals(ComputationOptimizer.prepare(request.plan()).execute(inputs), candidate.prepared().execute(inputs));
        }
    }

    @Test void independentCheckerProvesExactPolynomialDivisibility() {
        var kind = NumericKind.BIG_INTEGER;
        var one = literal(kind, 1);
        var numerator = op(kind, NumericOperation.SUBTRACT, op(kind, NumericOperation.MULTIPLY, X, X), one);
        var divisor = op(kind, NumericOperation.ADD, X, one);
        var request = request(kind, op(kind, NumericOperation.DIVIDE, numerator, divisor), nonnegative("x"));
        var expected = request.plan().withOutputs(List.of(op(kind, NumericOperation.SUBTRACT, X, one)));
        var verified = assertInstanceOf(VerificationResult.Verified.class, optimizer.verify(request, expected, CancellationToken.NONE));
        assertTrue(verified.evidence().proofMethods().contains("EXACT_INTEGER_QUOTIENT_IDENTITY"));
        assertFalse(optimizer.verify(request, request.plan().withOutputs(List.of(X)), CancellationToken.NONE)
                instanceof VerificationResult.Verified);
    }

    @Test void noFactOrAnUnrelatedPositiveInputCannotDischargeTheDivisor() {
        var kind = NumericKind.BIG_INTEGER;
        var source = op(kind, NumericOperation.DIVIDE, op(kind, NumericOperation.MULTIPLY, X, Y), Y);
        for (var facts : List.of(new SemanticAssumption[0], new SemanticAssumption[] {positive("x")},
                new SemanticAssumption[] {nonnegative("y")})) {
            var request = request(kind, source, facts);
            assertFalse(generated(request).proposals().stream().anyMatch(p -> p.rule().contains("cancel_division_factor")));
            assertInstanceOf(OptimizationResult.Unsupported.class, optimizer.optimize(request, CancellationToken.NONE));
        }
    }

    @Test void anInputNamedLikeAnEncodedAtomDoesNotProveAnUnrelatedDivisor() {
        var kind = NumericKind.BIG_INTEGER;
        var source = op(kind, NumericOperation.DIVIDE, op(kind, NumericOperation.MULTIPLY, X, Y), Y);
        var standard = request(kind, source);
        var inputs = new java.util.HashMap<>(standard.plan().inputs());
        inputs.put("atom1", kind.type());
        var plan = new JointComputationPlan(inputs, Map.of(), standard.plan().outputs());
        var facts = new HashSet<>(standard.assumptions());
        facts.add(positive("atom1"));
        facts.add(fact(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, "atom1", ""));
        facts.add(fact(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, "atom1", "64"));
        var request = request(plan, facts, SafetyProfile.PRESERVE_JAVA);
        assertFalse(generated(request).proposals().stream().anyMatch(p -> p.rule().contains("cancel_division_factor")));
        assertInstanceOf(OptimizationResult.Unsupported.class, optimizer.optimize(request, CancellationToken.NONE));
    }

    @Test void positiveFactsDoNotPassThroughOpaqueNarrowingCasts() {
        var cast = JavaExpressions.cast(NumericKind.LONG, NumericKind.INT, Y);
        var source = op(NumericKind.INT, NumericOperation.DIVIDE,
                op(NumericKind.INT, NumericOperation.MULTIPLY, X, cast), cast);
        var plan = new JointComputationPlan(Map.of("x", NumericKind.INT.type(), "y", NumericKind.LONG.type()), Map.of(),
                List.of(new JointComputationPlan.Output("result", NumericKind.INT.type(), source)));
        var request = request(plan, Set.of(positive("y")), SafetyProfile.PRESERVE_JAVA);
        assertFalse(generated(request).proposals().stream().anyMatch(p -> p.rule().contains("cancel_division_factor")));
        assertInstanceOf(OptimizationResult.Unsupported.class, optimizer.optimize(request, CancellationToken.NONE));
        assertThrows(ArithmeticException.class, () -> ComputationOptimizer.prepare(plan).execute(Map.of("x", 7, "y", 1L << 32)));
    }

    @Test void positiveDivisorsDoNotMakeTruncatingDivisionDistributive() {
        var kind = NumericKind.BIG_INTEGER;
        var request = request(kind, op(kind, NumericOperation.DIVIDE, op(kind, NumericOperation.ADD, X, Y), D), positive("d"));
        assertInstanceOf(VerificationResult.Verified.class, optimizer.verify(request, request.plan(), CancellationToken.NONE));
        var wrong = request.plan().withOutputs(List.of(op(kind, NumericOperation.ADD,
                op(kind, NumericOperation.DIVIDE, X, D), op(kind, NumericOperation.DIVIDE, Y, D))));
        assertFalse(optimizer.verify(request, wrong, CancellationToken.NONE) instanceof VerificationResult.Verified);
        var inputs = values(1, 1, 2);
        assertNotEquals(ComputationOptimizer.prepare(request.plan()).execute(inputs), ComputationOptimizer.prepare(wrong).execute(inputs));
    }

    @Test void positiveDivisorsDoNotUndoEarlierTruncation() {
        var kind = NumericKind.BIG_INTEGER;
        var request = request(kind, op(kind, NumericOperation.MULTIPLY, op(kind, NumericOperation.DIVIDE, X, Y), Y), positive("y"));
        assertInstanceOf(VerificationResult.Verified.class, optimizer.verify(request, request.plan(), CancellationToken.NONE));
        assertFalse(optimizer.verify(request, request.plan().withOutputs(List.of(X)), CancellationToken.NONE)
                instanceof VerificationResult.Verified);
    }

    @Test void primitiveWraparoundStillPreventsFactorCancellation() {
        for (var kind : List.of(NumericKind.INT, NumericKind.LONG)) {
            var request = request(kind, op(kind, NumericOperation.DIVIDE, op(kind, NumericOperation.MULTIPLY, X, Y), Y), positive("y"));
            assertInstanceOf(VerificationResult.Verified.class, optimizer.verify(request, request.plan(), CancellationToken.NONE));
            assertFalse(optimizer.verify(request, request.plan().withOutputs(List.of(X)), CancellationToken.NONE)
                    instanceof VerificationResult.Verified);
        }
    }

    @Test void positivePrimitiveFactorsDoNotMakeAnOverflowingProductNonzero() {
        var kind = NumericKind.INT;
        var divisor = op(kind, NumericOperation.MULTIPLY, Y, literal(kind, 4));
        var request = request(kind, op(kind, NumericOperation.DIVIDE,
                op(kind, NumericOperation.MULTIPLY, X, divisor), divisor), positive("y"));
        assertFalse(generated(request).proposals().stream().anyMatch(p -> p.rule().contains("cancel_division_factor")));
        assertInstanceOf(OptimizationResult.Unsupported.class, optimizer.optimize(request, CancellationToken.NONE));
        assertThrows(ArithmeticException.class, () -> ComputationOptimizer.prepare(request.plan()).execute(
                Map.of("x", 7, "y", 1 << 30, "d", 1)));
    }

    @Test void primitiveExactQuotientProofRetainsBothTraceRangeGates() {
        var kind = NumericKind.INT;
        var plain = request(kind, op(kind, NumericOperation.DIVIDE, op(kind, NumericOperation.MULTIPLY, X, Y), Y), positive("y"));
        var request = request(plain.plan(), plain.assumptions(), SafetyProfile.GUARDED_FALLBACK);
        var target = request.plan().withOutputs(List.of(X));
        var proof = assertInstanceOf(VerificationResult.Verified.class, optimizer.verify(request, target, CancellationToken.NONE));
        assertTrue(proof.obligations().checkIntegralRange());
        assertEquals(RuntimeObligations.GuardKind.ORIGINAL_AND_REPLACEMENT_RANGE, proof.obligations().guard());
        assertEquals(Map.of("result", 7), RuntimeChecks.checked(request, target, proof.obligations(), Map.of("x", 7, "y", 3, "d", 1)));
        assertThrows(ArithmeticException.class, () -> RuntimeChecks.checked(request, target, proof.obligations(),
                Map.of("x", Integer.MAX_VALUE, "y", 2, "d", 1)));
    }

    @Test void proofWorkIsIndependentOfEarlierCandidateChecks() {
        var kind = NumericKind.BIG_INTEGER;
        var one = literal(kind, 1);
        var request = request(kind, op(kind, NumericOperation.DIVIDE, X,
                op(kind, NumericOperation.ADD, Y, one)), nonnegative("y"));
        var candidate = request.plan().withOutputs(List.of(op(kind, NumericOperation.DIVIDE, X,
                op(kind, NumericOperation.ADD, one, Y))));
        var checker = new SemanticChecker(request, new VerificationWork(request, CancellationToken.NONE));
        checker.validate();
        var first = checker.check(request.plan(), candidate);
        var second = checker.check(request.plan(), candidate);
        assertTrue(first.accepted());
        assertEquals(first, second, "A proof receipt must not depend on prior search candidates");
    }

    @Test void exactTypedConstantsDischargeSupportedPredicates() {
        var kind = NumericKind.INT;
        var request = request(kind, op(kind, NumericOperation.MULTIPLY, X, literal(kind, 1)));
        for (var assumption : List.of(Assumption.nonZero("1"), Assumption.positive("1"), Assumption.nonNegative("1"))) {
            var generated = new JavaAlgebraCandidates(new VerificationWork(request, CancellationToken.NONE),
                    List.of(conditionalUnitRule(List.of(assumption))), MAX_WORK).generate(request.plan(), 64);
            assertEquals(1, generated.proposals().size(), assumption.toString());
            assertInstanceOf(VerificationResult.Verified.class, optimizer.verify(request,
                    request.plan().withExpression(generated.proposals().getFirst().expression()), CancellationToken.NONE));
        }
    }

    @Test void malformedFreshAndUnknownPremisesFailClosed() {
        var kind = NumericKind.INT;
        var request = request(kind, op(kind, NumericOperation.MULTIPLY, X, literal(kind, 1)));
        for (var assumption : List.of(Assumption.positive("fresh"), Assumption.positive("x"),
                new Assumption(Assumption.Kind.POSITIVE, "1 > 0", List.of("atom0")),
                new Assumption(Assumption.Kind.NON_ZERO, "1 == 0", List.of("1")),
                Assumption.customPredicate("1 > 0", List.of("1")), Assumption.unknown("1"))) {
            var generated = new JavaAlgebraCandidates(new VerificationWork(request, CancellationToken.NONE),
                    List.of(conditionalUnitRule(List.of(assumption))), MAX_WORK).generate(request.plan(), 64);
            assertTrue(generated.proposals().isEmpty(), assumption.toString());
        }
    }

    @Test void cancellationDuringPremiseDischargeIsNotSwallowedAsAnUnknownRule() {
        var request = request(NumericKind.INT, op(NumericKind.INT, NumericOperation.MULTIPLY, X, literal(NumericKind.INT, 1)));
        var cancelled = new AtomicBoolean();
        var rule = new UnitRule() {
            @Override public List<Assumption> assumptions(Expr source) {
                cancelled.set(true);
                return List.of(Assumption.positive("1"));
            }
        };
        var generator = new JavaAlgebraCandidates(new VerificationWork(request, cancelled::get), List.of(rule), MAX_WORK);
        assertTrue(assertThrows(VerificationWork.Stopped.class, () -> generator.generate(request.plan(), 64)).cancelled);
    }

    @Test void nativeExpansionLimitCannotAdmitAnUnfinishedPremiseProof() {
        var request = request(NumericKind.INT, op(NumericKind.INT, NumericOperation.MULTIPLY, X, literal(NumericKind.INT, 1)));
        var generated = new JavaAlgebraCandidates(new VerificationWork(request, CancellationToken.NONE),
                List.of(conditionalUnitRule(List.of(Assumption.positive("1")))), 8).generate(request.plan(), 64);
        assertTrue(generated.proposals().isEmpty());
        assertFalse(generated.complete());
    }

    @Test void aFailedPremiseIndexCannotBecomeAProvedPartialIndexOnRetry() {
        Expr one = literal(NumericKind.BIG_INTEGER, 1), two = literal(NumericKind.BIG_INTEGER, 2);
        Expr three = literal(NumericKind.BIG_INTEGER, 3), huge = JavaExpressions.literal(BigInteger.ONE.shiftLeft(3500));
        Expr left = new BinaryExpr(new BinaryExpr(one, BinaryOperator.ADD, two), BinaryOperator.ADD, three);
        Expr right = new BinaryExpr(one, BinaryOperator.ADD, new BinaryExpr(two, BinaryOperator.ADD, three));
        Expr mathematical = new BinaryExpr(left, BinaryOperator.ADD, new BinaryExpr(huge, BinaryOperator.ADD, right));
        Expr source = op(NumericKind.BIG_INTEGER, NumericOperation.ADD,
                op(NumericKind.BIG_INTEGER, NumericOperation.ADD, op(NumericKind.BIG_INTEGER, NumericOperation.ADD, one, two), three),
                op(NumericKind.BIG_INTEGER, NumericOperation.ADD, huge,
                        op(NumericKind.BIG_INTEGER, NumericOperation.ADD, one, op(NumericKind.BIG_INTEGER, NumericOperation.ADD, two, three))));
        var request = request(NumericKind.BIG_INTEGER, source);
        var rules = new java.util.ArrayList<RewriteRule>();
        for (int index = 0; index < 2; index++) {
            String id = "conditional-swap-" + index;
            rules.add(new UnitRule() {
                @Override public String id() { return id; }
                @Override public boolean matches(Expr expression) { return mathematical.equals(expression); }
                @Override public List<Assumption> assumptions(Expr expression) {
                    return List.of(Assumption.positive("1 + 2 + 3"));
                }
                @Override public Expr apply(Expr expression) {
                    var binary = (BinaryExpr) expression;
                    return new BinaryExpr(binary.right(), BinaryOperator.ADD, binary.left());
                }
            });
        }
        var generated = new JavaAlgebraCandidates(new VerificationWork(request, CancellationToken.NONE), rules, MAX_WORK)
                .generate(request.plan(), 64);
        assertTrue(generated.proposals().isEmpty(), "an interrupted index must never expose only the first unambiguous prefix");
    }

    private static RewriteRule conditionalUnitRule(List<Assumption> assumptions) {
        return new UnitRule() { @Override public List<Assumption> assumptions(Expr source) { return assumptions; } };
    }
    private static class UnitRule implements RewriteRule {
        @Override public String id() { return "conditional-unit"; }
        @Override public RewriteKind kind() { return RewriteKind.SIMPLIFY; }
        @Override public boolean mayIncreaseComplexity() { return false; }
        @Override public int estimatedCostDelta() { return -1; }
        @Override public boolean isEquivalencePreservingByConstruction() { return true; }
        @Override public boolean mayEmitAssumptions() { return true; }
        @Override public boolean matches(Expr expression) {
            return expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.MUL
                    && binary.right() instanceof NumberExpr number && number.value().equalsInteger(1);
        }
        @Override public Expr apply(Expr expression) { return ((BinaryExpr) expression).left(); }
    }
    private static de.regelsuche.search.program.JointPlanSearch.Generation generated(OptimizationRequest request) {
        return new JavaCandidateGenerator(request, new VerificationWork(request, CancellationToken.NONE)).generate(request.plan(), 64);
    }
    private static Expr op(NumericKind kind, NumericOperation operation, Expr... args) {
        return JavaExpressions.operation(kind, operation, args);
    }
    private static Expr literal(NumericKind kind, long value) {
        return switch (kind) {
            case BIG_INTEGER -> JavaExpressions.literal(BigInteger.valueOf(value));
            case LONG -> JavaExpressions.literal(value);
            default -> JavaExpressions.literal((int) value);
        };
    }
    private static SemanticAssumption fact(SemanticAssumption.Kind kind, String subject, String parameter) {
        return new SemanticAssumption(kind, subject, parameter, "established source fact");
    }
    private static SemanticAssumption positive(String subject) { return fact(SemanticAssumption.Kind.POSITIVE, subject, ""); }
    private static SemanticAssumption nonnegative(String subject) { return fact(SemanticAssumption.Kind.NON_NEGATIVE, subject, ""); }
    private static OptimizationRequest request(NumericKind kind, Expr source, SemanticAssumption... established) {
        var plan = new JointComputationPlan(Map.of("x", kind.type(), "y", kind.type(), "d", kind.type()), Map.of(),
                List.of(new JointComputationPlan.Output("result", kind.type(), source)));
        var facts = new HashSet<>(List.of(established));
        if (kind == NumericKind.BIG_INTEGER) for (var name : plan.inputs().keySet()) {
            facts.add(fact(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, name, ""));
            facts.add(fact(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, name, "64"));
        }
        return request(plan, facts, SafetyProfile.PRESERVE_JAVA);
    }
    private static OptimizationRequest request(JointComputationPlan plan, Set<SemanticAssumption> facts, SafetyProfile profile) {
        var kinds = new HashSet<NumericKind>();
        plan.inputs().values().forEach(type -> kinds.add(NumericKind.fromType(type)));
        plan.outputs().forEach(output -> kinds.add(NumericKind.fromType(output.type())));
        return new OptimizationRequest(plan, SourceEvaluationTrace.fromPlan(plan), kinds, ComputationOptimizer.SEMANTICS_REVISION,
                facts, profile, OptimizationGoal.LOWER_ESTIMATED_RUNTIME,
                new OptimizationBudget(MAX_WORK, 20_000, 64, 10_000), CheckedPolicy.NONE);
    }
    private static Map<String, Object> values(long x, long y, long d) {
        return Map.of("x", BigInteger.valueOf(x), "y", BigInteger.valueOf(y), "d", BigInteger.valueOf(d));
    }
}
