package de.regelsuche.discovery;

import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_KNOWN_STRUCTURE_EVIDENCE_BELOW_MINIMUM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.representation.KnownStructureCatalog;
import de.regelsuche.discovery.representation.KnownStructureMatch;
import de.regelsuche.discovery.representation.KnownStructureMatcher;
import de.regelsuche.discovery.representation.RepresentationCandidateAssessment;
import de.regelsuche.discovery.representation.RepresentationCandidateAssessor;
import de.regelsuche.discovery.representation.RepresentationCandidateProposal;
import de.regelsuche.discovery.representation.RepresentationDiscoveryInformationBoundary;
import de.regelsuche.discovery.representation.RepresentationDiscoveryInformationBoundary.CandidateFreezeReceipt;
import de.regelsuche.discovery.representation.RepresentationDiscoveryInformationBoundary.PostFreezeDisclosure;
import de.regelsuche.discovery.representation.RepresentationDiscoveryInformationBoundary.Track;
import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.knowledge.RuleProfile;
import de.regelsuche.validation.CandidateProofStatus;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiscoveryKnowledgePackOptionsTest {
    private static final String SYMPY_TRIGONOMETRY = "sympy-trigonometry";
    private static final String PYTHAGOREAN_PAIR =
        "sympy.trig.pythagorean-pair";

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
                PYTHAGOREAN_PAIR))
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

    @Test
    void informationTracksSeparateFormationFromPostFreezeKnowledge() {
        DiscoveryOptions options = sympyOptions();
        RepresentationCandidateProposal candidate =
            proposal(CandidateProofStatus.SYMBOLICALLY_VERIFIED);
        assertTrue(Arrays.stream(
            CandidateFreezeReceipt.class.getDeclaredConstructors())
            .allMatch(constructor ->
                Modifier.isPrivate(constructor.getModifiers())));

        RepresentationDiscoveryInformationBoundary compression =
            options.representationDiscoveryBoundary(
                Track.R1_TARGET_FREE_COMPRESSION);
        assertTrue(compression.candidateFormationCatalog()
            .structures().isEmpty());
        assertEquals(compression.candidateFormationRuleInventoryHash(),
            compression.postFreezeRuleInventoryCommitment());
        assertTrue(compression.disclosePostFreeze(
            compression.freezeCandidates(List.of(candidate)))
            .classificationCatalog().structures().isEmpty());

        RepresentationDiscoveryInformationBoundary blind =
            options.representationDiscoveryBoundary(
                Track.R2_CATALOG_BLIND_POST_HOC_BRIDGE);
        assertTrue(blind.candidateFormationCatalog().structures().isEmpty());
        assertEquals(Set.of(SYMPY_TRIGONOMETRY),
            blind.formationSelection().disabledPacks());
        assertNotEquals(blind.candidateFormationRuleInventoryHash(),
            blind.postFreezeRuleInventoryCommitment());
        assertTrue(new KnowledgePackRegistry()
            .enabledRules(blind.formationSelection()).stream()
            .noneMatch(rule -> rule.descriptor().packId().equals(
                SYMPY_TRIGONOMETRY)));
        CandidateFreezeReceipt blindFreeze =
            blind.freezeCandidates(List.of(candidate));
        PostFreezeDisclosure blindDisclosure =
            blind.disclosePostFreeze(blindFreeze);
        assertEquals(7,
            blindDisclosure.classificationCatalog().structures().size());
        assertEquals(blind.candidateFormationRuleInventoryHash(),
            blindDisclosure.formationRuleInventoryHash());
        assertEquals(blind.postFreezeRuleInventoryCommitment(),
            blindDisclosure.classificationRuleInventoryHash());
        assertEquals(Set.of(SYMPY_TRIGONOMETRY),
            blindDisclosure.withheldRulePackIds());

        RepresentationDiscoveryInformationBoundary visible =
            options.representationDiscoveryBoundary(
                Track.R3_CATALOG_VISIBLE_KNOWLEDGE_NAVIGATION);
        assertEquals(7,
            visible.candidateFormationCatalog().structures().size());
        assertEquals(visible.candidateFormationRuleInventoryHash(),
            visible.postFreezeRuleInventoryCommitment());
        PostFreezeDisclosure visibleDisclosure =
            visible.disclosePostFreeze(
                visible.freezeCandidates(List.of(candidate)));
        assertEquals(visible.candidateFormationCatalog().contentHash(),
            visibleDisclosure.classificationCatalog().contentHash());

        assertNotEquals(blind.contentHash(), visible.contentHash());
        assertThrows(IllegalArgumentException.class,
            () -> visible.disclosePostFreeze(blindFreeze));
    }

    @Test
    void hiddenStructureTrackWithholdsItsDirectRulePack() {
        DiscoveryOptions options = sympyOptions();
        RepresentationDiscoveryInformationBoundary hidden =
            options.representationDiscoveryBoundary(
                Track.R4_HIDDEN_STRUCTURE_REDISCOVERY,
                Set.of(PYTHAGOREAN_PAIR)
            );

        assertTrue(hidden.formationSelection().disabledPacks()
            .contains(SYMPY_TRIGONOMETRY));
        assertTrue(hidden.candidateFormationCatalog().structures().stream()
            .noneMatch(structure -> structure.id().equals(
                PYTHAGOREAN_PAIR)));
        assertNotEquals(hidden.candidateFormationRuleInventoryHash(),
            hidden.postFreezeRuleInventoryCommitment());
        assertTrue(new KnowledgePackRegistry()
            .enabledRules(hidden.formationSelection())
            .stream()
            .noneMatch(rule -> rule.descriptor().packId().equals(
                SYMPY_TRIGONOMETRY)));

        PostFreezeDisclosure disclosure = hidden.disclosePostFreeze(
            hidden.freezeCandidates(List.of(
                proposal(CandidateProofStatus.SYMBOLICALLY_VERIFIED))));
        assertEquals(Set.of(PYTHAGOREAN_PAIR),
            disclosure.requestedHoldoutStructureIds());
        assertTrue(disclosure.formationExcludedStructureIds()
            .contains(PYTHAGOREAN_PAIR));
        assertEquals(Set.of(SYMPY_TRIGONOMETRY),
            disclosure.withheldRulePackIds());
        assertTrue(disclosure.classificationCatalog().structures().stream()
            .anyMatch(structure -> structure.id().equals(
                PYTHAGOREAN_PAIR)));
        assertEquals(hidden.holdoutCommitmentHash(),
            disclosure.holdoutCommitmentHash());
        assertEquals(hidden.candidateFormationRuleInventoryHash(),
            disclosure.formationRuleInventoryHash());
        assertEquals(hidden.postFreezeRuleInventoryCommitment(),
            disclosure.classificationRuleInventoryHash());
    }

    @Test
    void invalidHoldoutConfigurationsFailClosed() {
        DiscoveryOptions options = sympyOptions();

        assertThrows(IllegalArgumentException.class,
            () -> options.representationDiscoveryBoundary(
                Track.R4_HIDDEN_STRUCTURE_REDISCOVERY));
        assertThrows(IllegalArgumentException.class,
            () -> options.representationDiscoveryBoundary(
                Track.R2_CATALOG_BLIND_POST_HOC_BRIDGE,
                Set.of(PYTHAGOREAN_PAIR)));
        assertThrows(IllegalArgumentException.class,
            () -> options.representationDiscoveryBoundary(
                Track.R4_HIDDEN_STRUCTURE_REDISCOVERY,
                Set.of("unknown.structure")));
    }

    private static DiscoveryOptions sympyOptions() {
        return DiscoveryOptions
            .forProfile(DiscoveryProfile.PURE_REWRITE)
            .enablePack(SYMPY_TRIGONOMETRY);
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
