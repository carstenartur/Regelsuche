package de.regelsuche.discovery;

import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_KNOWN_STRUCTURE_EVIDENCE_BELOW_MINIMUM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.representation.KnownStructureCatalog;
import de.regelsuche.discovery.representation.KnownStructureMatch;
import de.regelsuche.discovery.representation.KnownStructureMatcher;
import de.regelsuche.discovery.representation.RepresentationCandidateAssessment;
import de.regelsuche.discovery.representation.RepresentationCandidateAssessor;
import de.regelsuche.discovery.representation.RepresentationCandidateProposal;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.knowledge.RuleProfile;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiscoveryKnowledgePackOptionsTest {
    private static final String SYMPY_TRIGONOMETRY = "sympy-trigonometry";

    @Test
    void supportsCustomEnabledAndDisabledPacks() {
        DiscoveryOptions options = DiscoveryOptions
                .forProfile(DiscoveryProfile.PURE_REWRITE)
                .withRuleProfile(RuleProfile.CORE_PLUS_SYMPY_POLYNOMIAL)
                .disablePack("sympy-polynomial-basic")
                .enablePack("user-local-rules");

        assertEquals(Set.of("user-local-rules"), options.enabledPacks());
        assertEquals(Set.of("sympy-polynomial-basic"),
            options.disabledPacks());
        assertTrue(options.knowledgePackSelection()
            .effectiveEnabledPacks(Set.of(
                "sympy-polynomial-basic",
                "user-local-rules"
            ))
            .contains("user-local-rules"));
    }

    @Test
    void selectedSympyPackControlsCatalogVisibilityAndIdentity() {
        KnownStructureCatalog hidden = KnownStructureCatalog
            .fromKnowledgePacks(KnowledgePackSelection.CORE);
        KnownStructureCatalog visible = KnownStructureCatalog
            .fromKnowledgePacks(KnowledgePackSelection.CORE
                .enablePack(SYMPY_TRIGONOMETRY));
        KnownStructureMatch match = new KnownStructureMatcher(visible)
            .match("cos(x)^2 + sin(x)^2")
            .stream()
            .filter(value -> value.structureId().equals(
                "sympy.trig.pythagorean-pair"))
            .findFirst()
            .orElseThrow();

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
        KnownStructureCatalog catalog = KnownStructureCatalog
            .fromKnowledgePacks(KnowledgePackSelection.CORE
                .enablePack(SYMPY_TRIGONOMETRY));
        RepresentationCandidateAssessor assessor =
            new RepresentationCandidateAssessor(catalog);

        RepresentationCandidateAssessment provisional = assessor.assess(
            proposal(CandidateProofStatus.VALIDATED_BY_EXAMPLES));
        RepresentationCandidateAssessment verified = assessor.assess(
            proposal(CandidateProofStatus.SYMBOLICALLY_VERIFIED));

        assertTrue(provisional.newlyUnlockedConsequences().isEmpty());
        assertTrue(provisional.warnings().contains(
            WARNING_KNOWN_STRUCTURE_EVIDENCE_BELOW_MINIMUM));
        assertFalse(verified.newlyUnlockedConsequences().isEmpty());
    }

    private static RepresentationCandidateProposal proposal(
        CandidateProofStatus status
    ) {
        return RepresentationCandidateProposal.whole(
            "1",
            "sin(x)^2 + cos(x)^2",
            List.of(),
            status
        );
    }
}
