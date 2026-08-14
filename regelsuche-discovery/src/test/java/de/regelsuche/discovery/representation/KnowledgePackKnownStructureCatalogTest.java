package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_KNOWN_STRUCTURE_EVIDENCE_BELOW_MINIMUM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgePackKnownStructureCatalogTest {
    private static final String PACK_ID = "sympy-trigonometry";
    private static final String SYMPY_COMMIT =
        "fe935ceb303891d1f8bea4c03b19fd9ec9464b02";

    @Test
    void explicitSelectionControlsCatalogVisibilityAndIdentity() {
        KnownStructureCatalog hidden =
            KnowledgePackKnownStructureCatalog.fromSelection(
                KnowledgePackSelection.CORE);
        KnownStructureCatalog visible =
            KnowledgePackKnownStructureCatalog.fromSelection(
                KnowledgePackSelection.CORE.enablePack(PACK_ID));

        assertTrue(hidden.structures().stream()
            .noneMatch(structure -> structure.id().startsWith("sympy.")));
        assertEquals(7, visible.structures().stream()
            .filter(structure -> structure.id().startsWith("sympy."))
            .count());
        assertNotEquals(hidden.contentHash(), visible.contentHash());
    }

    @Test
    void selectedCatalogRecognizesAcKnownFormWithPinnedProvenance() {
        KnownStructureCatalog catalog =
            KnowledgePackKnownStructureCatalog.fromSelection(
                KnowledgePackSelection.CORE.enablePack(PACK_ID));
        KnownStructureMatch match = new KnownStructureMatcher(catalog)
            .match("cos(x)^2 + sin(x)^2")
            .stream()
            .filter(candidate ->
                candidate.structureId().equals(
                    "sympy.trig.pythagorean-pair"))
            .findFirst()
            .orElseThrow();

        assertEquals(
            KnownStructureMatch.RECOGNITION_EQUIVALENCE_AWARE,
            match.recognitionMode()
        );
        assertEquals("SymPy", match.metadata().sourceProject());
        assertEquals("BSD-3-Clause", match.metadata().license());
        assertTrue(match.metadata().sourceReference().contains(SYMPY_COMMIT));
        assertEquals(
            List.of("sympy-trigonometry"),
            match.metadata().enabledRulePackIds()
        );
    }

    @Test
    void minimumEvidenceFailsClosedBeforeCapabilityUnlock() {
        KnownStructureCatalog catalog =
            KnowledgePackKnownStructureCatalog.fromSelection(
                KnowledgePackSelection.CORE.enablePack(PACK_ID));
        RepresentationCandidateAssessor assessor =
            new RepresentationCandidateAssessor(catalog);

        RepresentationCandidateAssessment provisional = assessor.assess(
            RepresentationCandidateProposal.whole(
                "1",
                "sin(x)^2 + cos(x)^2",
                List.of(),
                CandidateProofStatus.VALIDATED_BY_EXAMPLES
            )
        );
        RepresentationCandidateAssessment verified = assessor.assess(
            RepresentationCandidateProposal.whole(
                "1",
                "sin(x)^2 + cos(x)^2",
                List.of(),
                CandidateProofStatus.SYMBOLICALLY_VERIFIED
            )
        );

        assertTrue(provisional.newlyUnlockedConsequences().isEmpty());
        assertTrue(provisional.warnings().contains(
            WARNING_KNOWN_STRUCTURE_EVIDENCE_BELOW_MINIMUM));
        assertFalse(verified.newlyUnlockedConsequences().isEmpty());
        assertTrue(verified.newlyUnlockedConsequences().stream()
            .anyMatch(unlock -> unlock.consequenceId().equals(
                "rule:sympy.trig.pythagorean")));
    }
}
