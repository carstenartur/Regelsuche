package de.regelsuche.math.algorithms.modular;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModularArithmeticCongruenceTest {
    private static final Expr X = new VariableExpr("x"), N = new VariableExpr("n"), M = new VariableExpr("m");
    private static final Expr FOUR = new NumberExpr(4);

    @Test void proposesRemovingProductReductionsBeforeAnAddendWithoutASuppliedTarget() {
        // The bridge's view of x.multiply(x).mod(n).multiply(x).mod(n).add(4).mod(n).
        Expr source = mod(add(modmul(modmul(X, X, N), X, N), FOUR), N);
        Expr expected = mod(add(mul(mul(X, X), X), FOUR), N);

        var generated = domain().generate(List.of(source), 64);

        assertTrue(generated.rewrites().stream().anyMatch(rewrite -> rewrite.outputs().equals(List.of(expected))),
            "The generic arithmetic proposal must remove both inner reductions and retain the final mod");
        assertTrue(generated.work() > 0);
        assertTrue(domain().generate(List.of(source), 1).rewrites().size() <= 1);
    }

    @Test void independentlyProvesAdditionAndSubtractionOfCongruentOperands() {
        // Both targets are written independently of generation; x has no normalization premise.
        Expr source = mod(add(mod(mul(mod(mul(X, X), N), X), N), FOUR), N);
        Expr target = mod(add(mul(mul(X, X), X), FOUR), N);
        assertTrue(domain().verifyEquivalent(List.of(source), List.of(target)).accepted());

        Expr difference = mod(sub(mod(add(X, FOUR), N), modmul(X, X, N)), N);
        Expr reducedDifference = mod(sub(add(X, FOUR), mul(X, X)), N);
        assertTrue(domain().verifyEquivalent(List.of(difference), List.of(reducedDifference)).accepted());
        assertFalse(domain().verifyEquivalent(List.of(difference),
            List.of(mod(sub(mul(X, X), add(X, FOUR)), N))).accepted(), "Subtraction order is significant");
    }

    @Test void cannotChangeEitherModulusOrRemoveTheFinalCanonicalReduction() {
        Expr source = mod(add(modmul(modmul(X, X, N), X, N), FOUR), N);
        Expr unreduced = add(mul(mul(X, X), X), FOUR);
        assertFalse(domain().verifyEquivalent(List.of(source), List.of(unreduced)).accepted());
        assertFalse(domain().verifyEquivalent(List.of(source), List.of(mod(unreduced, M))).accepted());

        Expr otherInnerModulus = mod(add(modmul(modmul(X, X, M), X, N), FOUR), N);
        assertFalse(domain().verifyEquivalent(List.of(otherInnerModulus), List.of(mod(unreduced, N))).accepted());
        assertTrue(domain().generate(List.of(otherInnerModulus), 64).rewrites().stream()
            .noneMatch(rewrite -> rewrite.outputs().equals(List.of(mod(unreduced, N)))));
    }

    @Test void arithmeticCongruenceRequiresAPositiveModulus() {
        var noPremises = new ModularComputationDomain(Set.of(), Set.of(), Set.of());
        for (Expr modulus : List.of(N, new NumberExpr(0), new NumberExpr(-7))) {
            Expr source = mod(add(mod(X, modulus), FOUR), modulus);
            Expr target = mod(add(X, FOUR), modulus);
            assertFalse(noPremises.verifyEquivalent(List.of(source), List.of(target)).accepted());
            assertTrue(noPremises.generate(List.of(source), 64).rewrites().isEmpty());
        }
        Expr source = mod(add(mod(X, new NumberExpr(1)), FOUR), new NumberExpr(1));
        Expr target = mod(add(X, FOUR), new NumberExpr(1));
        assertTrue(noPremises.verifyEquivalent(List.of(source), List.of(target)).accepted());
    }

    @Test void aDifferentInnerModulusStopsCongruenceForItsEntireValue() {
        Expr source = mod(add(mod(mod(X, N), M), FOUR), N);
        Expr wrong = mod(add(mod(X, M), FOUR), N);
        // x=5,n=3,m=2 gives 1 and 2 respectively: crossing m loses information.
        assertFalse(domain().verifyEquivalent(List.of(source), List.of(wrong)).accepted());
        assertTrue(domain().generate(List.of(source), 64).rewrites().stream()
            .noneMatch(rewrite -> rewrite.outputs().equals(List.of(wrong))));
    }

    @Test void removingAReductionAtItsUseCannotChangeAnotherNormalizedOutput() {
        Expr square = modmul(X, X, N);
        Expr source = mod(add(modmul(square, X, N), FOUR), N);
        Expr target = mod(add(mul(mul(X, X), X), FOUR), N);
        var generated = domain().generate(List.of(source, square), 64);

        assertTrue(generated.rewrites().stream().anyMatch(rewrite -> rewrite.outputs().equals(List.of(target, square))));
        assertTrue(generated.rewrites().stream().allMatch(rewrite -> rewrite.outputs().get(1).equals(square)),
            "A congruent operand replacement is valid only inside its enclosing reducing expression");
        assertFalse(domain().verifyEquivalent(List.of(source, square), List.of(target, mul(X, X))).accepted());
    }

    @Test void anExponentIsNotAValueInTheOuterResidueRing() {
        var checker = new ModularComputationDomain(Set.of("x"), Set.of("n"), Set.of());
        Expr source = mod(add(pow(new NumberExpr(2), X, N), FOUR), N);
        Expr changedExponent = mod(add(pow(new NumberExpr(2), add(X, N), N), FOUR), N);
        // At x=0,n=7 these are 5 and 6, even though the exponents are congruent modulo n.
        assertFalse(checker.verifyEquivalent(List.of(source), List.of(changedExponent)).accepted());

        Expr reducedExponent = mod(add(pow(new NumberExpr(2), mod(X, N), N), FOUR), N);
        assertFalse(checker.verifyEquivalent(List.of(reducedExponent), List.of(source)).accepted());
        assertTrue(checker.generate(List.of(reducedExponent), 64).rewrites().stream()
            .noneMatch(rewrite -> rewrite.outputs().equals(List.of(source))));
    }

    @Test void productsOfSumsStayBoundedWithoutDistributingThem() {
        Expr source = new NumberExpr(1), target = new NumberExpr(1);
        for (int index = 0; index < 20; index++) {
            Expr value = new VariableExpr("value" + index);
            source = mul(source, add(mod(value, N), FOUR));
            target = mul(target, add(value, FOUR));
        }
        // Expanding these products would require 2^20 terms; congruent operands suffice.
        assertTrue(domain().verifyEquivalent(List.of(mod(source, N)), List.of(mod(target, N))).accepted());
    }

    @Test void oversizedArithmeticProofsFailClosed() {
        Expr deep = mod(X, N);
        for (int index = 0; index < 110; index++) deep = add(deep, FOUR);
        Expr deepOutput = mod(deep, N);
        assertFalse(domain().verifyEquivalent(List.of(deepOutput), List.of(deepOutput)).accepted());
        assertThrows(IllegalArgumentException.class, () -> domain().generate(List.of(deepOutput), 64));

        Expr wide = mod(X, N);
        for (int level = 0; level < 11; level++) wide = add(wide, wide);
        Expr wideOutput = mod(wide, N);
        assertFalse(domain().verifyEquivalent(List.of(wideOutput), List.of(wideOutput)).accepted());
        assertThrows(IllegalArgumentException.class, () -> domain().generate(List.of(wideOutput), 64));
    }

    @Test void extraArithmeticTraversalCannotDiscardExistingBoundedProposals() {
        Expr nested = X, oneReductionRemoved = X;
        for (int index = 0; index < 63; index++) {
            oneReductionRemoved = nested;
            nested = mod(nested, N);
        }
        Expr source = nested, existingTarget = oneReductionRemoved;

        var generated = assertDoesNotThrow(() -> domain().generate(List.of(source), 64));

        assertTrue(generated.rewrites().stream().anyMatch(rewrite -> rewrite.outputs().equals(List.of(existingTarget))),
            "Additional bounded exploration must preserve the already generated same-modulus reduction");
        assertTrue(generated.rewrites().size() <= 64);
        assertFalse(generated.complete(), "Traversal exhaustion must remain visible to callers");
    }

    private static ModularComputationDomain domain() {
        return new ModularComputationDomain(Set.of(), Set.of("n", "m"), Set.of());
    }
    private static Expr add(Expr left, Expr right) { return new BinaryExpr(left, ADD, right); }
    private static Expr sub(Expr left, Expr right) { return new BinaryExpr(left, SUB, right); }
    private static Expr mul(Expr left, Expr right) { return new BinaryExpr(left, MUL, right); }
    private static Expr mod(Expr value, Expr modulus) { return new FunctionExpr("mod", List.of(value, modulus)); }
    private static Expr modmul(Expr left, Expr right, Expr modulus) { return new FunctionExpr("modmul", List.of(left, right, modulus)); }
    private static Expr pow(Expr base, Expr exponent, Expr modulus) { return new FunctionExpr("modpow", List.of(base, exponent, modulus)); }
}
