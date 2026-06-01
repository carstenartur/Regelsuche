package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LinearOffsetSimplificationRuleTest {
    private final AstRewriteTransformationEngine engine = new AstRewriteTransformationEngine();

    @Test
    void simplifiesGenericLinearOffsets() {
        assertGenerated("(x + 3) - 2", "x + 1");
        assertGenerated("(x + 3) + 2", "x + 5");
    }

    @Test
    void simplifiesOffsetsInsideProductsOneStepAtATime() {
        List<String> candidates = engine.transform("((x + 3) - 2) * ((x + 3) + 2)").stream()
            .map(Transformation::transformedExpression)
            .toList();

        assertTrue(candidates.contains("(x + 1) * (x + 3 + 2)")
            || candidates.contains("(x + 1) * ((x + 3) + 2)"), candidates.toString());
    }

    private void assertGenerated(String input, String expected) {
        List<String> candidates = engine.transform(input).stream()
            .map(Transformation::transformedExpression)
            .toList();
        assertTrue(candidates.contains(expected), candidates.toString());
    }
}
