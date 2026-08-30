package de.regelsuche.math.algorithms.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.polynomial.ExactFactorizationTransformationPipeline;
import de.regelsuche.transform.PolynomialDerivedMacroCache;
import de.regelsuche.transform.PolynomialTheorySubsumptionClassifier;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheorySubsumptionClassifierNativeIntegrationTest {
    private final PolynomialTheorySubsumptionClassifier classifier =
        new PolynomialTheorySubsumptionClassifier(
            NativeUnivariateFactorizationEngine.boundedRationals());

    @Test
    void classifiesNativeExactFactorizationModuloFactorOrder() {
        PolynomialTheorySubsumptionClassifier.Classification result =
            classifier.classify(
                "x^2 - 1",
                "(x + 1) * (x - 1)");

        assertTrue(result.subsumed(), result.toString());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.THEORY_SUBSUMED,
            result.status());
        assertEquals(
            ExactFactorizationTransformationPipeline.TRANSFORMATION_ID,
            result.theoryMethodId());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.RetentionDisposition
                .DERIVED_MACRO_CACHE_ONLY,
            result.retentionDisposition());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.ProjectInventoryNovelty
                .NOT_EVALUATED,
            result.projectInventoryNovelty());
        assertFalse(result.sourceExpression().isBlank());
        assertFalse(result.derivedExpression().isBlank());
        assertTrue(result.certificateHash().matches("sha256:[0-9a-f]{64}"));
        assertTrue(result.applicationKey().matches("sha256:[0-9a-f]{64}"));
        assertTrue(result.workUnits() > 0);
    }

    @Test
    void classifiesGeneralDegreeSixFactorizationBeyondQuarticControl() {
        PolynomialTheorySubsumptionClassifier.Classification result =
            classifier.classify(
                "x^6 - 1",
                "(x - 1) * (x + 1)"
                    + " * (x^2 - x + 1) * (x^2 + x + 1)");

        assertTrue(result.subsumed(), result.toString());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.THEORY_SUBSUMED,
            result.status());
    }

    @Test
    void comparesDecimalAndRenderedRationalCoefficientExactly() {
        PolynomialTheorySubsumptionClassifier.Classification result =
            classifier.classify(
                "1/2*x^2 - 1/2",
                "0.5 * (x + 1) * (x - 1)");

        assertTrue(result.subsumed(), result.toString());
        assertTrue(result.derivedExpression().contains("1 / 2"));
    }

    @Test
    void combinesConstantsWithinAssociativeGeneratedRepresentation() {
        PolynomialTheorySubsumptionClassifier.Classification result =
            classifier.classify(
                "x^2 + 5*x - 6",
                "(x - 1) * (x + 3 + 3)");

        assertTrue(result.subsumed(), result.toString());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.THEORY_SUBSUMED,
            result.status());
    }

    @Test
    void rejectsNearMissAndEquivalentButUngeneratedRepresentation() {
        PolynomialTheorySubsumptionClassifier.Classification nearMiss =
            classifier.classify(
                "x^2 - 1",
                "(x + 2) * (x - 1)");
        PolynomialTheorySubsumptionClassifier.Classification expandedSource =
            classifier.classify(
                "x^2 - 1",
                "x^2 - 1");

        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.NOT_SUBSUMED,
            nearMiss.status());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.NOT_SUBSUMED,
            expandedSource.status(),
            "mathematical equivalence alone is not generated-representation evidence");
        assertFalse(nearMiss.subsumed());
        assertFalse(expandedSource.subsumed());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.RetentionDisposition.NONE,
            expandedSource.retentionDisposition());
        assertTrue(expandedSource.certificateHash().isEmpty());
    }

    @Test
    void cacheDeduplicatesCanonicalSourceWhileRetainingLineages() {
        PolynomialTheorySubsumptionClassifier.Classification compact =
            classifier.classify(
                "x^2 - 1",
                "(x - 1) * (x + 1)");
        PolynomialTheorySubsumptionClassifier.Classification spaced =
            classifier.classify(
                "  x ^ 2 - 1  ",
                "(x + 1) * (x - 1)");
        assertTrue(compact.subsumed(), compact.toString());
        assertTrue(spaced.subsumed(), spaced.toString());
        assertEquals(
            compact.sourceExpression(),
            spaced.sourceExpression());
        assertEquals(
            compact.certificateHash(),
            spaced.certificateHash());

        PolynomialDerivedMacroCache cache =
            new PolynomialDerivedMacroCache(2);
        PolynomialDerivedMacroCache.Entry first = cache.retain(
            compact,
            List.of(
                ExactFactorizationTransformationPipeline.TRANSFORMATION_ID),
            List.of("path:compact"));
        PolynomialDerivedMacroCache.Entry second = cache.retain(
            spaced,
            List.of(
                ExactFactorizationTransformationPipeline.TRANSFORMATION_ID),
            List.of("path:spaced"));

        assertEquals(1, cache.size());
        assertEquals(first.id(), second.id());
        assertEquals(2, second.lineages().size());
        assertEquals(
            compact.sourceExpression(),
            second.leftPattern());
        assertEquals(
            compact.derivedExpression(),
            second.rightPattern());
    }

    @Test
    void cacheEvictionRemainsDeterministicForExactClassifications() {
        PolynomialDerivedMacroCache cache =
            new PolynomialDerivedMacroCache(2);
        PolynomialDerivedMacroCache.Entry first = retain(
            cache,
            "x^2 - 1",
            "(x - 1) * (x + 1)",
            "case:one");
        PolynomialDerivedMacroCache.Entry second = retain(
            cache,
            "x^2 - 4",
            "(x - 2) * (x + 2)",
            "case:four");
        PolynomialDerivedMacroCache.Entry third = retain(
            cache,
            "x^2 - 9",
            "(x - 3) * (x + 3)",
            "case:nine");

        assertEquals(List.of(second, third), cache.entries());
        assertTrue(cache.find(first.id()).isEmpty());
        assertTrue(cache.find(second.id()).isPresent());
        assertTrue(cache.find(third.id()).isPresent());

        PolynomialTheorySubsumptionClassifier.Classification nearMiss =
            classifier.classify(
                "x^2 - 1",
                "(x - 2) * (x + 2)");
        assertThrows(IllegalArgumentException.class, () -> cache.retain(
            nearMiss,
            List.of("candidate"),
            List.of("case:near-miss")));
    }

    private PolynomialDerivedMacroCache.Entry retain(
        PolynomialDerivedMacroCache cache,
        String left,
        String right,
        String provenance
    ) {
        PolynomialTheorySubsumptionClassifier.Classification result =
            classifier.classify(left, right);
        assertTrue(result.subsumed(), result.toString());
        return cache.retain(
            result,
            List.of(
                ExactFactorizationTransformationPipeline.TRANSFORMATION_ID),
            List.of(provenance));
    }
}
