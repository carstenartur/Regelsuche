package de.regelsuche.math.algorithms.modular;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModularComputationDomainTest {
    @Test void boundedBinaryPowersRetainReductionAndRequireTheirModulus() {
        for (int exponent = 2; exponent <= 16; exponent++) {
            var source = List.of(pow(new NumberExpr(exponent)));
            var generated = domain().generate(source, 64);
            var replacement = generated.rewrites().stream().filter(r -> r.rule().equals("modpow-small-binary-chain")).findFirst().orElseThrow();
            assertTrue(domain().verifyEquivalent(source, replacement.outputs()).accepted());
            assertFalse(new ModularComputationDomain(Set.of(), Set.of(), Set.of()).verifyEquivalent(source, replacement.outputs()).accepted());
        }
        for (int exponent : new int[] {-1, 0, 1, 17, Integer.MAX_VALUE}) {
            assertTrue(domain().generate(List.of(pow(new NumberExpr(exponent))), 64).rewrites().stream()
                    .noneMatch(r -> r.rule().equals("modpow-small-binary-chain")));
        }
        assertTrue(domain().generate(List.of(pow(new NumberExpr(2))), 1).rewrites().size() <= 1);
    }
    @Test void reductionsComposeOnlyUnderTheirOwnPositiveModulus() {
        Expr reduced = new FunctionExpr("mod", List.of(A, N));
        Expr nested = new FunctionExpr("mod", List.of(reduced, N));
        var checker = domain();
        assertTrue(checker.generate(List.of(nested), 64).rewrites().stream().anyMatch(r -> r.outputs().equals(List.of(reduced))));
        assertTrue(checker.verifyEquivalent(List.of(nested), List.of(reduced)).accepted());
        var unnormalized = new ModularComputationDomain(Set.of(), Set.of("N", "M"), Set.of());
        assertFalse(unnormalized.verifyEquivalent(List.of(nested), List.of(A)).accepted());
        Expr other = new FunctionExpr("mod", List.of(reduced, new VariableExpr("M")));
        assertFalse(unnormalized.verifyEquivalent(List.of(other), List.of(reduced)).accepted());
        assertFalse(new ModularComputationDomain(Set.of(), Set.of(), Set.of()).verifyEquivalent(List.of(nested), List.of(reduced)).accepted());
    }

    @Test void computedBasesAndMixedProductsHaveBoundedIndependentResidueProofs() {
        Expr sum = new BinaryExpr(A, ADD, new NumberExpr(1));
        Expr base = new FunctionExpr("mod", List.of(sum, N));
        Expr square = new FunctionExpr("modpow", List.of(base, new NumberExpr(2), N));
        assertTrue(domain().verifyEquivalent(List.of(square), List.of(product(base, base))).accepted());
        assertFalse(domain().verifyEquivalent(List.of(square), List.of(new BinaryExpr(base, MUL, base))).accepted());
        assertFalse(domain().verifyEquivalent(List.of(square), List.of(product(base, A))).accepted());
        Expr negative = new FunctionExpr("modpow", List.of(base, new NumberExpr(-1), N));
        assertFalse(domain().verifyEquivalent(List.of(negative), List.of(negative)).accepted());
        Expr deep = A;
        for (int i = 0; i < 110; i++) deep = new FunctionExpr("mod", List.of(deep, N));
        assertFalse(domain().verifyEquivalent(List.of(deep), List.of(base)).accepted());
        assertTrue(domain().generate(List.of(square, new FunctionExpr("mod", List.of(base, N))), 1).rewrites().size() <= 1);
    }

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
