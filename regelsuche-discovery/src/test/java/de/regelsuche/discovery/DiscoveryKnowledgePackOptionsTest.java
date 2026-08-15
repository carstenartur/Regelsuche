package de.regelsuche.discovery;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_KNOWN_FORM_WITHOUT_NEW_CAPABILITY;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_KNOWN_STRUCTURE_EVIDENCE_BELOW_MINIMUM;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_UNSATISFIED_KNOWN_STRUCTURE_ASSUMPTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.representation.KnownStructure;
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
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.knowledge.RuleProfile;
import de.regelsuche.transform.PatternExpr;
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
    private static final String CORE_IDENTITY_RULE = "ast_add_zero_right";

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
            .filter(value -> value.structureId().equals(PYTHAGOREAN_PAIR))
            .findFirst()
            .orElseThrow();

        assertTrue(hidden.structures().stream()
            .noneMatch(form -> form.id().startsWith("sympy.")));
        assertEquals(7L, sympyStructureCount(visible));
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
        assertFalse(provisional.warnings().contains(
            WARNING_KNOWN_FORM_WITHOUT_NEW_CAPABILITY));
        assertFalse(verified.newlyUnlockedConsequences().isEmpty());
    }

    @Test
    void unsatisfiedAssumptionsDoNotAddGenericKnownFormWarning() {
        KnownStructure guarded = new KnownStructure(
            "guarded-quotient",
            "rational",
            PatternExpr.var("expression"),
            List.of("x != 0"),
            List.of("rule:guarded-cancellation"),
            "test fixture"
        );
        RepresentationCandidateAssessment assessment =
            new RepresentationCandidateAssessor(new KnownStructureCatalog(
                "warning-fixture-v1",
                List.of(guarded)
            )).assess(RepresentationCandidateProposal.whole(
                "x",
                "x + 0",
                List.of(),
                CandidateProofStatus.SYMBOLICALLY_VERIFIED
            ));

        assertTrue(assessment.warnings().contains(
            WARNING_UNSATISFIED_KNOWN_STRUCTURE_ASSUMPTIONS));
        assertFalse(assessment.warnings().contains(
            WARNING_KNOWN_FORM_WITHOUT_NEW_CAPABILITY));
    }

    @Test
    void eligibleKnownFormWithoutConsequencesKeepsSpecificWarning() {
        KnownStructure informational = new KnownStructure(
            "known-zero-sum",
            "algebra",
            PatternExpr.op(
                ADD,
                PatternExpr.var("expression"),
                PatternExpr.num(0)
            ),
            List.of(),
            List.of(),
            "test fixture"
        );
        RepresentationCandidateAssessment assessment =
            new RepresentationCandidateAssessor(new KnownStructureCatalog(
                "warning-fixture-v1",
                List.of(informational)
            )).assess(RepresentationCandidateProposal.whole(
                "x",
                "x + 0",
                List.of(),
                CandidateProofStatus.SYMBOLICALLY_VERIFIED
            ));

        assertTrue(assessment.warnings().contains(
            WARNING_KNOWN_FORM_WITHOUT_NEW_CAPABILITY));
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
        assertTrue(Arrays.stream(
            RepresentationDiscoveryInformationBoundary.class.getMethods())
            .noneMatch(method -> method.getName().equals(
                "formationSelection")));

        RepresentationDiscoveryInformationBoundary compression =
            options.representationDiscoveryBoundary(
                Track.R1_TARGET_FREE_COMPRESSION);
        assertTrue(compression.candidateFormationCatalog()
            .structures().isEmpty());
        assertTrue(hasRule(compression, CORE_IDENTITY_RULE));
        assertTrue(hasPackRule(compression, SYMPY_TRIGONOMETRY));
        assertEquals(compression.candidateFormationRules().stream()
                .map(rule -> rule.id())
                .sorted()
                .toList(),
            compression.candidateFormationRules().stream()
                .map(rule -> rule.id())
                .toList());
        assertEquals(compression.candidateFormationRuleInventoryHash(),
            compression.postFreezeRuleInventoryCommitment());
        assertTrue(compression.disclosePostFreeze(
            compression.freezeCandidates(List.of(candidate)))
            .classificationCatalog().structures().isEmpty());

        RepresentationDiscoveryInformationBoundary blind =
            options.representationDiscoveryBoundary(
                Track.R2_CATALOG_BLIND_POST_HOC_BRIDGE);
        assertTrue(blind.candidateFormationCatalog().structures().isEmpty());
        assertTrue(hasRule(blind, CORE_IDENTITY_RULE));
        assertFalse(hasPackRule(blind, SYMPY_TRIGONOMETRY));
        assertNotEquals(blind.candidateFormationRuleInventoryHash(),
            blind.postFreezeRuleInventoryCommitment());
        CandidateFreezeReceipt blindFreeze =
            blind.freezeCandidates(List.of(candidate));
        PostFreezeDisclosure blindDisclosure =
            blind.disclosePostFreeze(blindFreeze);
        assertEquals(7L,
            sympyStructureCount(blindDisclosure.classificationCatalog()));
        assertEquals(blind.candidateFormationRuleInventoryHash(),
            blindDisclosure.formationRuleInventoryHash());
        assertEquals(blind.postFreezeRuleInventoryCommitment(),
            blindDisclosure.classificationRuleInventoryHash());
        assertEquals(Set.of(SYMPY_TRIGONOMETRY),
            blindDisclosure.withheldRulePackIds());

        RepresentationDiscoveryInformationBoundary visible =
            options.representationDiscoveryBoundary(
                Track.R3_CATALOG_VISIBLE_KNOWLEDGE_NAVIGATION);
        assertEquals(7L,
            sympyStructureCount(visible.candidateFormationCatalog()));
        assertTrue(hasRule(visible, CORE_IDENTITY_RULE));
        assertTrue(hasPackRule(visible, SYMPY_TRIGONOMETRY));
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

        assertTrue(hidden.candidateFormationCatalog().structures().stream()
            .noneMatch(structure -> structure.id().equals(
                PYTHAGOREAN_PAIR)));
        assertTrue(hasRule(hidden, CORE_IDENTITY_RULE));
        assertFalse(hasPackRule(hidden, SYMPY_TRIGONOMETRY));
        assertNotEquals(hidden.candidateFormationRuleInventoryHash(),
            hidden.postFreezeRuleInventoryCommitment());

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

    private static long sympyStructureCount(KnownStructureCatalog catalog) {
        return catalog.structures().stream()
            .filter(structure -> structure.id().startsWith("sympy."))
            .count();
    }

    private static boolean hasRule(
        RepresentationDiscoveryInformationBoundary boundary,
        String ruleId
    ) {
        return boundary.candidateFormationRules().stream()
            .anyMatch(rule -> rule.id().equals(ruleId));
    }

    private static boolean hasPackRule(
        RepresentationDiscoveryInformationBoundary boundary,
        String packId
    ) {
        return boundary.candidateFormationRules().stream()
            .anyMatch(rule -> rule.descriptor().packId().equals(packId));
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
