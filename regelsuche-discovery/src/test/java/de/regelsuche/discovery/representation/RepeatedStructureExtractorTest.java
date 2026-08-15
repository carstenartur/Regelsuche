package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RepeatedStructureExtractorTest {
    @Test
    void extractsOccurrencePreservingMaterialSharingCandidate() {
        RepeatedStructureExtractor extractor = new RepeatedStructureExtractor();

        List<RepeatedStructureExtractionCandidate> candidates =
            extractor.extractMaterial(
                "(sin(x + 1) + cos(x + 1))"
                    + " * (sin(x + 1) + cos(x + 1))");

        RepeatedStructureExtractionCandidate candidate = candidates.get(0);
        assertEquals(
            RepresentationCandidateAssessment
                .TYPE_REPEATED_STRUCTURE_EXTRACTION,
            candidate.candidateType());
        assertEquals(
            "sin(x + 1) + cos(x + 1)",
            candidate.representativeExpression());
        assertEquals(
            List.of("/0", "/1"),
            candidate.occurrences().stream()
                .map(occurrence -> occurrence.path().canonical())
                .toList());
        assertEquals(List.of(9, 9), candidate.occurrences().stream()
            .map(RepeatedStructureExtractionCandidate.Occurrence::astNodeCount)
            .toList());
        assertEquals(18, candidate.sharingCost().repeatedTreeCost());
        assertEquals(10, candidate.sharingCost().definitionTreeCost());
        assertEquals(2, candidate.sharingCost().referenceTreeCost());
        assertEquals(12, candidate.sharingCost().explicitSharingTreeCost());
        assertEquals(6, candidate.sharingCost().netAstNodeSavings());
        assertTrue(candidate.material());
        assertTrue(candidate.identity().matches("sha256:[0-9a-f]{64}"));
        assertEquals(extractor.policy(), candidate.policy());
    }

    @Test
    void chargesAliasDefinitionAndReferencesInsteadOfGamingCompression() {
        RepeatedStructureExtractionCandidate candidate =
            new RepeatedStructureExtractor().extract(
                "(x + 1) * (x + 1)").get(0);

        assertEquals(6, candidate.sharingCost().repeatedTreeCost());
        assertEquals(4, candidate.sharingCost().definitionTreeCost());
        assertEquals(2, candidate.sharingCost().referenceTreeCost());
        assertEquals(6, candidate.sharingCost().explicitSharingTreeCost());
        assertEquals(0, candidate.sharingCost().netAstNodeSavings());
        assertFalse(candidate.material());
        assertTrue(new RepeatedStructureExtractor()
            .extractMaterial("(x + 1) * (x + 1)").isEmpty());
    }

    @Test
    void groupsAcEquivalentSyntaxOccurrencesWithoutLosingTheirPaths() {
        RepeatedStructureExtractionCandidate candidate =
            new RepeatedStructureExtractor().extract(
                "(x + y) * z + (y + x) * w").stream()
                .filter(value -> value.occurrences().stream()
                    .anyMatch(occurrence -> occcurrence.expression()
                        .equals("x + y"))
                .findFirst()
                .orElseThrow();

        assertEquals("x + y", candidate.representativeExpression());
        assertEquals(
            List.of("x + y", "y + x"),
            candidate.occurrences().stream()
                .map(RepeatedStructureExtractionCandidate.Occurrence::expression)
                .toList());
        assertEquals(
            List.of("/0/0", "/1/0"),
            candidate.occurrences().stream()
                .map(occurrence -> occurrence.path().canonical())
                .toList());
    }

    @Test
    void supportsFrozenHigherOccurrenceThreshold() {
        RepeatedStructureExtractionCandidate.Policy policy =
            new RepeatedStructureExtractionCandidate.Policy(3, 2, 1, 1, 1);
        RepeatedStructureExtractor extractor =
            new RepeatedStructureExtractor(policy);

        List<RepeatedStructureExtractionCandidate> candidates =
            extractor.extract("(x + 1) * (x + 1) * (x + 1)");

        assertEquals(1, candidates.size());
        assertEquals(3, candidates.get(0).occurrences().size());
        assertEquals(policy, extractor.policy());
    }

    @Test
    void excludesLeavesAndReturnsNoCandidateWithoutRepeatedSubtrees() {
        RepeatedStructureExtractor extractor = new RepeatedStructureExtractor();

        assertTrue(extractor.extract("x + x").isEmpty());
        assertTrue(extractor.extract("x + y").isEmpty());
    }

    @Test
    void normalizesInputAndProducesStableIdentity() {
        RepeatedStructureExtractor extractor = new RepeatedStructureExtractor();

        RepeatedStructureExtractionCandidate compact = extractor.extract(
            "(x+1)*(x+1)").get(0);
        RepeatedStructureExtractionCandidate spaced = extractor.extract(
            " ( x + 1 ) * ( x + 1 ) ").get(0);

        assertEquals("(x + 1) * (x + 1)", compact.sourceExpression());
        assertEquals(compact, spaced);
        assertEquals(compact.identity(), spaced.identity());
    }

    @Test
    void materialCandidatesSortBeforeLargerNonMaterialRepresentatives() {
        RepeatedStructureExtractionCandidate material = candidate(
            List.of(
                occurrence("/0", "sin(x + 1) + cos(x + 1)", 9),
                occurrence("/1", "sin(x + 1) + cos(x + 1)", 9)
            )
        );
        RepeatedStructureExtractionCandidate nonMaterial = candidate(
            List.of(
                occurrence("/0", "x + 1", 3),
                occurrence("/1", "x + 1", 3)
            )
        );

        assertTrue(material.compareTo(nonMaterial) < 0);
        assertTrue(nonMaterial.compareTo(material) > 0);
        assertEquals(List.of(material, nonMaterial),
            List.of(nonMaterial, material).stream().sorted().toList());
    }

    @Test
    void candidateIdentityAndPolicyHashAreContentSensitive() {
        RepeatedStructureExtractionCandidate.Policy standard =
            RepeatedStructureExtractionCandidate.Policy.standard();
        RepeatedStructureExtractionCandidate.Policy expensiveReferences =
            new RepeatedStructureExtractionCandidate.Policy(2, 2, 1, 2, 1);
        List<RepeatedStructureExtractionCandidate.Occurrence> occurrences =
            List.of(
                occurrence("/0", "x + 1", 3),
                occurrence("/1", "x + 1", 3)
            );

        RepeatedStructureExtractionCandidate first =
            RepeatedStructureExtractionCandidate.create(
                "(x + 1) * (x + 1)", "key", occurrences, standard);
        RepeatedStructureExtractionCandidate second =
            RepeatedStructureExtractionCandidate.create(
                "(x + 1) * (x + 1)",
                "key",
                occurrences,
                expensiveReferences
            );

        assertNotEquals(standard.contentHash(),
            expensiveReferences.contentHash());
        assertNotEquals(first.identity(), second.identity());
    }

    @Test
    void candidateRejectsDuplicateAndUndersizedOccurrences() {
        RepeatedStructureExtractionCandidate.Policy policy =
            RepeatedStructureExtractionCandidate.Policy.standard();

        assertThrows(IllegalArgumentException.class, () ->
            RepeatedStructureExtractionCandidate.create(
                "x", "key", List.of(
                    occurrence("/0", "x + 1", 3),
                    occurrence("/0", "x + 1", 3)
                ), policy));
        assertThrows(IllegalArgumentException.class, () ->
            RepeatedStructureExtractionCandidate.create(
                "x", "key", List.of(
                    occcurrence("/0", "x", 1),
                    occurrence("/1", "x", 1)
                ), policy));
        assertThrows(IllegalArgumentException.class, () ->
            RepeatedStructureExtractionCandidate.create(
                "x", "key", List.of(
                    occurrence("/0", "x + 1", 3)
                ), policy));
    }

    @Test
    void candidateRejectsForgedDerivedEvidence() {
        RepeatedStructureExtractionCandidate valid = candidate(List.of(
            occurrence("/0", "x + 1", 3),
            occurrence("/1", "x + 1", 3)
        ));

        assertThrows(IllegalArgumentException.class, () ->
            new RepeatedStructureExtractionCandidate(
                "sha256:" + "0".repeat(64),
                valid.sourceExpression(),
                valid.semanticValueKey(),
                valid.representativeExpression(),
                valid.occurrences(),
                valid.policy(),
                valid.sharingCost()
            ));
        assertThrows(IllegalArgumentException.class, () ->
            new RepeatedStructureExtractionCandidate(
                valid.identity(),
                valid.sourceExpression(),
                valid.semanticValueKey(),
                "y + 1",
                valid.occurrences(),
                valid.policy(),
                valid.sharingCost()
            ));
        RepeatedStructureExtractionCandidate.SharingCost forgedCost =
            new RepeatedStructureExtractionCandidate.SharingCost(
                6, 4, 4, 8, -2, 1, false);
        assertThrows(IllegalArgumentException.class, () ->
            new RepeatedStructureExtractionCandidate(
                valid.identity(),
                valid.sourceExpression(),
                valid.semanticValueKey(),
                valid.representativeExpression(),
                valid.occurrences(),
                valid.policy(),
                forgedCost
            ));
    }

    @Test
    void occurrenceAndPolicyRejectInvalidCostsAndThresholds() {
        assertThrows(IllegalArgumentException.class, () ->
            occurrence("/0", "x", 0));
        assertThrows(IllegalArgumentException.class, () ->
            new RepeatedStructureExtractionCandidate.Policy(1, 2, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () ->
            new RepeatedStructureExtractionCandidate.Policy(2, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () ->
            new RepeatedStructureExtractionCandidate.Policy(2, 2, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () ->
            new RepeatedStructureExtractionCandidate.Policy(2, 2, 1, 0, 1));
        assertThrows(IlegalArgumentException.class, () ->
            new RepeatedStructureExtractionCandidate.Policy(2, 2, 1, 1, 0));
    }

    @Test
    void sharingCostRejectsUnbalancedAndIncorrectMaterialEvidence() {
        assertThrows(IlegalArgumentException.class, () ->
            new RepeatedStructureExtractionCandidate.SharingCost(
                0, 1, 1, 2, -2, 1, false);
        assertThrows(IllegalArgumentException.class, () ->
            new RepeatedStructureExtractionCandidate.SharingCost(
                6, 4, 2, 7, -1, 1, false);
        assertThrows(IllegalArgumentException.class, () ->
            new RepeatedStructureExtractionCandidate.SharingCost(
                6, 4, 2, 6, 1, 1, true));
        assertThrows(IllegalArgumentException.class, () ->
            new RepeatedStructureExtractionCandidate.SharingCost(
                6, 4, 2, 6, 0, 1, true));
    }

    @Test
    void rejectsBlankSourceAndMissingConstructorDependencies() {
        RepeatedStructureExtractionCandidate.Policy policy =
            RepeatedStructureExtractionCandidate.Policy.standard();
        List<RepeatedStructureExtractionCandidate.Occurrence> occurrences =
            List.of(
                occurrence("/0", "x + 1", 3),
                occurrence("/1", "x + 1", 3)
            );

        assertThrows(IllegalArgumentException.class, () ->
            new RepeatedStructureExtractor().extract(" "));
        assertThrows(NullPointerException.class, () ->
            new RepeatedStructureExtractor(null));
        assertThrows(NullPointerException.class, () ->
            RepeatedStructureExtractionCandidate.create(
                "x", "key", null, policy));
        assertThrows(NullPointerException.class, () ->
            RepeatedStructureExtractionCandidate.create(
                "x", "key", occurrences, null));
    }

    private static RepeatedStructureExtractionCandidate candidate(
        List<RepeatedStructureExtractionCandidate.Occurrence> occurrences
    ) {
        return RepeatedStructureExtractionCandidate.create(
            "source", "semantic-key", occurrences,
            RepeatedStructureExtractionCandidate.Policy.standard());
    }

    private static RepeatedStructureExtractionCandidate.Occurrence occurrence(
        String path,
        String expression,
        int astNodeCount
    ) {
        ExpressionOccurrencePath occurrencePath = path.equals("/")
            ? ExpressionOccurrencePath.root()
            : new ExpressionOccurrencePath(
                path.substring(1).isEmpty()
                    ? List.of()
                    : java.util.Arrays.stream(path.substring(1).split("/"))
                        .map(Integer::parseInt)
                        .toList()
            );
        return new RepeatedStructureExtractionCandidate.Occurrence(
            occurrencePath, expression, astNodeCount);
    }
}
