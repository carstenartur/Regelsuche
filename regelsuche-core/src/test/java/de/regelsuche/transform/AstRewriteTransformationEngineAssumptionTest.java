package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.rules.RationalRules;
import java.util.List;
import org.junit.jupiter.api.Test;

class AstRewriteTransformationEngineAssumptionTest {

    @Test
    void nestedRewriteRetainsAssumptionsFromTheMatchedSubtree() {
        AstRewriteTransformationEngine engine =
            new AstRewriteTransformationEngine(List.of(
                new RationalRules.CancelCommonFactorRule()));

        Transformation transformation = engine.transform(
            "q + (x*y)/(x*z)").stream()
            .filter(item -> "rational_cancel_common_factor"
                .equals(item.rule()))
            .findFirst()
            .orElseThrow();

        assertEquals("q + y / z", transformation.transformedExpression());
        assertEquals(List.of("x != 0", "z != 0"),
            transformation.assumptions());
    }
}
