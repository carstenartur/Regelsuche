package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves that structural representation correspondence, unlike value
 * equivalence, does not credit the frozen Sophie Germain source
 * ({@code x^4 + 4*y^4}) with reaching its factorized representation class
 * at depth 0, while still recognizing genuinely reached, AC-reordered
 * factorized occurrences at depth &gt; 0.
 */
class RepresentationCorrespondenceClassifierTest {
    private static final String SOPHIE_GERMAIN_SOURCE = "x^4 + 4*y^4";
    private static final String SOPHIE_GERMAIN_TARGET =
        "(x^2 + 2*y^2 - 2*x*y) * (x^2 + 2*y^2 + 2*x*y)";
    private static final String SOPHIE_GERMAIN_TARGET_REORDERED =
        "(x^2 + 2*y^2 + 2*x*y) * (x^2 + 2*y^2 - 2*x*y)";
    private static final String SOPHIE_GERMAIN_PREPARATION =
        "(x^2 + 2*y^2)^2 - (2*x*y)^2";

    private final RepresentationCorrespondenceClassifier classifier =
        new RepresentationCorrespondenceClassifier();

    @Test
    void doesNotCreditValueEquivalentSourceAsSameRepresentationClass() {
        assertDifferentRepresentation(
            SOPHIE_GERMAIN_SOURCE, SOPHIE_GERMAIN_TARGET);
    }

    @Test
    void treatsAcReorderedFactorizationAsSameRepresentationClass() {
        assertSameRepresentation(
            SOPHIE_GERMAIN_TARGET_REORDERED, SOPHIE_GERMAIN_TARGET);
    }

    @Test
    void treatsAssociativeCommutativeAdditionAsSameRepresentationClass() {
        assertSameRepresentation("a + (b + c)", "c + a + b");
        assertSameRepresentation("a - b + c", "c + a - b");
    }

    @Test
    void treatsAssociativeCommutativeMultiplicationAsSameRepresentationClass() {
        assertSameRepresentation("a * (b * c)", "c * a * b");
    }

    @Test
    void preservesRepeatedTermCompressionAsRepresentationChange() {
        assertDifferentRepresentation("x + x", "2 * x");
    }

    @Test
    void preservesRepeatedFactorCompressionAsRepresentationChange() {
        assertDifferentRepresentation("x * x", "x^2");
    }

    @Test
    void preservesNeutralTermsAsRepresentationChanges() {
        assertDifferentRepresentation("x + 0", "x");
        assertDifferentRepresentation("x * 1", "x");
    }

    @Test
    void reportsSourceAlreadyMatchingAtDepthZeroAsFalsePositive() {
        var evidence = classifier.evaluateTrace(
            List.of(new RepresentationCorrespondenceClassifier.TraceStep(
                0, SOPHIE_GERMAIN_TARGET_REORDERED)),
            SOPHIE_GERMAIN_TARGET);

        assertEquals(
            RepresentationCorrespondenceClassifier.RediscoveryStatus
                .SOURCE_ALREADY_MATCHES_FALSE_POSITIVE,
            evidence.status());
        assertEquals(0, evidence.matchedDepth());
    }

    @Test
    void reportsGenuineRediscoveryAtPositiveDepth() {
        var evidence = classifier.evaluateTrace(
            List.of(
                new RepresentationCorrespondenceClassifier.TraceStep(
                    0, SOPHIE_GERMAIN_SOURCE),
                new RepresentationCorrespondenceClassifier.TraceStep(
                    1, SOPHIE_GERMAIN_PREPARATION),
                new RepresentationCorrespondenceClassifier.TraceStep(
                    2, SOPHIE_GERMAIN_TARGET_REORDERED)),
            SOPHIE_GERMAIN_TARGET);

        assertEquals(
            RepresentationCorrespondenceClassifier.RediscoveryStatus
                .REPRESENTATION_REDISCOVERED,
            evidence.status());
        assertEquals(2, evidence.matchedDepth());
        assertEquals(
            SOPHIE_GERMAIN_TARGET_REORDERED, evidence.matchedExpression());
    }

    @Test
    void reportsNotReachedWhenNoStepMatchesRepresentationClass() {
        var evidence = classifier.evaluateTrace(
            List.of(
                new RepresentationCorrespondenceClassifier.TraceStep(
                    0, SOPHIE_GERMAIN_SOURCE),
                new RepresentationCorrespondenceClassifier.TraceStep(
                    1, SOPHIE_GERMAIN_PREPARATION)),
            SOPHIE_GERMAIN_TARGET);

        assertEquals(
            RepresentationCorrespondenceClassifier.RediscoveryStatus
                .NOT_REACHED,
            evidence.status());
        assertNull(evidence.matchedDepth());
        assertNull(evidence.matchedExpression());
    }

    @Test
    void requiresNonEmptyTrace() {
        assertTrue(assertThrows(
            IllegalArgumentException.class,
            () -> classifier.evaluateTrace(List.of(), SOPHIE_GERMAIN_TARGET))
            .getMessage().contains("trace must not be empty"));
    }

    @Test
    void requiresSourceOccurrenceAtDepthZero() {
        assertTrue(assertThrows(
            IllegalArgumentException.class,
            () -> classifier.evaluateTrace(
                List.of(new RepresentationCorrespondenceClassifier.TraceStep(
                    1, SOPHIE_GERMAIN_TARGET)),
                SOPHIE_GERMAIN_TARGET))
            .getMessage().contains("depth zero"));
    }

    @Test
    void selectsShallowestMatchDeterministically() {
        var evidence = classifier.evaluateTrace(
            List.of(
                new RepresentationCorrespondenceClassifier.TraceStep(
                    0, SOPHIE_GERMAIN_SOURCE),
                new RepresentationCorrespondenceClassifier.TraceStep(
                    3, SOPHIE_GERMAIN_TARGET),
                new RepresentationCorrespondenceClassifier.TraceStep(
                    2, SOPHIE_GERMAIN_TARGET_REORDERED)),
            SOPHIE_GERMAIN_TARGET);

        assertEquals(
            RepresentationCorrespondenceClassifier.RediscoveryStatus
                .REPRESENTATION_REDISCOVERED,
            evidence.status());
        assertEquals(2, evidence.matchedDepth());
        assertNotNull(evidence.matchedExpression());
    }

    private void assertSameRepresentation(String left, String right) {
        assertEquals(
            RepresentationCorrespondenceClassifier.Correspondence
                .SAME_REPRESENTATION_CLASS,
            classifier.classify(left, right));
    }

    private void assertDifferentRepresentation(String left, String right) {
        assertEquals(
            RepresentationCorrespondenceClassifier.Correspondence
                .DIFFERENT_REPRESENTATION_CLASS,
            classifier.classify(left, right));
    }
}
