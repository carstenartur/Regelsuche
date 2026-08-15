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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * End-to-end R2 example in which bounded rewrite enumeration receives neither
 * a target representation nor the SymPy known-structure catalog.
 */
public final class TargetFreeSymPyBridgeDiscoveryScenario {
    public static final String SCHEMA =
        "regelsuche.target-free-sympy-bridge-discovery/v1";
    public static final String PACK_ID = "sympy-trigonometry";
    public static final String STRUCTURE_ID =
        "sympy.trig.pythagorean-pair";
    public static final String CONSEQUENCE_ID =
        "rule:sympy.trig.pythagorean";
    public static final String SOURCE_EXPRESSION =
        "sin(x)^2 + (cos(x)^2 + 0)";
    public static final String FOLLOW_ON_EXPRESSION = "1";
    public static final String CLAIM_BOUNDARY =
        "Bounded target-free rewrite enumeration with post-freeze R2 "
            + "classification and executable capability evidence; not a "
            + "held-out superiority, external-novelty or global-optimality "
            + "claim.";

    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();

    private TargetFreeSymPyBridgeDiscoveryScenario() {
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

        TargetFreeRepresentationSearch searcher =
            new TargetFreeRepresentationSearch();
        var budget = TargetFreeRepresentationSearch.Budget.small();
        var disabledSearch = searcher.search(
            SOURCE_EXPRESSION,
            disabledBoundary.candidateFormationRules(),
            budget
        );
        var enabledSearch = searcher.search(
            SOURCE_EXPRESSION,
            enabledBoundary.candidateFormationRules(),
            budget
        );
        requireEqual(
            disabledSearch.contentHash(),
            enabledSearch.contentHash(),
            "target-free search depends on hidden post-freeze knowledge"
        );
        requireTrue(
            enabledSearch.content().candidateStates().stream()
                .noneMatch(state ->
                    state.expression().equals(FOLLOW_ON_EXPRESSION)),
            "formation reached the withheld SymPy consequence"
        );

        Map<String, RewriteRule> formationRules = ruleIndex(
            enabledBoundary.candidateFormationRules());
        SymPyOracleValidator oracle = new SymPyOracleValidator();
        List<Candidate> candidates = enabledSearch.content()
            .candidateStates().stream()
            .map(state -> candidate(
                state,
                proofStatus(state, formationRules, oracle)
            ))
            .toList();
        requireTrue(!candidates.isEmpty(),
            "target-free search produced no candidate representations");

        List<RepresentationCandidateProposal> proposals = candidates.stream()
            .map(Candidate::proposal)
            .toList();
        var disabledReceipt =
            disabledBoundary.freezeCandidates(proposals);
        var enabledReceipt =
            enabledBoundary.freezeCandidates(proposals);
        requireEqual(
            disabledReceipt.candidateSetHash(),
            enabledReceipt.candidateSetHash(),
            "candidate freeze differs across pack selections"
        );

        var disabledDisclosure =
            disabledBoundary.disclosePostFreeze(disabledReceipt);
        var enabledDisclosure =
            enabledBoundary.disclosePostFreeze(enabledReceipt);
        RepresentationCandidateAssessor disabledAssessor =
            new RepresentationCandidateAssessor(
                disabledDisclosure.classificationCatalog());
        RepresentationCandidateAssessor enabledAssessor =
            new RepresentationCandidateAssessor(
                enabledDisclosure.classificationCatalog());

        List<AssessedCandidate> assessed = candidates.stream()
            .map(candidate -> new AssessedCandidate(
                candidate,
                disabledAssessor.assess(candidate.proposal()),
                enabledAssessor.assess(candidate.proposal())
            ))
            .toList();
        AssessedCandidate discovered = assessed.stream()
            .filter(value -> hasUnlock(
                value.enabledAssessment(),
                STRUCTURE_ID,
                CONSEQUENCE_ID
            ))
            .sorted(Comparator
                .comparingInt((AssessedCandidate value) ->
                    value.candidate().state().depth())
                .thenComparingInt(value ->
                    value.candidate().state().metrics().tokenCount())
                .thenComparing(value ->
                    value.candidate().state().expression()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "post-freeze catalog found no executable bridge"));

        var provisional = RepresentationCandidateProposal.whole(
            SOURCE_EXPRESSION,
            discovered.candidate().state().expression(),
            discovered.candidate().state().assumptions(),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES
        );
        RepresentationCandidateAssessment provisionalAssessment =
            enabledAssessor.assess(provisional);
        KnownStructureMatch structure = discovered.enabledAssessment()
            .newlyExposedStructureMatches().stream()
            .filter(match -> match.structureId().equals(STRUCTURE_ID))
            .findFirst()
            .orElseThrow();

        FollowOnEvidence followOn = followOn(
            enabledBoundary.candidateFormationRules(),
            disabledDisclosure.classificationSelection(),
            enabledDisclosure.classificationSelection(),
            discovered.candidate().state().expression(),
            disabledDisclosure.classificationRuleInventoryHash(),
            enabledDisclosure.classificationRuleInventoryHash()
        );
        ScenarioContent content = new ScenarioContent(
            SCHEMA,
            "target-free-sympy-pythagorean-bridge",
            "R2",
            enabledSearch.content(),
            enabledSearch.contentHash(),
            new FreezeEvidence(
                disabledBoundary.contentHash(),
                enabledBoundary.contentHash(),
                disabledReceipt.candidateSetHash(),
                disabledReceipt.contentHash(),
                enabledReceipt.contentHash(),
                disabledDisclosure.classificationCatalog().contentHash(),
                enabledDisclosure.classificationCatalog().contentHash()
            ),
            new DiscoveredBridge(
                discovered.candidate().state().stateHash(),
                discovered.candidate().state().expression(),
                discovered.candidate().state().depth(),
                discovered.candidate().state().pathRuleIds(),
                discovered.candidate().state().primitiveRuleIds(),
                discovered.candidate().state().assumptions(),
                discovered.candidate().state().packIds(),
                discovered.candidate().proposal()
                    .validationStatus().name(),
                structure.structureId(),
                structure.recognitionMode(),
                structure.metadata().sourceProject(),
                structure.metadata().sourceReference(),
                structure.metadata().license(),
                structure.metadata().minimumEvidence().name(),
                CONSEQUENCE_ID
            ),
            new ClassificationEvidence(
                targetMatches(discovered.disabledAssessment()),
                targetMatches(provisionalAssessment),
                targetUnlocks(provisionalAssessment),
                provisionalAssessment.warnings(),
                targetMatches(discovered.enabledAssessment()),
                targetUnlocks(discovered.enabledAssessment()),
                discovered.enabledAssessment().warnings()
            ),
            followOn,
            CLAIM_BOUNDARY
        );
        ScenarioArtifact artifact = ScenarioArtifact.create(content);
        validate(artifact);
        return artifact;
    }

    public static ScenarioArtifact write(Path output) throws IOException {
        Objects.requireNonNull(output, "output");
        ScenarioArtifact artifact = run();
        Path absolute = output.toAbsolutePath();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        Files.writeString(
            absolute,
            artifact.toCanonicalJson() + "\n",
            StandardCharsets.UTF_8
        );
        return artifact;
    }

    public static void main(String[] args) throws IOException {
        Path output = args.length == 0
            ? Path.of(
                "build/reports/representation-discovery/"
                    + "target-free-sympy-bridge.json")
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

    private static Candidate candidate(
        TargetFreeRepresentationSearch.State state,
        CandidateProofStatus status
    ) {
        return new Candidate(
            state,
            RepresentationCandidateProposal.whole(
                SOURCE_EXPRESSION,
                state.expression(),
                state.assumptions(),
                status
            )
        );
    }

    private static CandidateProofStatus proofStatus(
        TargetFreeRepresentationSearch.State state,
        Map<String, RewriteRule> rules,
        SymPyOracleValidator oracle
    ) {
        OracleValidation validation = oracle.validateEquivalence(
            SOURCE_EXPRESSION, state.expression());
        boolean validatedPath = state.pathRuleIds().stream()
            .map(rules::get)
            .allMatch(rule -> rule != null
                && rule.descriptor().status() == RuleStatus.VALIDATED);
        if (state.equivalencePreserving()
                && state.assumptions().isEmpty()
                && validatedPath
                && validation.status() == AGREE) {
            return CandidateProofStatus.SYMBOLICALLY_VERIFIED;
        }
        return validation.status() == AGREE
            ? CandidateProofStatus.VALIDATED_BY_EXAMPLES
            : CandidateProofStatus.OBSERVED;
    }

    private static Map<String, RewriteRule> ruleIndex(
        List<RewriteRule> rules
    ) {
        Map<String, RewriteRule> result = new LinkedHashMap<>();
        for (RewriteRule rule : rules) {
            if (result.put(rule.id(), rule) != null) {
                throw new IllegalArgumentException(
                    "duplicate formation rule ID: " + rule.id());
            }
        }
        return Map.copyOf(result);
    }

    private static FollowOnEvidence followOn(
        List<RewriteRule> formationRules,
        KnowledgePackSelection disabled,
        KnowledgePackSelection enabled,
        String expression,
        String disabledInventoryHash,
        String enabledInventoryHash
    ) {
        AstRewriteTransformationEngine formationEngine =
            new AstRewriteTransformationEngine(formationRules);
        AstRewriteTransformationEngine disabledEngine =
            AstRewriteTransformationEngine.withKnowledgePacks(disabled);
        AstRewriteTransformationEngine enabledEngine =
            AstRewriteTransformationEngine.withKnowledgePacks(enabled);
        List<Transformation> formation =
            formationEngine.transform(expression);
        List<Transformation> disabledResults =
            disabledEngine.transform(expression);
        List<Transformation> enabledResults =
            enabledEngine.transform(expression);
        return new FollowOnEvidence(
            hasTargetRule(formationEngine),
            hasTargetRule(disabledEngine),
            hasTargetRule(enabledEngine),
            targetSuccessors(formation),
            targetSuccessors(disabledResults),
            targetSuccessors(enabledResults),
            formation.size(),
            disabledResults.size(),
            enabledResults.size(),
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
        List<Transformation> transformations
    ) {
        return transformations.stream()
            .filter(result -> result.rule().equals(targetRuleId()))
            .map(Transformation::transformedExpression)
            .distinct()
            .sorted()
            .toList();
    }

    private static String targetRuleId() {
        return CONSEQUENCE_ID.substring("rule:".length());
    }

    private static boolean hasUnlock(
        RepresentationCandidateAssessment assessment,
        String structureId,
        String consequenceId
    ) {
        return assessment.newlyUnlockedConsequences().stream()
            .anyMatch(unlock ->
                unlock.structureId().equals(structureId)
                    && unlock.consequenceId().equals(consequenceId));
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

    private static void validate(ScenarioArtifact artifact) {
        ScenarioContent content = artifact.content();
        ClassificationEvidence classification =
            content.classification();
        FollowOnEvidence followOn = content.followOnExecution();
        requireTrue(content.search().candidateStates().size() > 1,
            "target-free search retained too few candidates");
        requireTrue(!content.discoveredBridge().pathRuleIds()
                .contains(targetRuleId()),
            "formation path contains the withheld target rule");
        requireTrue(!content.discoveredBridge().packIds()
                .contains(PACK_ID),
            "formation path contains the withheld knowledge pack");
        requireEqual(0, classification.disabledMatches(),
            "disabled catalog recognized the target");
        requireEqual(1, classification.provisionalMatches(),
            "enabled catalog did not recognize the target");
        requireTrue(classification.provisionalUnlocks().isEmpty(),
            "below-threshold evidence unlocked a consequence");
        requireTrue(classification.provisionalWarnings().contains(
            WARNING_KNOWN_STRUCTURE_EVIDENCE_BELOW_MINIMUM),
            "specific evidence warning is missing");
        requireTrue(!classification.provisionalWarnings().contains(
            WARNING_KNOWN_FORM_WITHOUT_NEW_CAPABILITY),
            "generic warning obscures the evidence gate");
        requireEqual(List.of(CONSEQUENCE_ID),
            classification.verifiedUnlocks(),
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
                "Unable to render target-free scenario evidence", exception);
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

    private record Candidate(
        TargetFreeRepresentationSearch.State state,
        RepresentationCandidateProposal proposal
    ) {
    }

    private record AssessedCandidate(
        Candidate candidate,
        RepresentationCandidateAssessment disabledAssessment,
        RepresentationCandidateAssessment enabledAssessment
    ) {
    }

    public record FreezeEvidence(
        String disabledBoundaryHash,
        String enabledBoundaryHash,
        String candidateSetHash,
        String disabledFreezeReceiptHash,
        String enabledFreezeReceiptHash,
        String disabledCatalogHash,
        String enabledCatalogHash
    ) {
    }

    public record DiscoveredBridge(
        String stateHash,
        String expression,
        int depth,
        List<String> pathRuleIds,
        List<String> primitiveRuleIds,
        List<String> assumptions,
        List<String> packIds,
        String candidateProofStatus,
        String structureId,
        String recognitionMode,
        String sourceProject,
        String sourceReference,
        String license,
        String minimumEvidence,
        String consequenceId
    ) {
        public DiscoveredBridge {
            pathRuleIds = List.copyOf(pathRuleIds);
            primitiveRuleIds = List.copyOf(primitiveRuleIds);
            assumptions = List.copyOf(assumptions);
            packIds = List.copyOf(packIds);
        }
    }

    public record ClassificationEvidence(
        int disabledMatches,
        int provisionalMatches,
        List<String> provisionalUnlocks,
        List<String> provisionalWarnings,
        int verifiedMatches,
        List<String> verifiedUnlocks,
        List<String> verifiedWarnings
    ) {
        public ClassificationEvidence {
            provisionalUnlocks = List.copyOf(provisionalUnlocks);
            provisionalWarnings = List.copyOf(provisionalWarnings);
            verifiedUnlocks = List.copyOf(verifiedUnlocks);
            verifiedWarnings = List.copyOf(verifiedWarnings);
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
        TargetFreeRepresentationSearch.SearchContent search,
        String searchContentHash,
        FreezeEvidence freeze,
        DiscoveredBridge discoveredBridge,
        ClassificationEvidence classification,
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
            contentHash = RepresentationCandidateAssessment.requireText(
                contentHash, "contentHash");
            String expected =
                KnownStructureCatalog.sha256(json(content));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "scenario hash does not match canonical content");
            }
        }

        static ScenarioArtifact create(ScenarioContent content) {
            return new ScenarioArtifact(
                content,
                KnownStructureCatalog.sha256(json(content))
            );
        }

        public String toCanonicalJson() {
            return json(this);
        }
    }
}
