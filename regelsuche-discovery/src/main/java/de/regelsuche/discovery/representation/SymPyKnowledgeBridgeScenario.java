package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_KNOWN_FORM_WITHOUT_NEW_CAPABILITY;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_KNOWN_STRUCTURE_EVIDENCE_BELOW_MINIMUM;
import static de.regelsuche.validation.OracleValidator.OracleValidationStatus.AGREE;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.knowledge.RuleStatus;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.OracleValidator.OracleValidation;
import de.regelsuche.validation.SymPyOracleValidator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Reproducible R2 scenario: core search forms a candidate without SymPy
 * knowledge; post-freeze classification and execution expose the concrete
 * capability added by the explicitly enabled knowledge pack.
 */
public final class SymPyKnowledgeBridgeScenario {
    public static final String SCHEMA =
        "regelsuche.sympy-knowledge-bridge-scenario/v1";
    public static final String PACK_ID = "sympy-trigonometry";
    public static final String STRUCTURE_ID =
        "sympy.trig.pythagorean-pair";
    public static final String CONSEQUENCE_ID =
        "rule:sympy.trig.pythagorean";
    public static final String FORMATION_RULE_ID = "ast_add_zero_right";
    public static final String SOURCE_EXPRESSION =
        "sin(x)^2 + (cos(x)^2 + 0)";
    public static final String CANDIDATE_EXPRESSION =
        "sin(x) ^ 2 + cos(x) ^ 2";
    public static final String FOLLOW_ON_EXPRESSION = "1";
    public static final String CLAIM_BOUNDARY =
        "Fixed one-step target-free candidate generation and post-freeze "
            + "knowledge qualification only; not broad autonomous discovery, "
            + "external novelty or comparative superiority.";

    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();

    private SymPyKnowledgeBridgeScenario() {
    }

    public static ScenarioArtifact run() {
        KnowledgePackRegistry registry = new KnowledgePackRegistry();
        KnowledgePackSelection disabled = KnowledgePackSelection.CORE;
        KnowledgePackSelection enabled =
            KnowledgePackSelection.CORE.enablePack(PACK_ID);
        var disabledBoundary = boundary(registry, disabled);
        var enabledBoundary = boundary(registry, enabled);
        requireEqual(
            disabledBoundary.candidateFormationRuleInventoryHash(),
            enabledBoundary.candidateFormationRuleInventoryHash(),
            "R2 formation inventories differ"
        );

        Transformation disabledCandidate = generate(
            disabledBoundary.candidateFormationRules());
        Transformation enabledCandidate = generate(
            enabledBoundary.candidateFormationRules());
        requireEqual(
            disabledCandidate.transformedExpression(),
            enabledCandidate.transformedExpression(),
            "candidate generation depends on post-freeze knowledge"
        );

        RewriteRule formationRule = enabledBoundary.candidateFormationRules()
            .stream()
            .filter(rule -> rule.id().equals(FORMATION_RULE_ID))
            .findFirst()
            .orElseThrow();
        OracleValidation oracle = new SymPyOracleValidator()
            .validateEquivalence(
                SOURCE_EXPRESSION,
                enabledCandidate.transformedExpression()
            );
        CandidateProofStatus proofStatus = proofStatus(
            formationRule, enabledCandidate, oracle);
        requireEqual(
            CandidateProofStatus.SYMBOLICALLY_VERIFIED,
            proofStatus,
            "candidate evidence is below the catalog threshold"
        );

        RepresentationCandidateProposal provisional = proposal(
            enabledCandidate.transformedExpression(),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES
        );
        RepresentationCandidateProposal verified = proposal(
            enabledCandidate.transformedExpression(), proofStatus);
        List<RepresentationCandidateProposal> candidates =
            List.of(provisional, verified);
        var disabledReceipt = disabledBoundary.freezeCandidates(candidates);
        var enabledReceipt = enabledBoundary.freezeCandidates(candidates);
        requireEqual(
            disabledReceipt.candidateSetHash(),
            enabledReceipt.candidateSetHash(),
            "candidate freeze differs across pack selections"
        );

        var disabledDisclosure =
            disabledBoundary.disclosePostFreeze(disabledReceipt);
        var enabledDisclosure =
            enabledBoundary.disclosePostFreeze(enabledReceipt);
        RepresentationCandidateAssessment disabledAssessment =
            new RepresentationCandidateAssessor(
                disabledDisclosure.classificationCatalog()
            ).assess(verified);
        RepresentationCandidateAssessor enabledAssessor =
            new RepresentationCandidateAssessor(
                enabledDisclosure.classificationCatalog());
        RepresentationCandidateAssessment provisionalAssessment =
            enabledAssessor.assess(provisional);
        RepresentationCandidateAssessment verifiedAssessment =
            enabledAssessor.assess(verified);
        KnownStructureMatch structure = verifiedAssessment
            .newlyExposedStructureMatches().stream()
            .filter(match -> match.structureId().equals(STRUCTURE_ID))
            .findFirst()
            .orElseThrow();

        AstRewriteTransformationEngine formationEngine =
            new AstRewriteTransformationEngine(
                enabledBoundary.candidateFormationRules());
        AstRewriteTransformationEngine disabledEngine =
            AstRewriteTransformationEngine.withKnowledgePacks(
                disabledDisclosure.classificationSelection());
        AstRewriteTransformationEngine enabledEngine =
            AstRewriteTransformationEngine.withKnowledgePacks(
                enabledDisclosure.classificationSelection());

        ScenarioContent content = new ScenarioContent(
            SCHEMA,
            "sympy-pythagorean-post-hoc-bridge",
            "R2",
            SOURCE_EXPRESSION,
            enabledCandidate.transformedExpression(),
            new FormationEvidence(
                enabledCandidate.rule(),
                enabledCandidate.packId(),
                enabledCandidate.primitiveRuleIds(),
                enabledCandidate.equivalencePreservingByConstruction(),
                enabledCandidate.assumptions(),
                enabledBoundary.candidateFormationRuleInventoryHash(),
                enabledReceipt.candidateSetHash(),
                disabledReceipt.contentHash(),
                enabledReceipt.contentHash()
            ),
            new ValidationEvidence(
                oracle.status().name(),
                oracle.evidence(),
                proofStatus.name()
            ),
            new InformationIdentities(
                disabledBoundary.contentHash(),
                enabledBoundary.contentHash(),
                disabledAssessment.knownStructureCatalogHash(),
                verifiedAssessment.knownStructureCatalogHash()
            ),
            new StructureEvidence(
                structure.structureId(),
                structure.metadata().sourceProject(),
                structure.metadata().sourceReference(),
                structure.metadata().license(),
                structure.recognitionMode(),
                structure.metadata().minimumEvidence().name(),
                CONSEQUENCE_ID
            ),
            ComparisonEvidence.from(
                disabledAssessment,
                provisionalAssessment,
                verifiedAssessment
            ),
            followOn(
                formationEngine,
                disabledEngine,
                enabledEngine,
                disabledDisclosure.classificationRuleInventoryHash(),
                enabledDisclosure.classificationRuleInventoryHash()
            ),
            CLAIM_BOUNDARY
        );
        ScenarioArtifact result = new ScenarioArtifact(
            content,
            KnownStructureCatalog.sha256(json(content))
        );
        validate(result);
        return result;
    }

    public static ScenarioArtifact write(Path output) throws IOException {
        Objects.requireNonNull(output, "output");
        ScenarioArtifact result = run();
        Path absolute = output.toAbsolutePath();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        Files.writeString(
            absolute,
            result.toCanonicalJson() + System.lineSeparator(),
            StandardCharsets.UTF_8
        );
        return result;
    }

    public static void main(String[] args) throws IOException {
        Path output = args.length == 0
            ? Path.of(
                "build/reports/representation-discovery/"
                    + "sympy-knowledge-bridge.json")
            : Path.of(args[0]);
        write(output);
    }

    private static RepresentationDiscoveryInformationBoundary boundary(
        KnowledgePackRegistry registry,
        KnowledgePackSelection selection
    ) {
        return RepresentationDiscoveryInformationBoundary.fromKnowledgePacks(
            registry,
            RepresentationDiscoveryInformationBoundary.Track
                .R2_CATALOG_BLIND_POST_HOC_BRIDGE,
            selection,
            Set.of()
        );
    }

    private static Transformation generate(List<RewriteRule> rules) {
        List<Transformation> generated = new AstRewriteTransformationEngine(
            rules
        ).transform(SOURCE_EXPRESSION).stream()
            .filter(result -> result.rule().equals(FORMATION_RULE_ID))
            .toList();
        requireEqual(1, generated.size(),
            "formation rule must produce exactly one candidate");
        Transformation candidate = generated.getFirst();
        requireEqual(CANDIDATE_EXPRESSION, candidate.transformedExpression(),
            "formation rule produced an unexpected candidate");
        return candidate;
    }

    private static RepresentationCandidateProposal proposal(
        String candidate,
        CandidateProofStatus status
    ) {
        return RepresentationCandidateProposal.whole(
            SOURCE_EXPRESSION, candidate, List.of(), status);
    }

    private static CandidateProofStatus proofStatus(
        RewriteRule rule,
        Transformation candidate,
        OracleValidation oracle
    ) {
        if (rule.descriptor().status() == RuleStatus.VALIDATED
                && candidate.equivalencePreservingByConstruction()
                && candidate.assumptions().isEmpty()
                && oracle.status() == AGREE) {
            return CandidateProofStatus.SYMBOLICALLY_VERIFIED;
        }
        return oracle.status() == AGREE
            ? CandidateProofStatus.VALIDATED_BY_EXAMPLES
            : CandidateProofStatus.OBSERVED;
    }

    private static FollowOnEvidence followOn(
        AstRewriteTransformationEngine formation,
        AstRewriteTransformationEngine disabled,
        AstRewriteTransformationEngine enabled,
        String disabledInventoryHash,
        String enabledInventoryHash
    ) {
        return new FollowOnEvidence(
            hasTargetRule(formation),
            hasTargetRule(disabled),
            hasTargetRule(enabled),
            targetSuccessors(formation),
            targetSuccessors(disabled),
            targetSuccessors(enabled),
            formation.transform(CANDIDATE_EXPRESSION).size(),
            disabled.transform(CANDIDATE_EXPRESSION).size(),
            enabled.transform(CANDIDATE_EXPRESSION).size(),
            disabledInventoryHash,
            enabledInventoryHash
        );
    }

    private static boolean hasTargetRule(
        AstRewriteTransformationEngine engine
    ) {
        return engine.rules().stream()
            .anyMatch(rule -> rule.id().equals(targetRuleId()));
    }

    private static List<String> targetSuccessors(
        AstRewriteTransformationEngine engine
    ) {
        return engine.transform(CANDIDATE_EXPRESSION).stream()
            .filter(result -> result.rule().equals(targetRuleId()))
            .map(Transformation::transformedExpression)
            .distinct()
            .sorted()
            .toList();
    }

    private static String targetRuleId() {
        return CONSEQUENCE_ID.substring("rule:".length());
    }

    private static void validate(ScenarioArtifact artifact) {
        ComparisonEvidence comparison = artifact.content().comparison();
        FollowOnEvidence followOn = artifact.content().followOnExecution();
        requireEqual(0, comparison.disabledMatches(),
            "disabled catalog recognized the target");
        requireEqual(1, comparison.provisionalMatches(),
            "enabled catalog did not recognize the target");
        requireTrue(comparison.provisionalUnlocks().isEmpty(),
            "below-threshold evidence unlocked a consequence");
        requireTrue(comparison.provisionalWarnings().contains(
            WARNING_KNOWN_STRUCTURE_EVIDENCE_BELOW_MINIMUM),
            "specific evidence warning is missing");
        requireTrue(!comparison.provisionalWarnings().contains(
            WARNING_KNOWN_FORM_WITHOUT_NEW_CAPABILITY),
            "generic warning obscures the evidence gate");
        requireEqual(List.of(CONSEQUENCE_ID), comparison.verifiedUnlocks(),
            "verified candidate did not unlock the consequence");
        requireTrue(!followOn.formationTargetRulePresent(),
            "formation leaked the target rule");
        requireTrue(!followOn.disabledTargetRulePresent(),
            "disabled inventory contains the target rule");
        requireTrue(followOn.enabledTargetRulePresent(),
            "enabled inventory is missing the target rule");
        requireEqual(List.of(), followOn.formationTargetSuccessors(),
            "formation produced a target-rule successor");
        requireEqual(List.of(), followOn.disabledTargetSuccessors(),
            "disabled pack produced a target-rule successor");
        requireEqual(List.of(FOLLOW_ON_EXPRESSION),
            followOn.enabledTargetSuccessors(),
            "enabled pack did not produce the expected state");
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "Unable to render canonical scenario evidence", exception);
        }
    }

    private static void requireTrue(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void requireEqual(
        Object expected,
        Object actual,
        String message
    ) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException(
                message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    public record FormationEvidence(
        String ruleId,
        String packId,
        List<String> primitiveRuleIds,
        boolean equivalencePreservingByConstruction,
        List<String> assumptions,
        String ruleInventoryHash,
        String candidateSetHash,
        String disabledFreezeReceiptHash,
        String enabledFreezeReceiptHash
    ) {
        public FormationEvidence {
            primitiveRuleIds = List.copyOf(primitiveRuleIds);
            assumptions = List.copyOf(assumptions);
        }
    }

    public record ValidationEvidence(
        String oracleStatus,
        String oracleEvidence,
        String candidateProofStatus
    ) {
    }

    public record InformationIdentities(
        String disabledBoundaryHash,
        String enabledBoundaryHash,
        String disabledCatalogHash,
        String enabledCatalogHash
    ) {
    }

    public record StructureEvidence(
        String structureId,
        String sourceProject,
        String sourceReference,
        String license,
        String recognitionMode,
        String minimumEvidence,
        String consequenceId
    ) {
    }

    public record ComparisonEvidence(
        int disabledMatches,
        int provisionalMatches,
        List<String> provisionalUnlocks,
        List<String> provisionalWarnings,
        int verifiedMatches,
        List<String> verifiedUnlocks,
        List<String> verifiedWarnings
    ) {
        static ComparisonEvidence from(
            RepresentationCandidateAssessment disabled,
            RepresentationCandidateAssessment provisional,
            RepresentationCandidateAssessment verified
        ) {
            return new ComparisonEvidence(
                targetMatches(disabled),
                targetMatches(provisional),
                targetUnlocks(provisional),
                provisional.warnings(),
                targetMatches(verified),
                targetUnlocks(verified),
                verified.warnings()
            );
        }

        public ComparisonEvidence {
            provisionalUnlocks = List.copyOf(provisionalUnlocks);
            provisionalWarnings = List.copyOf(provisionalWarnings);
            verifiedUnlocks = List.copyOf(verifiedUnlocks);
            verifiedWarnings = List.copyOf(verifiedWarnings);
        }

        private static int targetMatches(
            RepresentationCandidateAssessment assessment
        ) {
            return Math.toIntExact(
                assessment.newlyExposedStructureMatches().stream()
                    .filter(match -> match.structureId().equals(STRUCTURE_ID))
                    .count());
        }

        private static List<String> targetUnlocks(
            RepresentationCandidateAssessment assessment
        ) {
            return assessment.newlyUnlockedConsequences().stream()
                .filter(unlock -> unlock.structureId().equals(STRUCTURE_ID))
                .map(KnownStructureConsequenceUnlock::consequenceId)
                .distinct()
                .sorted()
                .toList();
        }
    }

    public record FollowOnEvidence(
        boolean formationTargetRulePresent,
        boolean disabledTargetRulePresent,
        boolean enabledTargetRulePresent,
        List<String> formationTargetSuccessors,
        List<String> disabledTargetSuccessors,
        List<String> enabledTargetSuccessors,
        int formationTotalSuccessors,
        int disabledTotalSuccessors,
        int enabledTotalSuccessors,
        String disabledRuleInventoryHash,
        String enabledRuleInventoryHash
    ) {
        public FollowOnEvidence {
            formationTargetSuccessors =
                List.copyOf(formationTargetSuccessors);
            disabledTargetSuccessors =
                List.copyOf(disabledTargetSuccessors);
            enabledTargetSuccessors =
                List.copyOf(enabledTargetSuccessors);
        }
    }

    public record ScenarioContent(
        String schema,
        String scenarioId,
        String informationTrack,
        String sourceExpression,
        String candidateExpression,
        FormationEvidence formation,
        ValidationEvidence validation,
        InformationIdentities informationIdentities,
        StructureEvidence targetStructure,
        ComparisonEvidence comparison,
        FollowOnEvidence followOnExecution,
        String claimBoundary
    ) {
    }

    public record ScenarioArtifact(
        ScenarioContent content,
        String contentHash
    ) {
        public ScenarioArtifact {
            content = Objects.requireNonNull(content, "content");
            String expected = KnownStructureCatalog.sha256(json(content));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "scenario contentHash mismatch");
            }
        }

        public String toCanonicalJson() {
            return json(this);
        }
    }
}
