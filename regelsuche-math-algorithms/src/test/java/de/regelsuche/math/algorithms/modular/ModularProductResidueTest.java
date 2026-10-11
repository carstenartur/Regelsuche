package de.regelsuche.math.algorithms.modular;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Reduction at the enclosing product is required; bare outputs must stay normalized. */
class ModularProductResidueTest {
    private static final Expr A = new VariableExpr("a"), E = new VariableExpr("e"), Q = new VariableExpr("q");
    private static ModularComputationDomain domain() {
        return new ModularComputationDomain(Set.of("e"), Set.of("q"), Set.of());
    }
    private static Expr pow(Expr exponent) { return new FunctionExpr("modpow", List.of(A, exponent, Q)); }
    private static Expr mul(Expr x, Expr y, Expr q) { return new FunctionExpr("modmul", List.of(x, y, q)); }

    @Test void rawBaseIsValidOnlyInsideTheMatchingModularProduct() {
        var source = List.of(mul(pow(E), pow(new NumberExpr(1)), Q));
        var target = List.of(mul(pow(E), A, Q));
        assertTrue(domain().verifyEquivalent(source, target).accepted());
        assertTrue(domain().verifyEquivalent(target, source).accepted());
        assertFalse(domain().verifyEquivalent(List.of(pow(new NumberExpr(1))), List.of(A)).accepted());
    }
    @Test void generationRemovesOnlyTheUnitPowerUseInsideTheProduct() {
        Expr unit = pow(new NumberExpr(1));
        var source = List.of(unit, mul(pow(E), unit, Q));
        var expected = List.of(unit, mul(pow(E), A, Q));
        assertTrue(domain().generate(source, 64).rewrites().stream().anyMatch(r -> r.outputs().equals(expected)));
        assertTrue(domain().verifyEquivalent(source, expected).accepted());
    }
    @Test void generatedCandidatesRetainTheUnitPowerWhenItsModulusDiffers() {
        Expr other = new VariableExpr("other");
        var d = new ModularComputationDomain(Set.of("e"), Set.of("q", "other"), Set.of());
        var source = List.of(mul(pow(E), new FunctionExpr("modpow", List.of(A, new NumberExpr(1), other)), Q));
        assertFalse(d.verifyEquivalent(source, List.of(mul(pow(E), A, Q))).accepted());
        assertTrue(d.generate(source, 64).rewrites().stream().noneMatch(r -> r.outputs().equals(List.of(mul(pow(E), A, Q)))));
    }
    @Test void rawBareBaseCannotBeJustifiedByTheOtherOutput() {
        var source = List.of(pow(new NumberExpr(1)), mul(pow(E), pow(new NumberExpr(1)), Q));
        assertFalse(domain().verifyEquivalent(source, List.of(A, mul(pow(E), A, Q))).accepted());
    }
}
