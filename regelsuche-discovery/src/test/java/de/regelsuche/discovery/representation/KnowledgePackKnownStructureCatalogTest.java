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
    private static final String PACK = "sympy-trigonometry";

    @Test
    void selectedPackControlsRecognitionProvenanceAndCatalogIdentity() {
        KnownStructureCatalog hidden = catalog(KnowledgePackSelection.CORE);
        KnownStructureCatalog visible = catalog(
            KnowledgePackSelection.CORE.enablePack(PACK));
        KnownStructureMatch match = new KnownStructureMatcher(visible)
            .match("cos(x)^2 + sin(x)^2").stream()
            .filter(value -> value.structureId().equals(
                "sympy.trig.pythagorean-pair"))
            .findFirst().orElseThrow();

        assertTrue(hidden.structures().stream()
            .noneMatch(form -> form.id().startsWith("sympy.")));
        assertEquals(7, visible.structures().size());
        assertNotEquals(hidden.contentHash(), visible.contentHash());
        assertEquals(KnownStructureMatch.RECOGNITION_EQUIVALENCE_AWARE,
            match.recognitionMode());
        assertEquals("SymPy", match.metadata().sourceProject());
        assertEquals("BSD-3-Clause", match.metadata().license());
    }

    @Test
    void minimumEvidenceFailsClosedBeforeCapabilityUnlock() {
        RepresentationCandidateAssessor assessor =
            new RepresentationCandidateAssessor(catalog(
                KnowledgePackSelection.CORE.enablePack(PACK)));
        RepresentationCandidateAssessment provisional =
            assessor.assess(proposal(CandidateProofStatus.VALIDATED_BY_EXAMPLES));
        RepresentationCandidateAssessment verified =
            assessor.assess(proposal(CandidateProofStatus.SYMBOLICALLY_VERIFIED));

        assertTrue(provisional.newlyUnlockedConsequences().isEmpty());
        assertTrue(provisional.warnings().contains(
            WARNING_KNOWN_STRUCTURE_EVIDENCE_BELOW_MINIMUM));
        assertFalse(verified.newlyUnlockedConsequences().isEmpty());
    }

    private static KnownStructureCatalog catalog(
        KnowledgePackSelection selection
    ) {
        return KnowledgePackKnownStructureCatalog.fromSelection(selection);
    }

    private static RepresentationCandidateProposal proposal(
        CandidateProofStatus status
    ) {
        return RepresentationCandidateProposal.whole(
            "1", "sin(x)^2 + cos(x)^2", List.of(), status);
    }
}
