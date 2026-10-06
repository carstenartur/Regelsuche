/* HISTORICAL DECOMPILED REFERENCE: not original source; fresh semantic review and qualification required. */
package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.search.program.PreparedJointComputation;
import de.regelsuche.search.program.JointComputationPlan.Output;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NumericBoundaryTest {
   private final ComputationOptimizer optimizer = new ComputationOptimizer();
   private static final Expr X = new VariableExpr("x");

   @Test
   void primitiveEvaluatorKeepsJavaDivisionRemainderShiftsAndNarrowing() {
      Assertions.assertEquals(
         -2147483648,
         evaluate(NumericKind.INT, JavaExpressions.operation(NumericKind.INT, NumericOperation.DIVIDE, X, JavaExpressions.literal(-1)), -2147483648)
      );
      Assertions.assertEquals(
         -1, evaluate(NumericKind.INT, JavaExpressions.operation(NumericKind.INT, NumericOperation.REMAINDER, X, JavaExpressions.literal(2)), -3)
      );
      Assertions.assertEquals(
         1, evaluate(NumericKind.INT, JavaExpressions.operation(NumericKind.INT, NumericOperation.SHIFT_LEFT, X, JavaExpressions.literal(32L)), 1)
      );
      Assertions.assertEquals(
         2147483647,
         evaluate(NumericKind.INT, JavaExpressions.operation(NumericKind.INT, NumericOperation.UNSIGNED_SHIFT_RIGHT, X, JavaExpressions.literal(1)), -1)
      );
      Assertions.assertEquals(
         -1, evaluate(NumericKind.INT, JavaExpressions.operation(NumericKind.INT, NumericOperation.SHIFT_RIGHT, X, JavaExpressions.literal(64L)), -1)
      );
      Assertions.assertEquals(
         1L, evaluate(NumericKind.LONG, JavaExpressions.operation(NumericKind.LONG, NumericOperation.SHIFT_LEFT, X, JavaExpressions.literal(64)), 1L)
      );
      Assertions.assertThrows(
         ArithmeticException.class,
         () -> evaluate(NumericKind.INT, JavaExpressions.operation(NumericKind.INT, NumericOperation.DIVIDE, X, JavaExpressions.literal(0)), 4)
      );
      JointComputationPlan var1 = new JointComputationPlan(
         Map.of("x", NumericKind.INT.type()),
         Map.of(),
         List.of(
            new Output("b", NumericKind.BYTE.type(), JavaExpressions.cast(NumericKind.INT, NumericKind.BYTE, X)),
            new Output("c", NumericKind.CHAR.type(), JavaExpressions.cast(NumericKind.INT, NumericKind.CHAR, X))
         )
      );
      Assertions.assertEquals(Map.of("b", (byte)-1, "c", '\uffff'), ComputationOptimizer.prepare(var1).execute(Map.of("x", 65535)));
   }

   @Test
   void bitwiseIdentitiesAreUniversallyProvedNotRejectedAsOverflow() {
      JointComputationPlan var1 = OptimizationContractTest.plan(NumericKind.LONG, JavaExpressions.operation(NumericKind.LONG, NumericOperation.XOR, X, X));
      OptimizationResult.Candidate var2 = (OptimizationResult.Candidate)Assertions.assertInstanceOf(
         OptimizationResult.Candidate.class,
         this.optimizer.optimize(OptimizationContractTest.request(var1, SafetyProfile.PRESERVE_JAVA), CancellationToken.NONE)
      );
      Assertions.assertEquals(0L, var2.prepared().execute(Map.of("x", -9223372036854775808L)).get("out"));
      Assertions.assertTrue(var2.evidence().proofMethods().contains("BITVECTOR_TRUTH_TABLE"));
   }

   @Test
   void checkedIdentityRetainsDeadOriginalOverflowAndNewTargetOverflow() {
      JointComputationPlan var1 = OptimizationContractTest.plan(
         NumericKind.INT,
         JavaExpressions.operation(
            NumericKind.INT,
            NumericOperation.SUBTRACT,
            JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, X, JavaExpressions.literal(1)),
            JavaExpressions.literal(1)
         )
      );
      OptimizationRequest var2 = OptimizationContractTest.request(var1, SafetyProfile.CHECKED_THROW);
      OptimizationResult.Candidate var3 = (OptimizationResult.Candidate)Assertions.assertInstanceOf(
         OptimizationResult.Candidate.class, this.optimizer.optimize(var2, CancellationToken.NONE)
      );
      Assertions.assertThrows(ArithmeticException.class, () -> this.optimizer.evaluateChecked(var2, var3, Map.of("x", 2147483647)));
      Assertions.assertEquals(41, this.optimizer.evaluateChecked(var2, var3, Map.of("x", 41)).get("out"));
      JointComputationPlan var4 = OptimizationContractTest.plan(
         NumericKind.INT,
         JavaExpressions.operation(
            NumericKind.INT, NumericOperation.SUBTRACT, JavaExpressions.operation(NumericKind.INT, NumericOperation.MULTIPLY, X, JavaExpressions.literal(2)), X
         )
      );
      VerificationResult.Verified var5 = (VerificationResult.Verified)Assertions.assertInstanceOf(
         VerificationResult.Verified.class,
         this.optimizer
            .verify(
               OptimizationContractTest.request(OptimizationContractTest.plan(NumericKind.INT, X), SafetyProfile.CHECKED_THROW), var4, CancellationToken.NONE
            )
      );
      Assertions.assertTrue(var5.obligations().checkIntegralRange());
      Assertions.assertEquals(2, var5.obligations().replacementTrace().occurrences().size());
   }

   @Test
   void guardedIntegralFallbackKeepsWraparoundResult() {
      JointComputationPlan var1 = OptimizationContractTest.plan(
         NumericKind.INT,
         JavaExpressions.operation(
            NumericKind.INT,
            NumericOperation.DIVIDE,
            JavaExpressions.operation(NumericKind.INT, NumericOperation.MULTIPLY, X, JavaExpressions.literal(2)),
            JavaExpressions.literal(2)
         )
      );
      OptimizationRequest var2 = OptimizationContractTest.request(var1, SafetyProfile.GUARDED_FALLBACK);
      OptimizationResult.Candidate var3 = (OptimizationResult.Candidate)Assertions.assertInstanceOf(
         OptimizationResult.Candidate.class, this.optimizer.optimize(var2, CancellationToken.NONE)
      );
      Assertions.assertEquals(-1, this.optimizer.evaluateGuarded(var2, var3, Map.of("x", 2147483647)).get("out"));
      Assertions.assertEquals(7, this.optimizer.evaluateGuarded(var2, var3, Map.of("x", 7)).get("out"));
      Assertions.assertFalse(var3.cost().estimatedRuntimeImprovement());
   }

   @Test
   void integerTruncationDoesNotBecomeRationalDivision() {
      JointComputationPlan var1 = OptimizationContractTest.plan(
         NumericKind.INT,
         JavaExpressions.operation(
            NumericKind.INT,
            NumericOperation.MULTIPLY,
            JavaExpressions.operation(NumericKind.INT, NumericOperation.DIVIDE, X, JavaExpressions.literal(2)),
            JavaExpressions.literal(2)
         )
      );
      Assertions.assertInstanceOf(
         VerificationResult.Refuted.class,
         this.optimizer
            .verify(
               OptimizationContractTest.request(var1, SafetyProfile.CHECKED_THROW), OptimizationContractTest.plan(NumericKind.INT, X), CancellationToken.NONE
            )
      );
   }

   @Test
   void existingExactExceptionsAndIncompleteDeadOperationTraceFailClosed() {
      JointComputationPlan var1 = OptimizationContractTest.plan(
         NumericKind.INT, JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD_EXACT, X, JavaExpressions.literal(0))
      );
      Assertions.assertInstanceOf(
         OptimizationResult.Unsupported.class,
         this.optimizer.optimize(OptimizationContractTest.request(var1, SafetyProfile.PRESERVE_JAVA), CancellationToken.NONE)
      );
      JointComputationPlan var2 = OptimizationContractTest.plan(NumericKind.INT, X);
      Expr var3 = JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, X, JavaExpressions.literal(1));
      OptimizationRequest var4 = OptimizationContractTest.request(var2, SafetyProfile.CHECKED_THROW);
      OptimizationRequest var5 = new OptimizationRequest(
         var2,
         new SourceEvaluationTrace(List.of(new SourceEvaluationTrace.Occurrence("dead", var3, NumericKind.INT, NumericKind.INT))),
         var4.selectedKinds(),
         var4.semanticsRevision(),
         var4.assumptions(),
         var4.safetyProfile(),
         var4.goal(),
         var4.budget(),
         var4.checkedPolicy()
      );
      VerificationResult.Verified var6 = (VerificationResult.Verified)Assertions.assertInstanceOf(
         VerificationResult.Verified.class, this.optimizer.verify(var5, var2, CancellationToken.NONE)
      );
      Assertions.assertEquals(var3, ((SourceEvaluationTrace.Occurrence)var6.obligations().originalTrace().occurrences().getFirst()).expression());
   }

   @Test
   void evidenceCannotAuthorizeAnotherSourceOrForgedPlan() {
      JointComputationPlan var1 = OptimizationContractTest.plan(
         NumericKind.INT, JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, X, JavaExpressions.literal(0))
      );
      OptimizationRequest var2 = OptimizationContractTest.request(var1, SafetyProfile.PRESERVE_JAVA);
      OptimizationResult.Candidate var3 = (OptimizationResult.Candidate)Assertions.assertInstanceOf(
         OptimizationResult.Candidate.class, this.optimizer.optimize(var2, CancellationToken.NONE)
      );
      OptimizationRequest var4 = OptimizationContractTest.request(
         OptimizationContractTest.plan(NumericKind.INT, JavaExpressions.operation(NumericKind.INT, NumericOperation.SUBTRACT, X, JavaExpressions.literal(0))),
         SafetyProfile.PRESERVE_JAVA
      );
      Assertions.assertInstanceOf(VerificationResult.Unsupported.class, this.optimizer.reverify(var4, var3, CancellationToken.NONE));
      Assertions.assertThrows(
         IllegalArgumentException.class,
         () -> new OptimizationResult.Candidate(
            OptimizationContractTest.plan(NumericKind.INT, JavaExpressions.literal(9)),
            var3.prepared(),
            var3.evidence(),
            var3.obligations(),
            var3.cost(),
            var3.searchCompletion(),
            var3.work()
         )
      );
   }

   @Test
   void floatCorpusHasNoImplicitDoublePrecisionOrFastMath() {
      JointComputationPlan var1 = OptimizationContractTest.plan(
         NumericKind.FLOAT, JavaExpressions.operation(NumericKind.FLOAT, NumericOperation.MULTIPLY, X, JavaExpressions.literal(1.0F))
      );
      OptimizationResult.Candidate var2 = (OptimizationResult.Candidate)Assertions.assertInstanceOf(
         OptimizationResult.Candidate.class,
         this.optimizer.optimize(OptimizationContractTest.request(var1, SafetyProfile.PRESERVE_JAVA), CancellationToken.NONE)
      );

      for (float var6 : new float[]{0.0F / 0.0F, -1.0F / 0.0F, 1.0F / 0.0F, -0.0F, 0.0F, 1.0E-45F, 1.1754943E-38F, 3.4028235E38F, -1.0F}) {
         Assertions.assertEquals(Float.floatToIntBits(var6), Float.floatToIntBits((Float)var2.prepared().execute(Map.of("x", var6)).get("out")));
      }

      JointComputationPlan var7 = OptimizationContractTest.plan(
         NumericKind.DOUBLE, JavaExpressions.operation(NumericKind.DOUBLE, NumericOperation.ADD, X, JavaExpressions.literal(0.0))
      );
      Assertions.assertInstanceOf(
         VerificationResult.Refuted.class,
         this.optimizer
            .verify(
               OptimizationContractTest.request(var7, SafetyProfile.PRESERVE_JAVA),
               OptimizationContractTest.plan(NumericKind.DOUBLE, X),
               CancellationToken.NONE
            )
      );
      Assertions.assertEquals(
         0.0F,
         evaluate(
            NumericKind.FLOAT,
            JavaExpressions.operation(
               NumericKind.FLOAT,
               NumericOperation.SUBTRACT,
               JavaExpressions.operation(NumericKind.FLOAT, NumericOperation.ADD, X, JavaExpressions.literal(1.0F)),
               X
            ),
            1.6777216E7F
         )
      );
   }

   @Test
   void checkedFiniteAndSignedZeroPoliciesAreExplicit() {
      JointComputationPlan var1 = OptimizationContractTest.plan(
         NumericKind.DOUBLE, JavaExpressions.operation(NumericKind.DOUBLE, NumericOperation.SUBTRACT, X, X)
      );
      OptimizationRequest var2 = OptimizationContractTest.request(var1, SafetyProfile.CHECKED_THROW);
      OptimizationResult.Candidate var3 = (OptimizationResult.Candidate)Assertions.assertInstanceOf(
         OptimizationResult.Candidate.class, this.optimizer.optimize(var2, CancellationToken.NONE)
      );
      Assertions.assertThrows(ArithmeticException.class, () -> this.optimizer.evaluateChecked(var2, var3, Map.of("x", 1.0 / 0.0)));
      Assertions.assertEquals(0.0, this.optimizer.evaluateChecked(var2, var3, Map.of("x", 5.0E-324)).get("out"));
      OptimizationRequest var4 = new OptimizationRequest(
         var1,
         var2.sourceTrace(),
         var2.selectedKinds(),
         var2.semanticsRevision(),
         var2.assumptions(),
         var2.safetyProfile(),
         var2.goal(),
         var2.budget(),
         new CheckedPolicy("java-checked/v1", true, true, true, true)
      );
      Assertions.assertInstanceOf(OptimizationResult.Unsupported.class, this.optimizer.optimize(var4, CancellationToken.NONE));
   }

   @Test
   void aBigIntegerAssumptionOnPrimitiveInputIsRejected() {
      JointComputationPlan var1 = OptimizationContractTest.plan(
         NumericKind.INT, JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, X, JavaExpressions.literal(0))
      );
      OptimizationRequest var2 = OptimizationContractTest.request(var1, SafetyProfile.PRESERVE_JAVA);
      OptimizationRequest var3 = new OptimizationRequest(
         var1,
         var2.sourceTrace(),
         var2.selectedKinds(),
         var2.semanticsRevision(),
         Set.of(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, "x", "", "bad caller")),
         var2.safetyProfile(),
         var2.goal(),
         var2.budget(),
         var2.checkedPolicy()
      );
      Assertions.assertInstanceOf(OptimizationResult.Unsupported.class, this.optimizer.optimize(var3, CancellationToken.NONE));
   }

   @Test
   void congruenceAllowsSafeSimplificationInsideNarrowingWithoutDroppingTheCast() {
      Expr var1 = JavaExpressions.cast(
         NumericKind.INT, NumericKind.BYTE, JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, X, JavaExpressions.literal(0))
      );
      JointComputationPlan var2 = new JointComputationPlan(
         Map.of("x", NumericKind.INT.type()), Map.of(), List.of(new Output("out", NumericKind.BYTE.type(), var1))
      );
      OptimizationResult.Candidate var3 = (OptimizationResult.Candidate)Assertions.assertInstanceOf(
         OptimizationResult.Candidate.class,
         this.optimizer.optimize(OptimizationContractTest.request(var2, SafetyProfile.PRESERVE_JAVA), CancellationToken.NONE)
      );
      Assertions.assertEquals((byte)-1, var3.prepared().execute(Map.of("x", 255)).get("out"));
      Assertions.assertEquals(NumericKind.INT, JavaExpressions.castSourceKind((Expr)var3.plan().outputExpressions().getFirst()).orElseThrow());
   }

   @Test
   void excludedPrimitiveOutputDoesNotHideBehindBigIntegerScalarException() {
      JointComputationPlan var1 = OptimizationContractTest.plan(NumericKind.INT, X);
      OptimizationRequest var2 = OptimizationContractTest.request(var1, SafetyProfile.PRESERVE_JAVA);
      OptimizationRequest var3 = new OptimizationRequest(
         var1,
         var2.sourceTrace(),
         Set.of(NumericKind.BIG_INTEGER),
         var2.semanticsRevision(),
         var2.assumptions(),
         var2.safetyProfile(),
         var2.goal(),
         var2.budget(),
         var2.checkedPolicy()
      );
      Assertions.assertInstanceOf(OptimizationResult.Unsupported.class, this.optimizer.optimize(var3, CancellationToken.NONE));
   }

   @Test
   void finiteGatedProfilesDoNotNeedANaNPayloadAssumption() {
      JointComputationPlan var1 = OptimizationContractTest.plan(
         NumericKind.DOUBLE,
         JavaExpressions.operation(
            NumericKind.DOUBLE,
            NumericOperation.SUBTRACT,
            JavaExpressions.operation(NumericKind.DOUBLE, NumericOperation.ADD, X, JavaExpressions.literal(1.0)),
            X
         )
      );

      for (SafetyProfile var3 : List.of(SafetyProfile.GUARDED_FALLBACK, SafetyProfile.CHECKED_THROW)) {
         OptimizationRequest var4 = OptimizationContractTest.request(var1, var3);
         OptimizationRequest var5 = new OptimizationRequest(
            var1, var4.sourceTrace(), var4.selectedKinds(), var4.semanticsRevision(), Set.of(), var3, var4.goal(), var4.budget(), var4.checkedPolicy()
         );
         OptimizationResult.Candidate var6 = (OptimizationResult.Candidate)Assertions.assertInstanceOf(
            OptimizationResult.Candidate.class, this.optimizer.optimize(var5, CancellationToken.NONE)
         );
         Assertions.assertTrue(var6.obligations().requireFinite());
         if (var3 == SafetyProfile.CHECKED_THROW) {
            Assertions.assertThrows(ArithmeticException.class, () -> this.optimizer.evaluateChecked(var5, var6, Map.of("x", 0.0 / 0.0)));
         }
      }
   }

   @Test
   void independentlyComposesPolynomialAndBitwiseIdentitiesAcrossOutputs() {
      Expr var1 = JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, X, JavaExpressions.literal(0));
      Expr var2 = JavaExpressions.operation(NumericKind.INT, NumericOperation.XOR, var1, JavaExpressions.literal(0));
      JointComputationPlan var3 = new JointComputationPlan(
         Map.of("x", NumericKind.INT.type()),
         Map.of(),
         List.of(
            new Output("initial", NumericKind.INT.type(), X),
            new Output("added", NumericKind.INT.type(), var1),
            new Output("final", NumericKind.INT.type(), var2)
         )
      );
      OptimizationResult.Candidate var4 = (OptimizationResult.Candidate)Assertions.assertInstanceOf(
         OptimizationResult.Candidate.class,
         this.optimizer.optimize(OptimizationContractTest.request(var3, SafetyProfile.PRESERVE_JAVA), CancellationToken.NONE)
      );
      Assertions.assertEquals(Map.of("initial", -2147483648, "added", -2147483648, "final", -2147483648), var4.prepared().execute(Map.of("x", -2147483648)));
   }

   @Test
   void suppliedPreparedScheduleCannotSpoofVerifiedCandidate() {
      JointComputationPlan var1 = OptimizationContractTest.plan(
         NumericKind.INT, JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, X, JavaExpressions.literal(0))
      );
      OptimizationResult.Candidate var2 = (OptimizationResult.Candidate)Assertions.assertInstanceOf(
         OptimizationResult.Candidate.class,
         this.optimizer.optimize(OptimizationContractTest.request(var1, SafetyProfile.PRESERVE_JAVA), CancellationToken.NONE)
      );
      PreparedJointComputation var3 = ComputationOptimizer.prepare(OptimizationContractTest.plan(NumericKind.INT, JavaExpressions.literal(999)));
      Assertions.assertThrows(
         IllegalArgumentException.class,
         () -> new OptimizationResult.Candidate(var2.plan(), var3, var2.evidence(), var2.obligations(), var2.cost(), var2.searchCompletion(), var2.work())
      );
   }

   private static Object evaluate(NumericKind var0, Expr var1, Object var2) {
      return ComputationOptimizer.prepare(OptimizationContractTest.plan(var0, var1)).execute(Map.of("x", var2)).get("out");
   }
}
