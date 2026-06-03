package de.regelsuche.assumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AssumptionTest {
    @Test
    void contextDedupesByExpression() {
        AssumptionContext context = new AssumptionContext();
        context.add(Assumption.nonZero("b"));
        context.add(Assumption.nonZero("b"));
        context.add(Assumption.nonZero("c"));
        assertEquals(2, context.snapshot().size());
        assertTrue(context.snapshot().stream().anyMatch(a -> a.expression().equals("b != 0")));
        assertTrue(context.snapshot().stream().anyMatch(a -> a.expression().equals("c != 0")));
    }

    @Test
    void positiveAndNonZeroAreDifferentExpressions() {
        AssumptionContext context = new AssumptionContext();
        context.add(Assumption.positive("x"));
        context.add(Assumption.nonZero("x"));
        assertEquals(2, context.snapshot().size());
        assertNotEquals(context.snapshot().get(0).expression(), context.snapshot().get(1).expression());
    }

    @Test
    void assumptionSignatureNormalizesAndDeduplicatesExpressions() {
        AssumptionSignature signature = AssumptionSignature.ofExpressions(
            java.util.List.of(" b   != 0 ", "0 != b", "b≠0", "x > 0")
        );
        assertEquals(java.util.List.of("b != 0", "x > 0"), signature.normalizedAssumptions());
        assertEquals("b != 0;x > 0", signature.fingerprint());
    }

    @Test
    void normalizationPreservesCompoundOperators() {
        // >  must not match inside >=, and < must not match inside <=.
        // Without a negative-lookahead guard, "x >= 0" would become "x > = 0".
        AssumptionSignature sig = AssumptionSignature.ofExpressions(
            java.util.List.of("x >= 0", "y<=1", "a>b", "c < d")
        );
        assertEquals(java.util.List.of("a > b", "c < d", "x >= 0", "y <= 1"),
            sig.normalizedAssumptions());
        assertEquals("a > b;c < d;x >= 0;y <= 1", sig.fingerprint());
    }

    @Test
    void integerKnowledgeImpliesRationalButUnknownStaysUnknown() {
        assertEquals(AssumptionTruthValue.TRUE,
            Assumption.rational("n").truthValueUnder(java.util.List.of(Assumption.integer("n"))));
        assertEquals(AssumptionTruthValue.UNKNOWN,
            Assumption.real("x").truthValueUnder(java.util.List.of(Assumption.unknown("x"))));
    }
}
