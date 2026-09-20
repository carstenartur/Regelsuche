package de.regelsuche.example.modular;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.math.algorithms.modular.ModularComputationDomain;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModularJointPlansTest {
    private static final Expr A = new VariableExpr("a"), X = new VariableExpr("x"), N = new VariableExpr("N");
    private static final ModularJointPlans DOMAIN = new ModularJointPlans(new ModularComputationDomain(
        Set.of("x"), Set.of("N"), Set.of(new ModularComputationDomain.NormalizedInput("a", "N"))));

    // Replacing the source-only frontier with a supplied answer, independent output scoring,
    // or execution-only CSE would violate these reachability and assessed-state assertions.
    @Test void existingFrontierFindsAndScoresTheSharedPlanFromOnlyTheSource() {
        var input = input();
        var result = DOMAIN.optimizer(64).optimize(input, new MoveSearch.Budget(5, 5, 0, 512, 1_000_000));
        assertTrue(result.search().improved());
        assertTrue(result.withinBudget());
        assertFalse(result.search().search().reached());
        assertEquals(1, result.prepared().nodes().stream().filter(n -> n.operation() != null && n.operation().id().equals("modpow")).count());
        assertEquals(2, result.prepared().nodes().stream().filter(n -> n.operation() != null && n.operation().id().equals("modmul")).count());
        assertTrue(result.search().witness().size() >= 2);
        assertTrue(result.search().search().encodedResult().stateAssessments().values().stream()
            .anyMatch(assessment -> assessment.value() == -result.search().outputScore()));
        assertTrue(result.search().search().metrics().searchWork() > 0);
        assertTrue(result.setupWork() > 0);
        for (int exponent : List.of(0, 1, 2, 5, 19)) {
            BigInteger a = BigInteger.valueOf(3), n = BigInteger.valueOf(101), x = BigInteger.valueOf(exponent);
            var values = result.prepared().execute(Map.of("a", a, "x", x, "N", n));
            assertEquals(a.modPow(x.add(BigInteger.ONE), n), values.get("u"));
            assertEquals(a.modPow(x.multiply(BigInteger.TWO).add(BigInteger.ONE), n), values.get("v"));
        }
        assertThrows(IllegalArgumentException.class, () -> result.prepared().execute(Map.of("a", BigInteger.TWO, "x", BigInteger.valueOf(-1), "N", BigInteger.TEN)));
        assertThrows(IllegalArgumentException.class, () -> result.prepared().execute(Map.of("a", BigInteger.TEN, "x", BigInteger.ONE, "N", BigInteger.TEN)));
    }

    @Test void preparationRejectsTamperedOutputsAndMissingPremises() {
        var original = input();
        var tampered = original.withOutputs(List.of(pow(X), pow(X)));
        assertThrows(IllegalArgumentException.class, () -> DOMAIN.optimizer(64).prepareVerified(original, tampered));
        var noPremises = new ModularJointPlans(new ModularComputationDomain(Set.of(), Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> noPremises.optimizer(64).optimize(original, new MoveSearch.Budget(5, 5, 0, 128, 100_000)));
    }

    @Test void tinyBudgetsRetainTheirMeasuredOverrun() {
        var result = DOMAIN.optimizer(8).optimize(input(), new MoveSearch.Budget(5, 5, 0, 64, 1));
        assertFalse(result.withinBudget());
        assertTrue(result.totalWork() > 1);
    }

    @Test void variableRenamingAndDifferentAffineCoefficientsUseTheSameLaws() {
        Expr b = new VariableExpr("b"), y = new VariableExpr("y"), m = new VariableExpr("M");
        var adapter = new ModularJointPlans(new ModularComputationDomain(Set.of("y"), Set.of("M"),
            Set.of(new ModularComputationDomain.NormalizedInput("b", "M"))));
        Expr e = new BinaryExpr(y, ADD, new NumberExpr(3));
        Expr f = new BinaryExpr(new BinaryExpr(new NumberExpr(2), MUL, y), ADD, new NumberExpr(3));
        var plan = adapter.plan(Map.of("first", new FunctionExpr("modpow", List.of(b, e, m)),
            "second", new FunctionExpr("modpow", List.of(b, f, m))), Set.of("b", "y", "M"));
        var result = adapter.optimizer(64).optimize(plan, new MoveSearch.Budget(6, 6, 0, 512, 1_000_000));
        assertTrue(result.search().improved());
        assertEquals(BigInteger.valueOf(27), result.prepared().execute(Map.of("b", BigInteger.valueOf(3), "y", BigInteger.ZERO, "M", BigInteger.valueOf(101))).get("first"));
    }

    private static JointComputationPlan input() {
        var outputs = new LinkedHashMap<String, Expr>();
        outputs.put("u", pow(new BinaryExpr(X, ADD, new NumberExpr(1))));
        outputs.put("v", pow(new BinaryExpr(new BinaryExpr(new NumberExpr(2), MUL, X), ADD, new NumberExpr(1))));
        return DOMAIN.plan(outputs, Set.of("a", "x", "N"));
    }
    private static Expr pow(Expr exponent) { return new FunctionExpr("modpow", List.of(A, exponent, N)); }
}
