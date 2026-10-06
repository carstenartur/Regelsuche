/* HISTORICAL DECOMPILED REFERENCE: not original source; fresh semantic review and qualification required. */
package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.search.program.JointComputationPlan.Output;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BigIntegerRangeContractTest {
   private final ComputationOptimizer optimizer = new ComputationOptimizer();
   private static final Expr X = new VariableExpr("x");

   @Test
   void runtimeConsumerHelperEnforcesMagnitudeAndScalarBounds() {
      var bounded = request(100, SafetyProfile.CHECKED_THROW, OptimizationGoal.READABILITY, true);
      Assertions.assertThrows(IllegalArgumentException.class,
         () -> RuntimeChecks.validateAssumptions(bounded, Map.of("x", BigInteger.ONE.shiftLeft(64))));
      var scalarPlan = new JointComputationPlan(Map.of("x", NumericKind.BIG_INTEGER.type(), "exponent", NumericKind.INT.type()),
         Map.of(), List.of(new Output("out", NumericKind.BIG_INTEGER.type(), X)));
      var scalarRequest = new OptimizationRequest(scalarPlan, SourceEvaluationTrace.fromPlan(scalarPlan),
         Set.of(NumericKind.BIG_INTEGER), ComputationOptimizer.SEMANTICS_REVISION,
         Set.of(new SemanticAssumption(SemanticAssumption.Kind.NON_NEGATIVE_UPPER_BOUND, "exponent", "100", "test bound")),
         SafetyProfile.CHECKED_THROW, OptimizationGoal.READABILITY, OptimizationBudget.DEFAULT, CheckedPolicy.EXPLICIT_DEFAULT);
      Assertions.assertThrows(IllegalArgumentException.class,
         () -> RuntimeChecks.validateAssumptions(scalarRequest, Map.of("x", BigInteger.ONE, "exponent", 101)));
      Assertions.assertThrows(IllegalArgumentException.class,
         () -> RuntimeChecks.validateAssumptions(scalarRequest, Map.of("x", BigInteger.ONE, "exponent", -1)));
      Assertions.assertDoesNotThrow(
         () -> RuntimeChecks.validateAssumptions(scalarRequest, Map.of("x", BigInteger.ONE, "exponent", 100)));
   }

   @Test
   void bigIntegerShiftRequiresItsJavaIntParameterType() {
      var expression = JavaExpressions.operation(NumericKind.BIG_INTEGER, NumericOperation.SHIFT_LEFT,
         X, JavaExpressions.literal(1L));
      var backend = new JavaNumericBackend(Map.of("x", NumericKind.BIG_INTEGER.type()));
      Assertions.assertThrows(IllegalArgumentException.class, () -> backend.operation(expression));
   }

   @Test
   void costlyOperationsAreNotExecutedForCounterexampleSampling() {
      var expression = JavaExpressions.operation(NumericKind.BIG_INTEGER, NumericOperation.POW,
         JavaExpressions.literal(BigInteger.TWO), JavaExpressions.literal(512));
      var source = new JointComputationPlan(Map.of(), Map.of(), List.of(new Output("out", NumericKind.BIG_INTEGER.type(), expression)));
      var request = new OptimizationRequest(source, SourceEvaluationTrace.fromPlan(source), Set.of(NumericKind.BIG_INTEGER),
         ComputationOptimizer.SEMANTICS_REVISION, Set.of(), SafetyProfile.PRESERVE_JAVA,
         OptimizationGoal.READABILITY, OptimizationBudget.DEFAULT, CheckedPolicy.NONE);
      Assertions.assertInstanceOf(VerificationResult.Inconclusive.class,
         optimizer.verify(request, source.withOutputs(List.of(JavaExpressions.literal(BigInteger.ZERO))), CancellationToken.NONE));
   }

   private static OptimizationRequest request(int var0, SafetyProfile var1, OptimizationGoal var2, boolean var3) {
      Expr var4 = JavaExpressions.operation(NumericKind.BIG_INTEGER, NumericOperation.POW, X, JavaExpressions.literal(var0));
      JointComputationPlan var5 = new JointComputationPlan(
         Map.of("x", NumericKind.BIG_INTEGER.type()),
         Map.of(),
         List.of(new Output("out", NumericKind.BIG_INTEGER.type(), JavaExpressions.operation(NumericKind.BIG_INTEGER, NumericOperation.SUBTRACT, var4, var4)))
      );
      HashSet var6 = new HashSet();
      var6.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, "x", "", "exact valueOf(long)"));
      if (var3) {
         var6.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, "x", "64", "valueOf(long) magnitude"));
      }

      return new OptimizationRequest(
         var5,
         SourceEvaluationTrace.fromPlan(var5),
         Set.of(NumericKind.BIG_INTEGER),
         "java25-numeric/v1",
         var6,
         var1,
         var2,
         OptimizationBudget.DEFAULT,
         var1 == SafetyProfile.CHECKED_THROW ? CheckedPolicy.EXPLICIT_DEFAULT : CheckedPolicy.NONE
      );
   }

   @Test
   void supportedRangeArithmeticExceptionCannotBeEliminated() {
      Assertions.assertThrows(ArithmeticException.class, () -> BigInteger.valueOf(4L).pow(2147483647));

      for (SafetyProfile var4 : SafetyProfile.values()) {
         Assertions.assertInstanceOf(
            OptimizationResult.Unsupported.class,
            this.optimizer.optimize(request(2147483647, var4, OptimizationGoal.READABILITY, true), CancellationToken.NONE)
         );
      }
   }

   @Test
   void unknownBigIntegerMagnitudeDoesNotAuthorizeUnboundedAlgebra() {
      Assertions.assertInstanceOf(
         OptimizationResult.Unsupported.class,
         this.optimizer.optimize(request(100, SafetyProfile.PRESERVE_JAVA, OptimizationGoal.READABILITY, false), CancellationToken.NONE)
      );
   }

   @Test
   void finiteMagnitudeBoundKeepsUsefulBigIntegerOptimization() {
      OptimizationResult.Candidate var1 = (OptimizationResult.Candidate)Assertions.assertInstanceOf(
         OptimizationResult.Candidate.class,
         this.optimizer.optimize(request(100, SafetyProfile.PRESERVE_JAVA, OptimizationGoal.READABILITY, true), CancellationToken.NONE)
      );
      Assertions.assertEquals(BigInteger.ZERO, var1.prepared().execute(Map.of("x", BigInteger.valueOf(4L))).get("out"));
   }

   @Test
   void checkedRuntimeGoalIncludesBothOriginalPowerEvaluations() {
      Assertions.assertInstanceOf(
         OptimizationResult.NoImprovement.class,
         this.optimizer.optimize(request(100, SafetyProfile.CHECKED_THROW, OptimizationGoal.LOWER_ESTIMATED_RUNTIME, true), CancellationToken.NONE)
      );
      OptimizationResult.Candidate var1 = (OptimizationResult.Candidate)Assertions.assertInstanceOf(
         OptimizationResult.Candidate.class,
         this.optimizer.optimize(request(100, SafetyProfile.CHECKED_THROW, OptimizationGoal.READABILITY, true), CancellationToken.NONE)
      );
      Assertions.assertFalse(var1.cost().estimatedRuntimeImprovement());
      Assertions.assertTrue(var1.obligations().estimatedCheckWork() >= 12L, "two original powers plus subtraction must be charged");
   }

   @Test
   void costlyConstantOperationsAreSkippedBeforeBackendEvaluation() {
      JointComputationPlan var1 = new JointComputationPlan(
         Map.of(),
         Map.of(),
         List.of(
            new Output(
               "out",
               NumericKind.BIG_INTEGER.type(),
               JavaExpressions.operation(
                  NumericKind.BIG_INTEGER, NumericOperation.POW, JavaExpressions.literal(BigInteger.valueOf(2L)), JavaExpressions.literal(512)
               )
            )
         )
      );
      OptimizationRequest var2 = new OptimizationRequest(
         var1,
         SourceEvaluationTrace.fromPlan(var1),
         Set.of(NumericKind.BIG_INTEGER),
         "java25-numeric/v1",
         Set.of(),
         SafetyProfile.PRESERVE_JAVA,
         OptimizationGoal.READABILITY,
         OptimizationBudget.DEFAULT,
         CheckedPolicy.NONE
      );
      OptimizationResult.NoImprovement var3 = (OptimizationResult.NoImprovement)Assertions.assertInstanceOf(
         OptimizationResult.NoImprovement.class, this.optimizer.optimize(var2, CancellationToken.NONE)
      );
      Assertions.assertEquals("CONSTANT_FOLD_BUDGET_LIMIT", var3.diagnostic());
   }
}
