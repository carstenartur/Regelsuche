package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheorySubsumptionClassifierTest {
    private final PolynomialTheorySubsumptionClassifier classifier =
        new PolynomialTheorySubsumptionClassifier();

    @Test
    void classifiesGeneratedSophieGermainIdentityAsTheorySubsumed() {
        PolynomialTheorySubsumptionClassifier.Classification result =
            classifier.classify(
                "x^4 + 4*y^4",
                "(x^2 - 2*x*y + 2*y^2)"
                    + " * (x^2 + 2*x*y + 2*y^2)");

        assertTrue(result.subsumed(), result.toString());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.THEORY_SUBSUMED,
            result.status());
        assertEquals(
            PolynomialDecompositionSynthesisOperator.METHOD_ID,
            result.theoryMethodId());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.ProjectInventoryNovelty
                .NOT_EVALUATED,
            result.projectInventoryNovelty());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.RetentionDisposition
                .DERIVED_MACRO_CACHE_ONLY,
            result.retentionDisposition());
        assertFalse(result.sourceExpression().isBlank());
        assertTrue(result.certificateHash().matches("sha256:[0-9a-f]{64}"));
        assertFalse(result.derivedExpression().isBlank());
        assertFalse(result.applicationKey().isBlank());
        assertTrue(result.consideredConfigurations() > 0);
    }

    @Test
    void acceptsAssociativeCommutativeFactorOrderWithoutBroadEquivalence() {
        PolynomialTheorySubsumptionClassifier.Classification result =
            classifier.classify(
                "x^4 + 5*x^2*y^2 + 4*y^4",
                "(x^2 + 4*y^2) * (x^2 + y^2)");

        assertTrue(result.subsumed(), result.toString());
    }

    @Test
    void rejectsNearMissAndUnsupportedDomainsFailClosed() {
        PolynomialTheorySubsumptionClassifier.Classification nearMiss =
            classifier.classify(
                "x^4 + 4*y^4",
                "(x^2 - 2*x*y + 2*y^2)"
                    + " * (x^2 + 2*x*y + 3*y^2)");
        PolynomialTheorySubsumptionClassifier.Classification unsupported =
            classifier.classify(
                "x^3 + y^3",
                "(x + y) * (x^2 - x*y + y^2)");

        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.NOT_SUBSUMED,
            nearMiss.status());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.UNSUPPORTED,
            unsupported.status());
        assertTrue(nearMiss.sourceExpression().isEmpty());
        assertTrue(nearMiss.certificateHash().isEmpty());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.RetentionDisposition.NONE,
            nearMiss.retentionDisposition());
    }

    @Test
    void exhaustedTheoryBudgetRemainsInconclusive() {
        PolynomialDecompositionSynthesisOperator bounded =
            new PolynomialDecompositionSynthesisOperator(
                new PolynomialSemanticView(
                    new PolynomialSemanticView.Budget(2, 4, 16, 256)),
                6,
                32,
                1);
        PolynomialTheorySubsumptionClassifier boundedClassifier =
            new PolynomialTheorySubsumptionClassifier(
                bounded,
                new ExpressionCanonicalizer(),
                new ExpressionParser());

        PolynomialTheorySubsumptionClassifier.Classification result =
            boundedClassifier.classify(
                "x^4 + 4*y^4",
                "(x^2 - 2*x*y + 2*y^2)"
                    + " * (x^2 + 2*x*y + 2*y^2)");

        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.BUDGET_INCONCLUSIVE,
            result.status());
        assertFalse(result.subsumed());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.RetentionDisposition.NONE,
            result.retentionDisposition());
    }

    @Test
    void cacheRetainsDistinctLineagesForOneTheoryDerivedMacro() {
        PolynomialTheorySubsumptionClassifier.Classification result =
            classifier.classify(
                "A^4 + 4*B^4",
                "(A^2 - 2*A*B + 2*B^2)"
                    + " * (A^2 + 2*A*B + 2*B^2)");
        PolynomialDerivedMacroCache cache =
            new PolynomialDerivedMacroCache(2);

        PolynomialDerivedMacroCache.Entry first = cache.retain(
            result,
            List.of(PolynomialDecompositionSynthesisOperator.RULE_ID),
            List.of("path:sophie-replay", "generation:1"));
        PolynomialDerivedMacroCache.Entry duplicate = cache.retain(
            result,
            List.of(PolynomialDecompositionSynthesisOperator.RULE_ID),
            List.of("path:sophie-replay", "generation:1"));
        PolynomialDerivedMacroCache.Entry secondLineage = cache.retain(
            result,
            List.of("ast_expand", "ast_square_difference_factor"),
            List.of("path:sophie-replay-2", "generation:2"));

        assertEquals(first, duplicate);
        assertEquals(1, cache.size());
        assertEquals(2, secondLineage.lineages().size());
        assertEquals(result.sourceExpression(), secondLineage.leftPattern());
        assertEquals(result.derivedExpression(), secondLineage.rightPattern());
        assertEquals(result.certificateHash(),
            secondLineage.classification().certificateHash());
        assertEquals(
            List.of(PolynomialDecompositionSynthesisOperator.RULE_ID),
            secondLineage.lineages().getFirst().primitiveRuleIds());
        assertEquals(
            List.of("path:sophie-replay", "generation:1"),
            secondLineage.lineages().getFirst().sourceProvenance());
        assertEquals(
            PolynomialDerivedMacroCache.PURPOSE,
            secondLineage.purpose());
    }

    @Test
    void cacheEvictionIsDeterministicAndRejectsNonSubsumedEntries() {
        PolynomialDerivedMacroCache cache =
            new PolynomialDerivedMacroCache(2);
        PolynomialDerivedMacroCache.Entry first = retain(
            cache,
            "x^4 + 4*y^4",
            "(x^2 - 2*x*y + 2*y^2)"
                + " * (x^2 + 2*x*y + 2*y^2)",
            "case:sophie");
        PolynomialDerivedMacroCache.Entry second = retain(
            cache,
            "x^4 + 5*x^2*y^2 + 4*y^4",
            "(x^2 + y^2) * (x^2 + 4*y^2)",
            "case:even");
        PolynomialDerivedMacroCache.Entry third = retain(
            cache,
            "x^4 + x^2*y^2 + y^4",
            "(x^2 - x*y + y^2) * (x^2 + x*y + y^2)",
            "case:cyclotomic");

        assertEquals(List.of(second, third), cache.entries());
        assertTrue(cache.find(first.id()).isEmpty());
        assertTrue(cache.find(second.id()).isPresent());
        assertTrue(cache.find(third.id()).isPresent());

        PolynomialTheorySubsumptionClassifier.Classification nearMiss =
            classifier.classify(
                "x^4 + 4*y^4",
                "(x^2 + y^2) * (x^2 + 4*y^2)");
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
            List.of(PolynomialDecompositionSynthesisOperator.RULE_ID),
            List.of(provenance));
    }
}
