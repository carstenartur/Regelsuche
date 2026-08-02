package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class AstRewriteTransformationEnginesTest {
    @Test
    void productionSelectionUsesPreparedBackend() {
        assertEquals(
            AstRewriteTransformationEngines.Backend.PREPARED,
            AstRewriteTransformationEngines.productionBackend()
        );
        assertInstanceOf(
            PreparedAstRewriteTransformationEngine.class,
            AstRewriteTransformationEngines.production()
        );
    }

    @Test
    void referenceBackendRemainsExplicitlySelectable() {
        assertInstanceOf(
            AstRewriteTransformationEngine.class,
            AstRewriteTransformationEngines.reference()
        );
        assertInstanceOf(
            AstRewriteTransformationEngine.class,
            AstRewriteTransformationEngines.create(
                AstRewriteTransformationEngines.Backend.REFERENCE
            )
        );
    }

    @Test
    void selectedBackendsRetainOrderedTransformationParity() {
        List<RewriteRule> rules = AstRewriteTransformationEngine.defaultRules();
        TransformationEngine reference = AstRewriteTransformationEngines.reference(
            rules,
            12,
            80
        );
        TransformationEngine prepared = AstRewriteTransformationEngines.production(
            rules,
            12,
            80
        );

        for (String expression : List.of(
            "(x + 0) * 1",
            "a * (b + c)",
            "x^2 - y^2",
            "sin(x + 0)"
        )) {
            assertEquals(
                reference.transform(expression),
                prepared.transform(expression),
                expression
            );
        }
    }

    @Test
    void nullBackendIsRejectedRatherThanSilentlyFallingBack() {
        assertThrows(
            NullPointerException.class,
            () -> AstRewriteTransformationEngines.create(null)
        );
    }
}
