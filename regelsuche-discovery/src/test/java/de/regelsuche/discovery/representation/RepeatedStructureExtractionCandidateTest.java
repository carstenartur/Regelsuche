package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RepeatedStructureExtractionCandidateTest {
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
                    occurrence("/0", "x", 1),
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
        assertThrows(IllegalArgumentException.class, () ->
            new RepeatedStructureExtractionCandidate.Policy(2, 2, 1, 1, 0));
    }

    @Test
    void sharingCostRejectsUnbalancedAndIncorrectMaterialEvidence() {
        assertThrows(IllegalArgumentException.class, () ->
            new RepeatedStructureExtractionCandidate.SharingCost(
                0, 1, 1, 2, -2, 1, false));
        assertThrows(IllegalArgumentException.class, () ->
            new RepeatedStructureExtractionCandidate.SharingCost(
                6, 4, 2, 7, -1, 1, false));
        assertThrows(IllegalArgumentException.class, () ->
            new RepeatedStructureExtractionCandidate.SharingCost(
                6, 4, 2, 6, 1, 1, true));
        assertThrows(IllegalArgumentException.class, () ->
            new RepeatedStructureExtractionCandidate.SharingCost(
                6, 4, 2, 6, 0, 1, true));
    }

    @Test
    void createRejectsMissingOccurrencesAndPolicy() {
        RepeatedStructureExtractionCandidate.Policy policy =
            RepeatedStructureExtractionCandidate.Policy.standard();
        List<RepeatedStructureExtractionCandidate.Occurrence> occurrences =
            List.of(
                occurrence("/0", "x + 1", 3),
                occurrence("/1", "x + 1", 3)
            );

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
