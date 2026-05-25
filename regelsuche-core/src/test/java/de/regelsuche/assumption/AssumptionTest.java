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
}
