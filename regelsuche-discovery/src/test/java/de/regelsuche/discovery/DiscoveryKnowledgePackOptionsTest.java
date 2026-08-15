package de.regelsuche.discovery;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_KNOWN_FORM_WITHOUT_NEW_CAPABILITY;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_KNOWN_STRUCTURE_EVIDENCE_BELOW_MINIMUM;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_UNSATISFIED_KNOWN_STRUCTURE_ASSUMPTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.representation.KnownStructure;
import de.regelsuche.discovery.representation.KnownStructureCatalog;
import de.regelsuche.discovery.representation.KnownStructureMatch;
import de.regelsuche.discovery.representation.KnownStructureMatcher;
import de.regelsuche.discovery.representation.RepresentationCandidateAssessment;
import de.regelsuche.discovery.representation.RepresentationCandidateAssessor;
import de.regelsuche.discovery.representation.RepresentationCandidateProposal;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.knowledge.RuleProfile;
import de.regelsuche.transform.PatternExpr;
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
        assertEquals(7L, visible.structures().stream()
            .filter(form -> form.id().startsWith("sympy."))
            .count());
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
