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
        var originalRequest = request(plan, completeTrace, Set.of(), SafetyProfile.PRESERVE_JAVA, Set.of(NumericKind.INT));
        var changedRequest = request(plan, tamperedTrace, Set.of(), SafetyProfile.PRESERVE_JAVA, Set.of(NumericKind.INT));
        var proof = assertInstanceOf(VerificationResult.Verified.class,
            optimizer.verify(originalRequest, plan, CancellationToken.NONE));
        assertEquals(3, proof.obligations().originalTrace().occurrences().size());
        assertEquals(completeTrace, proof.obligations().originalTrace());
        var prepared = ComputationOptimizer.prepare(plan);
        var candidate = new OptimizationResult.Candidate(plan, prepared, proof.evidence(), proof.obligations(),
            new OptimizationResult.CostAssessment(1, 1, 0, 0, prepared.cost(), prepared.cost(), false),
            OptimizationResult.SearchCompletion.EXHAUSTED_BOUNDED_SPACE, 1);
        assertInstanceOf(VerificationResult.Verified.class,
            optimizer.reverify(originalRequest, candidate, CancellationToken.NONE));

        // The shorter trace could describe sum=x+1; result=sum+sum. The value DAG
        // alone cannot distinguish that program from (x+1)+(x+1). Only evidence
        // bound to the actual original trace can authorize reuse of a candidate.
        assertInstanceOf(VerificationResult.Verified.class,
            optimizer.verify(changedRequest, plan, CancellationToken.NONE));
        assertNotEquals(EvidenceHashes.trace(completeTrace), EvidenceHashes.trace(tamperedTrace));
        assertInstanceOf(VerificationResult.Unsupported.class,
            optimizer.reverify(changedRequest, candidate, CancellationToken.NONE));
    }

    @Test
    void duplicateEvaluationsRemainInEstimatedSourceCosts() {
        Expr plusZero = JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, X, JavaExpressions.literal(0));
        Expr output = JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, plusZero, plusZero);
        var plan = plan(NumericKind.INT, output);
        var duplicateTrace = SourceEvaluationTrace.fromPlan(plan);
        var sharedTrace = new SourceEvaluationTrace(List.of(
            duplicateTrace.occurrences().getFirst(), duplicateTrace.occurrences().getLast()));
        var duplicateRequest = request(plan, duplicateTrace, Set.of(), SafetyProfile.PRESERVE_JAVA, Set.of(NumericKind.INT));
        var sharedRequest = request(plan, sharedTrace, Set.of(), SafetyProfile.PRESERVE_JAVA, Set.of(NumericKind.INT));
        var duplicate = assertInstanceOf(OptimizationResult.Candidate.class,
            optimizer.optimize(duplicateRequest, CancellationToken.NONE));
        var shared = assertInstanceOf(OptimizationResult.Candidate.class,
            optimizer.optimize(sharedRequest, CancellationToken.NONE));
        assertEquals(duplicateTrace, duplicate.obligations().originalTrace());
        assertEquals(sharedTrace, shared.obligations().originalTrace());
        assertTrue(duplicate.cost().sourceScore() > shared.cost().sourceScore());
        assertEquals(duplicate.cost().candidateScore(), shared.cost().candidateScore());
        assertEquals(ComputationOptimizer.prepare(plan).execute(Map.of("x", Integer.MAX_VALUE)),
            duplicate.prepared().execute(Map.of("x", Integer.MAX_VALUE)));
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
