/* HISTORICAL DECOMPILED REFERENCE: not original source; fresh semantic review and qualification required. */
package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.search.program.JointComputationPlan.Output;
import java.math.BigInteger;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OptimizationContractTest {
   private final ComputationOptimizer optimizer = new ComputationOptimizer();
   private static final Expr X = new VariableExpr("x");

   @Test
   void wraparoundIdentityIsProvedAndEvidenceIsIndependentlyRechecked() {
      JointComputationPlan var1 = plan(
         NumericKind.INT,
         JavaExpressions.operation(
            NumericKind.INT,
            NumericOperation.SUBTRACT,
            JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, X, JavaExpressions.literal(1)),
            JavaExpressions.literal(1)
         )
      );
      OptimizationRequest var2 = request(var1, SafetyProfile.PRESERVE_JAVA);
      OptimizationResult.Candidate var3 = (OptimizationResult.Candidate)Assertions.assertInstanceOf(
         OptimizationResult.Candidate.class, this.optimizer.optimize(var2, CancellationToken.NONE)
      );
      Assertions.assertEquals(2147483647, var3.prepared().execute(Map.of("x", 2147483647)).get("out"));
      Assertions.assertInstanceOf(VerificationResult.Verified.class, this.optimizer.reverify(var2, var3, CancellationToken.NONE));
      Assertions.assertInstanceOf(
         VerificationResult.Refuted.class, this.optimizer.verify(var2, plan(NumericKind.INT, JavaExpressions.literal(7)), CancellationToken.NONE)
      );
   }

   @Test
   void removingOriginalMultiplicationOverflowNeedsExplicitPolicy() {
      JointComputationPlan var1 = plan(
         NumericKind.INT,
         JavaExpressions.operation(
            NumericKind.INT,
            NumericOperation.DIVIDE,
            JavaExpressions.operation(NumericKind.INT, NumericOperation.MULTIPLY, X, JavaExpressions.literal(2)),
            JavaExpressions.literal(2)
         )
      );
      Assertions.assertInstanceOf(
         VerificationResult.Refuted.class, this.optimizer.verify(request(var1, SafetyProfile.PRESERVE_JAVA), plan(NumericKind.INT, X), CancellationToken.NONE)
      );
      OptimizationResult.Candidate var2 = (OptimizationResult.Candidate)Assertions.assertInstanceOf(
         OptimizationResult.Candidate.class, this.optimizer.optimize(request(var1, SafetyProfile.CHECKED_THROW), CancellationToken.NONE)
      );
      Assertions.assertTrue(var2.obligations().checkIntegralRange());
      Assertions.assertEquals(2, var2.obligations().originalTrace().occurrences().size());
      Assertions.assertThrows(
         ArithmeticException.class, () -> this.optimizer.evaluateChecked(request(var1, SafetyProfile.CHECKED_THROW), var2, Map.of("x", 2147483647))
      );
   }

   @Test
   void finiteFloatingPointCounterexampleIsRefuted() {
      JointComputationPlan var1 = plan(
         NumericKind.DOUBLE,
         JavaExpressions.operation(
            NumericKind.DOUBLE,
            NumericOperation.SUBTRACT,
            JavaExpressions.operation(NumericKind.DOUBLE, NumericOperation.ADD, X, JavaExpressions.literal(1.0)),
            X
         )
      );
      Assertions.assertInstanceOf(
         VerificationResult.Refuted.class,
         this.optimizer.verify(request(var1, SafetyProfile.PRESERVE_JAVA), plan(NumericKind.DOUBLE, JavaExpressions.literal(1.0)), CancellationToken.NONE)
      );
      OptimizationResult.Candidate var2 = (OptimizationResult.Candidate)Assertions.assertInstanceOf(
         OptimizationResult.Candidate.class, this.optimizer.optimize(request(var1, SafetyProfile.CHECKED_THROW), CancellationToken.NONE)
      );
      Assertions.assertTrue(var2.obligations().compareFloatingPointBits());
      Assertions.assertThrows(
         ArithmeticException.class, () -> this.optimizer.evaluateChecked(request(var1, SafetyProfile.CHECKED_THROW), var2, Map.of("x", 1.0E16))
      );
   }

   @Test
   void signedZeroAndInfinityArePreservedBySafeDoubleNegation() {
      JointComputationPlan var1 = plan(
         NumericKind.DOUBLE,
         JavaExpressions.operation(NumericKind.DOUBLE, NumericOperation.NEGATE, JavaExpressions.operation(NumericKind.DOUBLE, NumericOperation.NEGATE, X))
      );
      OptimizationResult.Candidate var2 = (OptimizationResult.Candidate)Assertions.assertInstanceOf(
         OptimizationResult.Candidate.class, this.optimizer.optimize(request(var1, SafetyProfile.PRESERVE_JAVA), CancellationToken.NONE)
      );

      for (double var6 : new double[]{-0.0, 0.0, 5.0E-324, 1.7976931348623157E308, 1.0 / 0.0}) {
         Assertions.assertEquals(Double.doubleToRawLongBits(var6), Double.doubleToRawLongBits((Double)var2.prepared().execute(Map.of("x", var6)).get("out")));
      }
   }

   @Test
   void narrowingAndPromotionAreNotUnboundedAlgebra() {
      Expr var1 = JavaExpressions.operation(NumericKind.INT, NumericOperation.MULTIPLY, X, JavaExpressions.literal(2));
      JointComputationPlan var2 = new JointComputationPlan(
         Map.of("x", NumericKind.INT.type()),
         Map.of(),
         List.of(new Output("out", NumericKind.LONG.type(), JavaExpressions.cast(NumericKind.INT, NumericKind.LONG, var1)))
      );
      JointComputationPlan var3 = var2.withOutputs(
         List.of(
            JavaExpressions.operation(
               NumericKind.LONG, NumericOperation.MULTIPLY, JavaExpressions.cast(NumericKind.INT, NumericKind.LONG, X), JavaExpressions.literal(2L)
            )
         )
      );
      Assertions.assertInstanceOf(
         VerificationResult.Refuted.class, this.optimizer.verify(request(var2, SafetyProfile.PRESERVE_JAVA), var3, CancellationToken.NONE)
      );
   }

   @Test
   void independentCheckerRejectsTamperedTraceAndDomain() {
      JointComputationPlan var1 = plan(NumericKind.INT, JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, X, JavaExpressions.literal(0)));
      OptimizationRequest var2 = request(var1, SafetyProfile.PRESERVE_JAVA);
      OptimizationRequest var3 = new OptimizationRequest(
         var1,
         new SourceEvaluationTrace(List.of()),
         var2.selectedKinds(),
         var2.semanticsRevision(),
         var2.assumptions(),
         var2.safetyProfile(),
         var2.goal(),
         var2.budget(),
         var2.checkedPolicy()
      );
      Assertions.assertInstanceOf(OptimizationResult.Unsupported.class, this.optimizer.optimize(var3, CancellationToken.NONE));
      OptimizationRequest var4 = new OptimizationRequest(
         var1,
         var2.sourceTrace(),
         var2.selectedKinds(),
         "foreign-domain",
         var2.assumptions(),
         var2.safetyProfile(),
         var2.goal(),
         var2.budget(),
         var2.checkedPolicy()
      );
      Assertions.assertInstanceOf(OptimizationResult.Unsupported.class, this.optimizer.optimize(var4, CancellationToken.NONE));
   }

   @Test
   void cancellationAndWorkBudgetNeverReturnCandidate() {
      JointComputationPlan var1 = plan(NumericKind.INT, JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, X, JavaExpressions.literal(0)));
      OptimizationRequest var2 = request(var1, SafetyProfile.PRESERVE_JAVA);
      Assertions.assertInstanceOf(OptimizationResult.Cancelled.class, this.optimizer.optimize(var2, () -> true));
      OptimizationRequest var3 = new OptimizationRequest(
         var1,
         var2.sourceTrace(),
         var2.selectedKinds(),
         var2.semanticsRevision(),
         var2.assumptions(),
         var2.safetyProfile(),
         var2.goal(),
         new OptimizationBudget(1L, 1, 1, 1L),
         var2.checkedPolicy()
      );
      Assertions.assertInstanceOf(OptimizationResult.BudgetExceeded.class, this.optimizer.optimize(var3, CancellationToken.NONE));
   }

   @Test
   void bigIntegerAlgebraRequiresValueContractAndSharesOutputs() {
      Expr var1 = JavaExpressions.operation(NumericKind.BIG_INTEGER, NumericOperation.ADD, X, JavaExpressions.literal(BigInteger.ZERO));
      JointComputationPlan var2 = new JointComputationPlan(
         Map.of("x", NumericKind.BIG_INTEGER.type()),
         Map.of(),
         List.of(
            new Output("out", NumericKind.BIG_INTEGER.type(), var1),
            new Output("twice", NumericKind.BIG_INTEGER.type(), JavaExpressions.operation(NumericKind.BIG_INTEGER, NumericOperation.ADD, var1, var1))
         )
      );
      OptimizationRequest var3 = request(var2, SafetyProfile.PRESERVE_JAVA);
      OptimizationRequest var4 = new OptimizationRequest(
         var2,
         var3.sourceTrace(),
         var3.selectedKinds(),
         var3.semanticsRevision(),
         Set.of(),
         var3.safetyProfile(),
         var3.goal(),
         var3.budget(),
         var3.checkedPolicy()
      );
      Assertions.assertInstanceOf(OptimizationResult.Unsupported.class, this.optimizer.optimize(var4, CancellationToken.NONE));
      OptimizationResult.Candidate var5 = (OptimizationResult.Candidate)Assertions.assertInstanceOf(
         OptimizationResult.Candidate.class, this.optimizer.optimize(var3, CancellationToken.NONE)
      );
      Assertions.assertEquals(Map.of("out", BigInteger.TEN, "twice", BigInteger.valueOf(20L)), var5.prepared().execute(Map.of("x", BigInteger.TEN)));
   }

   static JointComputationPlan plan(NumericKind var0, Expr var1) {
      return new JointComputationPlan(Map.of("x", var0.type()), Map.of(), List.of(new Output("out", var0.type(), var1)));
   }

   static OptimizationRequest request(JointComputationPlan var0, SafetyProfile var1) {
      HashSet var2 = new HashSet();
      var2.add(new SemanticAssumption(SemanticAssumption.Kind.NO_NAN_PAYLOAD_OBSERVATION, "region", "", "test context"));
      var0.inputs().forEach((var1x, var2x) -> {
         if (var2x.equals(NumericKind.BIG_INTEGER.type())) {
            var2.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, var1x, "", "test exact class/non-null/value contract"));
         }

         if (var2x.equals(NumericKind.BIG_INTEGER.type())) {
            var2.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, var1x, "64", "test input magnitude bound"));
         }
      });
      return new OptimizationRequest(
         var0,
         SourceEvaluationTrace.fromPlan(var0),
         EnumSet.allOf(NumericKind.class),
         "java25-numeric/v1",
         var2,
         var1,
         OptimizationGoal.READABILITY,
         OptimizationBudget.DEFAULT,
         var1 == SafetyProfile.CHECKED_THROW ? CheckedPolicy.EXPLICIT_DEFAULT : CheckedPolicy.NONE
      );
   }
}
