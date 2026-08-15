package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                    .anyMatch(occurrence -> occurrence.expression()
                        .equals("x + y")))
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
    void rejectsBlankSourceAndMissingPolicy() {
        assertThrows(IllegalArgumentException.class, () ->
            new RepeatedStructureExtractor().extract(" "));
        assertThrows(NullPointerException.class, () ->
            new RepeatedStructureExtractor(null));
    }
}
