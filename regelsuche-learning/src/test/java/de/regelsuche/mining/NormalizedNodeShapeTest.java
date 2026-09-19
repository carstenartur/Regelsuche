package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class NormalizedNodeShapeTest {
    @Test
    void sharedFunctionConstructorAllowsChildrenToBeGeneralizedLocally() {
        var x = NormalizedNode.variable("x");
        var plain = NormalizedNode.function("f", List.of(NormalizedNode.number(15), x));
        var compound = NormalizedNode.function("f", List.of(NormalizedNode.number(28),
            NormalizedNode.add(List.of(x, NormalizedNode.number(1)))));
        assertTrue(plain.sameShape(compound));
        assertFalse(plain.equals(compound));
    }

    @Test
    void differingFunctionNamesAndAritiesDoNotShareAConstructor() {
        var x = NormalizedNode.variable("x");
        var f = NormalizedNode.function("f", List.of(x));
        assertFalse(f.sameShape(NormalizedNode.function("g", List.of(x))));
        assertFalse(f.sameShape(NormalizedNode.function("f", List.of(x, x))));
    }

    @Test
    void operatorHeadsArePreservedWithoutConfusingDifferentOperators() {
        var x = NormalizedNode.variable("x");
        var power = NormalizedNode.pow(x, NormalizedNode.number(2));
        var compoundPower = NormalizedNode.pow(
            NormalizedNode.add(List.of(x, NormalizedNode.number(1))), NormalizedNode.number(3));
        assertTrue(power.sameShape(compoundPower));
        assertFalse(power.sameShape(NormalizedNode.add(List.of(x, NormalizedNode.number(2)))));
    }

    @Test
    void leafIdentityAndPlaceholderDistinctionsArePreserved() {
        var x = NormalizedNode.variable("x");
        assertTrue(x.sameShape(NormalizedNode.variable("x")));
        assertFalse(x.sameShape(NormalizedNode.variable("y")));
        assertFalse(x.sameShape(NormalizedNode.placeholder("x")));
        assertTrue(NormalizedNode.number(3).sameShape(NormalizedNode.number(5)));
    }
}
