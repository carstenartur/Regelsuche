package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.search.program.JointComputationPlan.Output;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReviewFeedbackRegressionTest {
    private final ComputationOptimizer optimizer = new ComputationOptimizer();
    private static final Expr X = new VariableExpr("x");

    @Test
    void sourceTraceMustPreserveDuplicateOperationOccurrences() {
        Expr repeated = JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, X, JavaExpressions.literal(1));
        Expr output = JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, repeated, repeated);
        var plan = plan(NumericKind.INT, output);
        var completeTrace = SourceEvaluationTrace.fromPlan(plan);
        var tamperedTrace = new SourceEvaluationTrace(List.of(
            completeTrace.occurrences().getFirst(), completeTrace.occurrences().getLast()));
        var request = request(plan, tamperedTrace, Set.of(), SafetyProfile.PRESERVE_JAVA, Set.of(NumericKind.INT));

        assertInstanceOf(VerificationResult.Unsupported.class,
            optimizer.verify(request, plan, CancellationToken.NONE));
    }

    @Test
    void guardedFallbackSelectsOriginalPlanWhenBigIntegerBoundFails() {
        var assumptionSet = Set.of(
            new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, "x", "", "BigInteger input"),
            new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, "x", "4", "captured input bound"));
        var plan = plan(NumericKind.BIG_INTEGER,
            JavaExpressions.operation(NumericKind.BIG_INTEGER, NumericOperation.ADD, X, JavaExpressions.literal(BigInteger.ZERO)));
        var request = request(plan, SourceEvaluationTrace.fromPlan(plan), assumptionSet,
            SafetyProfile.GUARDED_FALLBACK, Set.of(NumericKind.BIG_INTEGER));
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class,
            optimizer.optimize(request, CancellationToken.NONE));

        assertEquals(BigInteger.valueOf(32),
            optimizer.evaluateGuarded(request, candidate, Map.of("x", BigInteger.valueOf(32))).get("out"));
        assertThrows(IllegalArgumentException.class,
            () -> optimizer.evaluateGuarded(request, candidate, Map.of("x", 32)));
    }

    @Test
    void evidenceHashUsesUtf8LengthAndRejectsMalformedSurrogates() {
        String kind = SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS.name();
        var valid = Set.of(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS,
            "x", "", "😀"));
        String canonical = kind.getBytes(StandardCharsets.UTF_8).length + ":" + kind + "1:x0:4:😀";
        assertEquals(EvidenceHashes.hash(canonical), EvidenceHashes.assumptions(valid));

        var malformed = Set.of(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS,
            "x", "", "\uD800"));
        assertThrows(IllegalArgumentException.class, () -> EvidenceHashes.assumptions(malformed));
    }

    @Test
    void localProposalAtCandidateCapMarksGenerationIncomplete() {
        Expr expression = JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, X, JavaExpressions.literal(0));
        var plan = plan(NumericKind.INT, expression);
        var request = request(plan, SourceEvaluationTrace.fromPlan(plan), Set.of(),
            SafetyProfile.PRESERVE_JAVA, Set.of(NumericKind.INT));
        var generator = new JavaCandidateGenerator(request, new VerificationWork(request, CancellationToken.NONE));

        var generated = generator.generate(plan, 1);

        assertEquals(1, generated.proposals().size());
        assertFalse(generated.complete());
    }

    private static JointComputationPlan plan(NumericKind kind, Expr expression) {
        return new JointComputationPlan(Map.of("x", kind.type()), Map.of(),
            List.of(new Output("out", kind.type(), expression)));
    }

    private static OptimizationRequest request(JointComputationPlan plan, SourceEvaluationTrace trace,
            Set<SemanticAssumption> assumptions, SafetyProfile profile, Set<NumericKind> kinds) {
        return new OptimizationRequest(plan, trace, kinds, ComputationOptimizer.SEMANTICS_REVISION,
            assumptions, profile, OptimizationGoal.READABILITY, OptimizationBudget.DEFAULT, CheckedPolicy.NONE);
    }
}
