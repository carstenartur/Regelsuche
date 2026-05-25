package de.regelsuche.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscoveryRecordsTest {
    @Test
    void transformationDefaultsNullMetadataAndDefensivelyCopiesSteps() {
        List<TransformationStep> steps = new ArrayList<>();
        steps.add(step());

        DiscoveredTransformation transformation = new DiscoveredTransformation(
            "path-1",
            "x + 0",
            "x",
            steps,
            new ExpressionScore(5, 3, 1, 1, 0),
            new ExpressionScore(1, 1, 0, 0, 0),
            4,
            null,
            null,
            null
        );
        steps.clear();

        assertEquals(CandidateProofStatus.OBSERVED, transformation.validationStatus());
        assertEquals("", transformation.canonicalHash());
        assertEquals(1, transformation.steps().size());
    }

    @Test
    void stepRequiresCoreFieldsAndDefaultsExplanation() {
        TransformationStep step = new TransformationStep(
            0, "x + 0", "x", "add-zero", RewriteKind.SIMPLIFY, 5, 1, true, null);

        assertEquals("", step.explanation());
        assertThrows(IllegalArgumentException.class,
            () -> new TransformationStep(0, null, "x", "add-zero", RewriteKind.SIMPLIFY, 5, 1, true, null));
    }

    private static TransformationStep step() {
        return new TransformationStep(
            0, "x + 0", "x", "add-zero", RewriteKind.SIMPLIFY, 5, 1, true, "neutral element");
    }
}
