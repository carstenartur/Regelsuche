package de.regelsuche.math.algorithms.modular;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModularComputationDomainTest {
    private static final Expr A = new VariableExpr("a"), X = new VariableExpr("x"), N = new VariableExpr("N");
    private static final Expr XP1 = new BinaryExpr(X, ADD, new NumberExpr(1));
    private static final Expr TWOXP1 = new BinaryExpr(new BinaryExpr(new NumberExpr(2), MUL, X), ADD, new NumberExpr(1));

    @Test void availablePowerDifferencesGenerateTheSharedSubcalculationWithoutATarget() {
        var domain = domain();
        var source = List.of(pow(XP1), pow(TWOXP1));
        var generated = domain.generate(source, 64);
        var target = List.of(pow(XP1), product(pow(XP1), pow(X)));
        assertTrue(generated.rewrites().stream().anyMatch(rewrite -> rewrite.outputs().equals(target)));
        assertTrue(generated.work() > 0);
        assertTrue(domain.verifyEquivalent(source, target).accepted());
        var shared = List.of(product(pow(X), A), product(product(pow(X), A), pow(X)));
        assertTrue(domain.verifyEquivalent(source, shared).accepted());
        assertFalse(domain.verifyEquivalent(source, List.of(product(pow(X), A), product(pow(X), pow(X)))).accepted());
        assertFalse(domain.verifyEquivalent(source, source.reversed()).accepted());
    }

    @Test void proofNeedsNonnegativeExponentsPositiveModulusAndNormalizationForBareBase() {
        var source = List.of(pow(XP1));
        var split = List.of(product(pow(X), A));
        assertFalse(new ModularComputationDomain(Set.of(), Set.of("N"), Set.of()).verifyEquivalent(source, split).accepted());
        assertFalse(new ModularComputationDomain(Set.of("x"), Set.of(), Set.of()).verifyEquivalent(source, split).accepted());
        assertFalse(new ModularComputationDomain(Set.of("x"), Set.of("N"), Set.of()).verifyEquivalent(List.of(pow(new NumberExpr(1))), List.of(A)).accepted());
        assertFalse(domain().verifyEquivalent(List.of(pow(new BinaryExpr(X, MUL, X))), List.of(pow(new BinaryExpr(X, MUL, X)))).accepted());
        assertFalse(domain().verifyEquivalent(List.of(pow(NumberExpr.exact("1/2"))), List.of(pow(NumberExpr.exact("1/2")))).accepted());
    }

    @Test void everyExecutionChecksTheInputDomainAndPrimitivePremises() {
        var domain = domain();
        domain.validateInputs(inputs(2, 0, 7));
        domain.validateInputs(inputs(0, 4, 1));
        assertThrows(IllegalArgumentException.class, () -> domain.validateInputs(inputs(2, -1, 7)));
        assertThrows(IllegalArgumentException.class, () -> domain.validateInputs(inputs(2, 1, 0)));
        assertThrows(IllegalArgumentException.class, () -> domain.validateInputs(inputs(7, 1, 7)));
        assertThrows(IllegalArgumentException.class, () -> domain.validateInputs(inputs(-1, 1, 7)));
        assertThrows(IllegalArgumentException.class, () -> domain.validateInputs(Map.of("a", "2", "x", BigInteger.ONE, "N", BigInteger.TEN)));
        assertEquals(BigInteger.valueOf(2), domain.evaluate("modpow", List.of(BigInteger.valueOf(2), BigInteger.valueOf(4), BigInteger.valueOf(7))));
        assertThrows(IllegalArgumentException.class, () -> domain.evaluate("modpow", List.of(BigInteger.TWO, BigInteger.valueOf(-1), BigInteger.valueOf(7))));
        assertThrows(IllegalArgumentException.class, () -> domain.evaluate("modmul", List.of(BigInteger.TWO, BigInteger.TWO, BigInteger.ZERO)));
    }

    @Test void generationHasExplicitBoundsAndRejectsNearMisses() {
        var generated = domain().generate(List.of(pow(XP1), pow(TWOXP1)), 1);
        assertTrue(generated.rewrites().size() <= 1);
        Expr otherBase = new FunctionExpr("modpow", List.of(new VariableExpr("b"), XP1, N));
        assertTrue(domain().generate(List.of(pow(TWOXP1), otherBase), 64).rewrites().isEmpty());
    }

    private static ModularComputationDomain domain() {
        return new ModularComputationDomain(Set.of("x"), Set.of("N"), Set.of(new ModularComputationDomain.NormalizedInput("a", "N")));
    }
    private static Map<String,Object> inputs(long a, long x, long n) {
        return Map.of("a", BigInteger.valueOf(a), "x", BigInteger.valueOf(x), "N", BigInteger.valueOf(n));
    }
    private static Expr pow(Expr exponent) { return new FunctionExpr("modpow", List.of(A, exponent, N)); }
    private static Expr product(Expr left, Expr right) { return new FunctionExpr("modmul", List.of(left, right, N)); }
}
