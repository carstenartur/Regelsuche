package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class TransformationPrimitiveLineageTest {
    @Test
    void ordinaryTransformationDefaultsToOnePrimitiveStep() {
        Transformation transformation = new Transformation(
            "flat_rule",
            "y",
            RewriteKind.SIMPLIFY,
            false,
            -1,
            false,
            "flat_rule:x",
            List.of(),
            "core",
            "PROJECT");

        assertEquals(List.of("flat_rule"), transformation.primitiveRuleIds());
        assertEquals(1, transformation.primitiveStepCount());
    }

    @Test
    void composedTransformationRetainsOrderedRepeatedPrimitiveSteps() {
        Transformation transformation = new Transformation(
            "program:repeat[normalize -> normalize -> cleanup]",
            "z",
            RewriteKind.NORMALIZE,
            false,
            -3,
            false,
            "program:repeat:1",
            List.of(),
            "core",
            "PROJECT",
            List.of("normalize", "normalize", "cleanup"));

        assertEquals(
            List.of("normalize", "normalize", "cleanup"),
            transformation.primitiveRuleIds());
        assertEquals(3, transformation.primitiveStepCount());
    }

    @Test
    void emptyOrBlankPrimitiveLineageFailsClosed() {
        assertThrows(IllegalArgumentException.class, () ->
            new Transformation(
                "program:empty",
                "z",
                RewriteKind.NORMALIZE,
                false,
                0,
                false,
                "program:empty:1",
                List.of(),
                "core",
                "PROJECT",
                List.of()));
        assertThrows(IllegalArgumentException.class, () ->
            new Transformation(
                "program:blank",
                "z",
                RewriteKind.NORMALIZE,
                false,
                0,
                false,
                "program:blank:1",
                List.of(),
                "core",
                "PROJECT",
                List.of(" ")));
    }
}
