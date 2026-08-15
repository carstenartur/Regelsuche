package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExactSharingRepresentationTest {
    @Test
    void materialCandidateBecomesExactReconstructableSharingEvidence() {
        RepeatedStructureExtractionCandidate candidate = materialCandidate();

        ExactSharingRepresentation representation =
            ExactSharingRepresentation.create(candidate);

        assertEquals(ExactSharingRepresentation.SCHEMA,
            representation.schema());
        assertEquals(candidate.identity(), representation.candidateIdentity());
        assertEquals(candidate.sourceExpression(),
            representation.sourceExpression());
        assertEquals(candidate.semanticValueKey(),
            representation.semanticValueKey());
        assertEquals(candidate.representativeExpression(),
            representation.definitionExpression());
        assertEquals(
            List.of("/0", "/1"),
            representation.referencePaths().stream()
                .map(ExpressionOccurrencePath::canonical)
                .toList()
        );
        assertEquals(candidate.policy(), representation.policy());
        assertEquals(candidate.sharingCost(), representation.sharingCost());
        assertEquals(representation.sourceTreeHash(),
            representation.expandedTreeHash());
        assertEquals(candidate.sourceExpression(),
            representation.reconstructSourceExpression());
        assertEquals(ExactSharingRepresentation.CLAIM_BOUNDARY,
            representation.claimBoundary());
        assertTrue(representation.identity().matches(
            "sha256:[0-9a-f]{64}"));
        assertEquals(representation.toCanonicalJson(),
            ExactSharingRepresentation.create(candidate).toCanonicalJson());
    }

    @Test
    void evidenceIdentityBindsCandidatePolicyCostAndPaths() {
        ExactSharingRepresentation representation =
            ExactSharingRepresentation.create(materialCandidate());
        var alternativePolicy =
            new RepeatedStructureExtractionCandidate.Policy(2, 2, 2, 1, 1);
        RepeatedStructureExtractionCandidate alternativeCandidate =
            new RepeatedStructureExtractor(alternativePolicy)
                .extractMaterial(representation.sourceExpression()).stream()
                .filter(candidate -> candidate.representativeExpression().equals(
                    representation.definitionExpression()))
                .findFirst()
                .orElseThrow();
        ExactSharingRepresentation alternative =
            ExactSharingRepresentation.create(alternativeCandidate);

        assertNotEquals(representation.identity(), alternative.identity());
        assertNotEquals(representation.policy().contentHash(),
            alternative.policy().contentHash());
    }

    @Test
    void nonMaterialCandidateCannotBecomeExactSharingEvidence() {
        RepeatedStructureExtractionCandidate candidate =
            new RepeatedStructureExtractor().extract(
                "(x + 1) * (x + 1)").getFirst();

        assertThrows(IllegalArgumentException.class,
            () -> ExactSharingRepresentation.create(candidate));
    }

    @Test
    void semanticAcGroupingDoesNotPretendToReconstructDifferentSyntax() {
        RepeatedStructureExtractionCandidate candidate =
            new RepeatedStructureExtractor().extractMaterial(
                "(sin(x + y) + cos(x + y))"
                    + " * (sin(y + x) + cos(y + x))"
            ).stream()
                .filter(value -> value.occurrences().stream()
                    .anyMatch(occurrence -> occurrence.expression().contains(
                        "sin(y + x)")))
                .findFirst()
                .orElseThrow();

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ExactSharingRepresentation.create(candidate)
        );
        assertTrue(exception.getMessage().contains(
            "presentation-identical"));
    }

    @Test
    void forgedCandidateThatDoesNotComeFromItsSourceIsRejected() {
        RepeatedStructureExtractionCandidate valid = materialCandidate();
        RepeatedStructureExtractionCandidate forged =
            RepeatedStructureExtractionCandidate.create(
                "(a + b) * (c + d)",
                valid.semanticValueKey(),
                valid.occurrences(),
                valid.policy()
            );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ExactSharingRepresentation.create(forged)
        );
        assertTrue(exception.getMessage().contains(
            "cannot be regenerated"));
    }

    @Test
    void constructorRejectsForgedHashesIdentityAndClaimBoundary() {
        ExactSharingRepresentation valid =
            ExactSharingRepresentation.create(materialCandidate());
        String forgedHash = "sha256:" + "0".repeat(64);

        assertThrows(IllegalArgumentException.class, () ->
            copy(valid, forgedHash, valid.sourceTreeHash(),
                valid.expandedTreeHash(), valid.claimBoundary()));
        assertThrows(IllegalArgumentException.class, () ->
            copy(valid, valid.identity(), forgedHash,
                valid.expandedTreeHash(), valid.claimBoundary()));
        assertThrows(IllegalArgumentException.class, () ->
            copy(valid, valid.identity(), valid.sourceTreeHash(),
                forgedHash, valid.claimBoundary()));
        assertThrows(IllegalArgumentException.class, () ->
            copy(valid, valid.identity(), valid.sourceTreeHash(),
                valid.expandedTreeHash(), "broader claim"));
    }

    @Test
    void constructorRejectsCandidateFieldAndPathSubstitution() {
        ExactSharingRepresentation valid =
            ExactSharingRepresentation.create(materialCandidate());
        String forgedHash = "sha256:" + "0".repeat(64);

        assertThrows(IllegalArgumentException.class, () ->
            new ExactSharingRepresentation(
                valid.schema(),
                valid.identity(),
                forgedHash,
                valid.sourceExpression(),
                valid.semanticValueKey(),
                valid.definitionExpression(),
                valid.referencePaths(),
                valid.policy(),
                valid.sharingCost(),
                valid.sourceTreeHash(),
                valid.expandedTreeHash(),
                valid.claimBoundary()
            ));
        assertThrows(IllegalArgumentException.class, () ->
            new ExactSharingRepresentation(
                valid.schema(),
                valid.identity(),
                valid.candidateIdentity(),
                valid.sourceExpression(),
                "forged-semantic-key",
                valid.definitionExpression(),
                valid.referencePaths(),
                valid.policy(),
                valid.sharingCost(),
                valid.sourceTreeHash(),
                valid.expandedTreeHash(),
                valid.claimBoundary()
            ));
        assertThrows(IllegalArgumentException.class, () ->
            new ExactSharingRepresentation(
                valid.schema(),
                valid.identity(),
                valid.candidateIdentity(),
                valid.sourceExpression(),
                valid.semanticValueKey(),
                valid.definitionExpression(),
                List.of(valid.referencePaths().getFirst()),
                valid.policy(),
                valid.sharingCost(),
                valid.sourceTreeHash(),
                valid.expandedTreeHash(),
                valid.claimBoundary()
            ));
    }

    @Test
    void constructorRejectsUnsupportedSchemaAndMalformedIdentity() {
        ExactSharingRepresentation valid =
            ExactSharingRepresentation.create(materialCandidate());

        assertThrows(IllegalArgumentException.class, () ->
            new ExactSharingRepresentation(
                "future-schema",
                valid.identity(),
                valid.candidateIdentity(),
                valid.sourceExpression(),
                valid.semanticValueKey(),
                valid.definitionExpression(),
                valid.referencePaths(),
                valid.policy(),
                valid.sharingCost(),
                valid.sourceTreeHash(),
                valid.expandedTreeHash(),
                valid.claimBoundary()
            ));
        assertThrows(IllegalArgumentException.class, () ->
            copy(valid, "not-a-hash", valid.sourceTreeHash(),
                valid.expandedTreeHash(), valid.claimBoundary()));
    }

    private static RepeatedStructureExtractionCandidate materialCandidate() {
        return new RepeatedStructureExtractor().extractMaterial(
            "(sin(x + 1) + cos(x + 1))"
                + " * (sin(x + 1) + cos(x + 1))"
        ).stream()
            .filter(candidate -> candidate.representativeExpression().equals(
                "sin(x + 1) + cos(x + 1)"))
            .findFirst()
            .orElseThrow();
    }

    private static ExactSharingRepresentation copy(
        ExactSharingRepresentation source,
        String identity,
        String sourceTreeHash,
        String expandedTreeHash,
        String claimBoundary
    ) {
        return new ExactSharingRepresentation(
            source.schema(),
            identity,
            source.candidateIdentity(),
            source.sourceExpression(),
            source.semanticValueKey(),
            source.definitionExpression(),
            source.referencePaths(),
            source.policy(),
            source.sharingCost(),
            sourceTreeHash,
            expandedTreeHash,
            claimBoundary
        );
    }
}
