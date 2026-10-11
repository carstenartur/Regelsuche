package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.math.algorithms.modular.ModularComputationDomain;
import de.regelsuche.math.algorithms.modular.ModularJointPlans;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.program.JointComputationPlan;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ModularBackendCostTest {
    private static final Expr X = new VariableExpr("x"), M = new VariableExpr("m");

    @Test void directModularAdapterSelectsTheIndependentlyVerifiedSquare() {
        var adapter = adapter();
        var source = adapter.plan(Map.of("result", power(JavaExpressions.literal(BigInteger.TWO))), Set.of("x", "m"));
        var result = adapter.optimizer(64).optimize(source, new MoveSearch.Budget(5, 5, 0, 128, 1_000_000));

        assertTrue(result.search().improved());
        assertTrue(result.withinBudget());
        assertTrue(adapter.verify(source, result.plan()).accepted());
        assertTrue(result.prepared().cost().operationWork() < source.prepare(adapter).cost().operationWork());
        assertEquals(1, result.prepared().nodes().stream()
                .filter(node -> node.operation() != null && node.operation().id().equals("modmul")).count());
        assertTrue(result.prepared().nodes().stream()
                .noneMatch(node -> node.operation() != null && node.operation().id().equals("modpow")));
        for (int x = -30; x <= 30; x++) for (int modulus = 1; modulus <= 31; modulus++) {
            var base = BigInteger.valueOf(x);
            var divisor = BigInteger.valueOf(modulus);
            assertEquals(base.modPow(BigInteger.TWO, divisor),
                    result.prepared().execute(Map.of("x", base, "m", divisor)).get("result"));
        }
    }

    @Test void modularAndJavaBackendsUseTheSameModularOperationEstimates() {
        var modular = adapter();
        var java = new JavaNumericBackend(Map.of("x", NumericKind.BIG_INTEGER.type(), "m", NumericKind.BIG_INTEGER.type(),
                "e", NumericKind.BIG_INTEGER.type()));
        for (Expr exponent : List.of(JavaExpressions.literal(BigInteger.ZERO), JavaExpressions.literal(BigInteger.TWO),
                JavaExpressions.literal(BigInteger.valueOf(16)), JavaExpressions.literal(BigInteger.ONE.shiftLeft(1000)),
                new VariableExpr("e"))) {
            var typedPower = JavaExpressions.operation(NumericKind.BIG_INTEGER, NumericOperation.MOD_POW, X, exponent, M);
            assertEquals(java.operation(typedPower).work(), modular.operation(power(exponent)).work(), exponent.toString());
        }
        assertEquals(java.operation(JavaExpressions.operation(NumericKind.BIG_INTEGER, NumericOperation.MOD_MULTIPLY, X, X, M)).work(),
                modular.operation(new FunctionExpr("modmul", List.of(X, X, M))).work());
    }

    @ParameterizedTest
    @EnumSource(value = NumericOperation.class, names = {"ADD", "SUBTRACT", "MULTIPLY", "MOD"})
    void ordinaryOperationsCannotBecomeArtificiallyCheaperInTheDirectModularAdapter(NumericOperation operation) {
        Expr modular = switch (operation) {
            case ADD -> new BinaryExpr(X, BinaryOperator.ADD, M);
            case SUBTRACT -> new BinaryExpr(X, BinaryOperator.SUB, M);
            case MULTIPLY -> new BinaryExpr(X, BinaryOperator.MUL, M);
            case MOD -> new FunctionExpr("mod", List.of(X, M));
            default -> throw new IllegalArgumentException("ordinary modular arithmetic required");
        };
        Expr java = JavaExpressions.operation(NumericKind.BIG_INTEGER, operation, X, M);
        assertPreparedOperationWork(4, modular, java);
    }

    @Test void ordinaryAndFusedMultiplyThenModEachKeepTheirEstimateAcrossBackends() {
        Expr ordinary = new FunctionExpr("mod", List.of(new BinaryExpr(X, BinaryOperator.MUL, X), M));
        Expr typedOrdinary = JavaExpressions.operation(NumericKind.BIG_INTEGER, NumericOperation.MOD,
            JavaExpressions.operation(NumericKind.BIG_INTEGER, NumericOperation.MULTIPLY, X, X), M);
        assertPreparedOperationWork(8, ordinary, typedOrdinary);

        Expr fused = new FunctionExpr("modmul", List.of(X, X, M));
        Expr typedFused = JavaExpressions.operation(NumericKind.BIG_INTEGER, NumericOperation.MOD_MULTIPLY, X, X, M);
        assertPreparedOperationWork(10, fused, typedFused);
    }

    private static void assertPreparedOperationWork(long expected, Expr modular, Expr java) {
        var adapter = adapter();
        var modularPlan = adapter.plan(Map.of("result", modular), Set.of("x", "m"));
        var inputs = Map.of("x", NumericKind.BIG_INTEGER.type(), "m", NumericKind.BIG_INTEGER.type());
        var javaPlan = new JointComputationPlan(inputs, Map.of(), List.of(
            new JointComputationPlan.Output("result", NumericKind.BIG_INTEGER.type(), java)));
        assertEquals(expected, javaPlan.prepare(new JavaNumericBackend(inputs)).cost().operationWork(),
            "The established Java estimate must stay unchanged");
        assertEquals(expected, modularPlan.prepare(adapter).cost().operationWork(),
            "The same arithmetic representation must have the same work in the direct adapter");
    }

    private static ModularJointPlans adapter() {
        return new ModularJointPlans(new ModularComputationDomain(Set.of(), Set.of("m"), Set.of()));
    }

    private static Expr power(Expr exponent) { return new FunctionExpr("modpow", List.of(X, exponent, M)); }
}
