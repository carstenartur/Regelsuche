package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.Transformation;
import de.regelsuche.util.AtomicJsonFile;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.OracleValidator;
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
import java.util.TreeSet;

/**
 * Opens the sealed qualification only after the immutable candidate freeze has
 * been supplied and its complete content binding has been verified.
 */
final class TargetFreeRepresentationPostFreezeQualification {
    static final String SCHEMA =
        "regelsuche.target-free-representation-post-freeze-qualification/v1";
    static final String FILE_NAME =
        "representation-discovery-post-freeze-qualification.json";
    static final String EVIDENCE_STATUS =
        "FROZEN_CANDIDATES_QUALIFIED_POST_FREEZE";
    static final String DISCLOSURE = "DISCLOSED_AFTER_CANDIDATE_FREEZE";
    static final String CLAIM_BOUNDARY =
        "Classifies the exact immutable candidate batches after opening the "
            + "sealed qualification resource. Formation, candidates and work "
            + "ledgers remain unchanged. This is not external novelty, global "
            + "optimality, CPU-time equality or general superiority evidence.";

    private static final String QUALIFICATION_SCHEMA =
        "regelsuche.target-free-representation-qualification/v1";
    private static final String SEALED = "SEALED_POST_FREEZE";
    private static final String QUALIFIED = "QUALIFIED";
    private static final String UNQUALIFIED =
        "NO_QUALIFYING_CANDIDATE";
    private static final String RULE_PREFIX = "rule:";
    private static final ExpressionParser QUALIFICATION_PARSER =
        new ExpressionParser();
    private static final JsonMapper QUALIFICATION_JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .build();

    private TargetFreeRepresentationPostFreezeQualification() {
    }

    static QualificationArtifact qualify(
        Path freezeFile,
        String repositoryRevision
    ) throws IOException {
        String source = Files.readString(
            Objects.requireNonNull(freezeFile, "freezeFile")
                .toAbsolutePath().normalize(),
            StandardCharsets.UTF_8
        );
        return qualify(
            TargetFreeRepresentationCandidateFreeze.FreezeArtifact
                .fromCanonicalJson(source),
            repositoryRevision
        );
    }

    static QualificationArtifact qualify(
        TargetFreeRepresentationCandidateFreeze.FreezeArtifact freeze,
        String repositoryRevision
    ) {
        Objects.requireNonNull(freeze, "freeze");
        var plan = TargetFreeRepresentationEvaluationPlan.create(
            repositoryRevision);
        requireFrozenAuthority(freeze, plan);

        byte[] sealedBytes =
            TargetFreeRepresentationEvaluationPlan.readResource(
                plan.content().qualificationResource());
        String sealedHash =
            TargetFreeRepresentationEvaluationPlan.sha256(sealedBytes);
        if (sealedBytes.length != plan.content().qualificationByteLength()
                || sealedBytes.length
                    != freeze.content().qualificationByteLength()
                || !sealedHash.equals(plan.content().qualificationHash())
                || !sealedHash.equals(
                    freeze.content().qualificationHash())) {
            throw new IllegalArgumentException(
                "sealed qualification differs from its frozen commitment");
        }
        Qualification qualification = decode(
            sealedBytes, Qualification.class);
        requireQualification(qualification, plan);

        Map<String, TargetFreeRepresentationEvaluationPlan.CaseDefinition>
            cases = new LinkedHashMap<>();
        plan.content().cases().forEach(value ->
            cases.put(value.id(), value));
        Map<String, QualificationRule> rules = new LinkedHashMap<>();
        qualification.cases().forEach(value ->
            rules.put(value.id(), value));

        SymPyOracleValidator oracle = new SymPyOracleValidator();
        List<QualificationEntry> entries =
            freeze.content().entries().stream()
                .map(entry -> qualifyEntry(
                    entry,
                    Objects.requireNonNull(cases.get(entry.caseId())),
                    Objects.requireNonNull(rules.get(entry.caseId())),
                    oracle
                ))
                .toList();
        QualificationContent content = new QualificationContent(
            SCHEMA,
            EVIDENCE_STATUS,
            plan.content().repositoryRevision(),
            plan.contentHash(),
            freeze.contentHash(),
            plan.content().qualificationResource(),
            sealedHash,
            sealedBytes.length,
            DISCLOSURE,
            entries,
            Summary.from(entries),
            qualification.claimBoundary(),
            CLAIM_BOUNDARY
        );
        return QualificationArtifact.create(content);
    }

    static QualificationArtifact write(
        Path outputDirectory,
        Path freezeFile,
        String repositoryRevision
    ) throws IOException {
        Path directory = Objects.requireNonNull(
            outputDirectory, "outputDirectory")
            .toAbsolutePath().normalize();
        Files.createDirectories(directory);
        QualificationArtifact artifact = qualify(
            freezeFile, repositoryRevision);
        Path target = directory.resolve(FILE_NAME);
        String canonical = artifact.toCanonicalJson();
        AtomicJsonFile.writeUtf8(target, canonical);
        if (!canonical.equals(Files.readString(
                target, StandardCharsets.UTF_8))
                || !artifact.equals(
                    QualificationArtifact.fromCanonicalJson(canonical))) {
            throw new IllegalStateException(
                "post-freeze qualification changed while writing");
        }
        return artifact;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                "usage: <repository-commit> <candidate-freeze-file> "
                    + "<output-directory>");
        }
        QualificationArtifact artifact = write(
            Path.of(args[2]), Path.of(args[1]), args[0]);
        System.out.println(
            "targetFreeRepresentationPostFreezeQualificationHash="
                + artifact.contentHash());
        System.out.println(
            "targetFreeRepresentationQualifiedConfigurations="
                + artifact.content().summary().qualifiedEntries());
        System.out.println(
            "targetFreeRepresentationQualifiedCandidates="
                + artifact.content().summary().qualifiedCandidates());
    }

    private static QualificationEntry qualifyEntry(
        TargetFreeRepresentationCandidateFreeze.ExecutionEntry frozen,
        TargetFreeRepresentationEvaluationPlan.CaseDefinition benchmarkCase,
        QualificationRule rule,
        SymPyOracleValidator oracle
    ) {
        RepresentationDiscoveryInformationBoundary boundary =
            RepresentationDiscoveryInformationBoundary.fromKnowledgePacks(
                benchmarkCase.informationTrack(),
                visibleSelection(benchmarkCase)
            );
        if (!boundary.contentHash().equals(
                frozen.informationBoundaryHash())
                || !boundary.candidateFormationSelectionCommitment()
                    .equals(frozen.formationSelectionCommitment())
                || !boundary.candidateFormationRuleInventoryHash()
                    .equals(frozen.formationRuleInventoryHash())
                || !boundary.postFreezeCatalogCommitment()
                    .equals(frozen.postFreezeCatalogCommitment())) {
            throw new IllegalArgumentException(
                "information-boundary commitment changed before disclosure");
        }

        List<RepresentationCandidateProposal> proposals =
            frozen.candidates().stream()
                .map(candidate -> RepresentationCandidateProposal.whole(
                    benchmarkCase.sourceExpression(),
                    candidate.expression(),
                    candidate.assumptions(),
                    CandidateProofStatus.OBSERVED
                ))
                .toList();
        var receipt = boundary.freezeCandidates(proposals);
        if (receipt.candidateCount() != frozen.candidateCount()
                || !receipt.candidateSetHash().equals(
                    frozen.candidateSetHash())
                || !receipt.contentHash().equals(
                    frozen.candidateFreezeReceiptHash())) {
            throw new IllegalArgumentException(
                "candidate set changed before qualification");
        }
        var disclosure = boundary.disclosePostFreeze(receipt);
        if (!disclosure.classificationCatalog().contentHash().equals(
                frozen.postFreezeCatalogCommitment())
                || !disclosure.formationRuleInventoryHash().equals(
                    frozen.formationRuleInventoryHash())) {
            throw new IllegalArgumentException(
                "post-freeze disclosure differs from its commitment");
        }

        RepresentationCandidateAssessor assessor =
            new RepresentationCandidateAssessor(
                disclosure.classificationCatalog());
        AstRewriteTransformationEngine engine =
            AstRewriteTransformationEngine.withKnowledgePacks(
                disclosure.classificationSelection());
        List<QualifiedCandidate> candidates = frozen.candidates().stream()
            .map(candidate -> classify(
                benchmarkCase,
                rule,
                candidate,
                assessor,
                engine,
                oracle
            ))
            .sorted(Comparator.comparing(
                QualifiedCandidate::candidateHash))
            .toList();
        int qualified = Math.toIntExact(candidates.stream()
            .filter(QualifiedCandidate::qualified).count());
        return new QualificationEntry(
            frozen.sequence(),
            frozen.configurationId(),
            frozen.caseId(),
            frozen.policyId(),
            frozen.candidateBatchHash(),
            frozen.candidateSetHash(),
            frozen.candidateFreezeReceiptHash(),
            frozen.work().contentHash(),
            frozen.workAuthorityHash(),
            disclosure.contentHash(),
            disclosure.classificationCatalog().contentHash(),
            disclosure.classificationRuleInventoryHash(),
            qualified > 0 ? QUALIFIED : UNQUALIFIED,
            candidates,
            qualified
        );
    }

    private static QualifiedCandidate classify(
        TargetFreeRepresentationEvaluationPlan.CaseDefinition benchmarkCase,
        QualificationRule rule,
        TargetFreeRepresentationCandidateFreeze.CandidateEvidence candidate,
        RepresentationCandidateAssessor assessor,
        AstRewriteTransformationEngine engine,
        SymPyOracleValidator oracle
    ) {
        boolean reference = rule.referenceExpressions().stream()
            .map(TargetFreeRepresentationPostFreezeQualification::normalize)
            .anyMatch(normalize(candidate.expression())::equals);
        Proof proof = proof(
            benchmarkCase.sourceExpression(), candidate, reference, oracle);
        RepresentationCandidateAssessment assessment = assessor.assess(
            RepresentationCandidateProposal.whole(
                benchmarkCase.sourceExpression(),
                candidate.expression(),
                candidate.assumptions(),
                proof.status()
            )
        );
        int tokenSavings = assessment.wholeSourceMetrics().tokenCount()
            - assessment.wholeCandidateMetrics().tokenCount();
        Set<String> types = Set.copyOf(assessment.candidateTypes());
        boolean assumptions = candidate.assumptions().containsAll(
            rule.requiredAssumptions());
        List<String> unlocked =
            assessment.newlyUnlockedConsequences().stream()
                .map(KnownStructureConsequenceUnlock::consequenceId)
                .distinct().sorted().toList();
        List<String> executable = reference
            ? executable(engine, candidate.expression(),
                rule.requiredCapabilities())
            : List.of();
        boolean acceptedType = rule.acceptedCandidateTypes().stream()
            .anyMatch(types::contains);
        boolean capabilitiesUnlocked =
            unlocked.containsAll(rule.requiredCapabilities());
        boolean capabilitiesExecutable =
            executable.containsAll(rule.requiredCapabilities());

        TreeSet<String> forbidden = new TreeSet<>();
        for (String outcome : rule.forbiddenOutcomes()) {
            boolean present = switch (outcome) {
                case "ASSUMPTION_DROPPED" -> !assumptions;
                case "FORMATION_USED_POST_FREEZE_RULE" ->
                    usedWithheldRule(
                        candidate, rule.requiredCapabilities());
                case "ALIAS_ONLY_COMPRESSION" ->
                    !assessment.introducedVariableSymbols().isEmpty()
                        || !assessment.introducedFunctionSymbols().isEmpty();
                case "WRONG_OCCURRENCE_PROVENANCE" ->
                    assessment.newlyExposedStructureMatches().stream()
                        .noneMatch(match -> !match.wholeExpression());
                case "ALPHA_OR_AC_ONLY_GAIN" ->
                    !assessment.materialRepresentationGain();
                case "LABEL_WITHOUT_EXECUTABLE_CONSEQUENCE" ->
                    !capabilitiesExecutable;
                default -> throw new IllegalArgumentException(
                    "unknown forbidden qualification outcome " + outcome);
            };
            if (present) {
                forbidden.add(outcome);
            }
        }

        TreeSet<String> reasons = new TreeSet<>();
        reject(reasons, !reference, "REFERENCE_NOT_MATCHED");
        reject(reasons, !assumptions, "REQUIRED_ASSUMPTION_MISSING");
        reject(reasons, tokenSavings < rule.minimumTokenSavings(),
            "TOKEN_SAVINGS_BELOW_MINIMUM");
        reject(reasons, !acceptedType,
            "ACCEPTED_CANDIDATE_TYPE_NOT_OBSERVED");
        reject(reasons, !capabilitiesUnlocked,
            "REQUIRED_CAPABILITY_NOT_UNLOCKED");
        reject(reasons, !capabilitiesExecutable,
            "REQUIRED_CAPABILITY_NOT_EXECUTABLE");
        reject(reasons, !proof.status().atLeast(
                CandidateProofStatus.SYMBOLICALLY_VERIFIED),
            "SYMBOLIC_VALIDATION_NOT_CONFIRMED");
        reject(reasons, !forbidden.isEmpty(),
            "FORBIDDEN_OUTCOME_OBSERVED");

        List<String> structures =
            assessment.newlyExposedStructureMatches().stream()
                .map(match -> match.structureId()
                    + "@" + match.occurrencePath())
                .distinct().sorted().toList();
        return new QualifiedCandidate(
            candidate.candidateHash(),
            candidate.expression(),
            candidate.assumptions(),
            proof.status().name(),
            proof.oracleStatus(),
            reference,
            tokenSavings,
            assessment.compressionStatus(),
            assessment.candidateTypes(),
            structures,
            unlocked,
            executable,
            List.copyOf(forbidden),
            List.copyOf(reasons),
            reasons.isEmpty()
        );
    }

    private static Proof proof(
        String source,
        TargetFreeRepresentationCandidateFreeze.CandidateEvidence candidate,
        boolean reference,
        SymPyOracleValidator oracle
    ) {
        if (!reference) {
            return new Proof(
                CandidateProofStatus.OBSERVED,
                "NOT_RUN_REFERENCE_MISS");
        }
        if (!candidate.equivalencePreserving()) {
            return new Proof(
                CandidateProofStatus.OBSERVED,
                "NOT_EQUIVALENCE_PRESERVING_BY_CONSTRUCTION");
        }
        try {
            var validation = oracle.validateEquivalence(
                source, candidate.expression());
            return new Proof(
                validation.status()
                    == OracleValidator.OracleValidationStatus.AGREE
                    ? CandidateProofStatus.SYMBOLICALLY_VERIFIED
                    : CandidateProofStatus.OBSERVED,
                validation.status().name()
            );
        } catch (RuntimeException exception) {
            return new Proof(
                CandidateProofStatus.OBSERVED,
                "VALIDATOR_ERROR_"
                    + exception.getClass().getSimpleName());
        }
    }

    private static List<String> executable(
        AstRewriteTransformationEngine engine,
        String expression,
        List<String> capabilities
    ) {
        if (capabilities.isEmpty()) {
            return List.of();
        }
        List<Transformation> successors = engine.transform(expression);
        TreeSet<String> result = new TreeSet<>();
        for (String capability : capabilities) {
            String rule = ruleId(capability);
            boolean present = engine.rules().stream()
                .anyMatch(value -> value.id().equals(rule));
            boolean executed = successors.stream().anyMatch(value ->
                value.rule().equals(rule)
                    && !normalize(value.transformedExpression())
                        .equals(normalize(expression)));
            if (present && executed) {
                result.add(capability);
            }
        }
        return List.copyOf(result);
    }

    private static boolean usedWithheldRule(
        TargetFreeRepresentationCandidateFreeze.CandidateEvidence candidate,
        List<String> capabilities
    ) {
        Set<String> rules = new TreeSet<>();
        capabilities.stream().map(
            TargetFreeRepresentationPostFreezeQualification::ruleId)
            .forEach(rules::add);
        return candidate.pathRuleIds().stream().anyMatch(rules::contains)
            || candidate.primitiveRuleIds().stream()
                .anyMatch(rules::contains);
    }

    private static KnowledgePackSelection visibleSelection(
        TargetFreeRepresentationEvaluationPlan.CaseDefinition benchmarkCase
    ) {
        KnowledgePackSelection selection = KnowledgePackSelection.profile(
            benchmarkCase.ruleProfile());
        for (String pack : benchmarkCase.enabledRulePackIds()) {
            selection = selection.enablePack(pack);
        }
        return selection;
    }

    private static void requireFrozenAuthority(
        TargetFreeRepresentationCandidateFreeze.FreezeArtifact freeze,
        TargetFreeRepresentationEvaluationPlan.EvaluationPlan plan
    ) {
        var content = freeze.content();
        List<String> expected = plan.content().entries().stream()
            .map(TargetFreeRepresentationEvaluationPlan.PlanEntry
                ::configurationId)
            .toList();
        List<String> actual = content.entries().stream()
            .map(TargetFreeRepresentationCandidateFreeze.ExecutionEntry
                ::configurationId)
            .toList();
        if (!content.repositoryRevision().equals(
                plan.content().repositoryRevision())
                || !content.evaluationPlanHash().equals(plan.contentHash())
                || !content.preregistrationHash().equals(
                    plan.content().preregistrationHash())
                || !content.formationHash().equals(
                    plan.content().formationHash())
                || !content.qualificationHash().equals(
                    plan.content().qualificationHash())
                || !TargetFreeRepresentationCandidateFreeze
                    .QUALIFICATION_DISCLOSURE.equals(
                        content.qualificationDisclosure())
                || !expected.equals(actual)) {
            throw new IllegalArgumentException(
                "candidate freeze is not the exact frozen matrix authority");
        }
    }

    private static void requireQualification(
        Qualification qualification,
        TargetFreeRepresentationEvaluationPlan.EvaluationPlan plan
    ) {
        List<String> expected = plan.content().cases().stream()
            .map(TargetFreeRepresentationEvaluationPlan.CaseDefinition::id)
            .toList();
        List<String> actual = qualification.cases().stream()
            .map(QualificationRule::id)
            .toList();
        if (!QUALIFICATION_SCHEMA.equals(qualification.schema())
                || !SEALED.equals(qualification.evidenceStatus())
                || !expected.equals(actual)) {
            throw new IllegalArgumentException(
                "sealed qualification differs from the preregistration");
        }
        qualification.cases().forEach(rule -> {
            if (rule.minimumTokenSavings() < 0
                    || rule.referenceExpressions().isEmpty()
                    || rule.acceptedCandidateTypes().isEmpty()) {
                throw new IllegalArgumentException(
                    "qualification rule is incomplete: " + rule.id());
            }
            rule.requiredCapabilities().forEach(
                TargetFreeRepresentationPostFreezeQualification::ruleId);
        });
    }

    private static String normalize(String value) {
        return ExpressionFormatter.format(
            QUALIFICATION_PARSER.parseTerm(requireText(
                value, "expression")));
    }

    private static String ruleId(String value) {
        String capability = requireText(value, "capability");
        if (!capability.startsWith(RULE_PREFIX)
                || capability.length() == RULE_PREFIX.length()) {
            throw new IllegalArgumentException(
                "capability must use rule: identity");
        }
        return capability.substring(RULE_PREFIX.length());
    }

    private static void reject(
        Set<String> reasons,
        boolean condition,
        String reason
    ) {
        if (condition) {
            reasons.add(reason);
        }
    }

    private static <T> T decode(byte[] bytes, Class<T> type) {
        try {
            return QUALIFICATION_JSON.readValue(bytes, type);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                "invalid sealed qualification resource", exception);
        }
    }

    private static String json(Object value) {
        try {
            return QUALIFICATION_JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "cannot render post-freeze qualification", exception);
        }
    }

    record Qualification(
        String schema,
        String evidenceStatus,
        List<QualificationRule> cases,
        String claimBoundary
    ) {
        Qualification {
            cases = List.copyOf(cases);
            claimBoundary = requireText(
                claimBoundary, "qualification claimBoundary");
        }
    }

    record QualificationRule(
        String id,
        List<String> acceptedCandidateTypes,
        List<String> forbiddenOutcomes,
        int minimumTokenSavings,
        List<String> referenceExpressions,
        List<String> requiredAssumptions,
        List<String> requiredCapabilities
    ) {
        QualificationRule {
            id = requireText(id, "qualification id");
            acceptedCandidateTypes =
                List.copyOf(new TreeSet<>(acceptedCandidateTypes));
            forbiddenOutcomes =
                List.copyOf(new TreeSet<>(forbiddenOutcomes));
            referenceExpressions =
                List.copyOf(new TreeSet<>(referenceExpressions));
            requiredAssumptions =
                List.copyOf(new TreeSet<>(requiredAssumptions));
            requiredCapabilities =
                List.copyOf(new TreeSet<>(requiredCapabilities));
        }
    }

    record QualifiedCandidate(
        String candidateHash,
        String expression,
        List<String> assumptions,
        String proofStatus,
        String oracleStatus,
        boolean referenceMatched,
        int tokenSavings,
        String compressionStatus,
        List<String> candidateTypes,
        List<String> newlyExposedStructures,
        List<String> unlockedCapabilities,
        List<String> executableCapabilities,
        List<String> forbiddenOutcomes,
        List<String> disqualificationReasons,
        boolean qualified
    ) {
        QualifiedCandidate {
            candidateHash = requireSha256(
                candidateHash, "candidateHash");
            assumptions = List.copyOf(assumptions);
            candidateTypes = List.copyOf(candidateTypes);
            newlyExposedStructures =
                List.copyOf(newlyExposedStructures);
            unlockedCapabilities =
                List.copyOf(unlockedCapabilities);
            executableCapabilities =
                List.copyOf(executableCapabilities);
            forbiddenOutcomes = List.copyOf(forbiddenOutcomes);
            disqualificationReasons =
                List.copyOf(disqualificationReasons);
            if (qualified != disqualificationReasons.isEmpty()) {
                throw new IllegalArgumentException(
                    "qualified flag differs from reasons");
            }
        }
    }

    record QualificationEntry(
        int sequence,
        String configurationId,
        String caseId,
        String policyId,
        String candidateBatchHash,
        String candidateSetHash,
        String candidateFreezeReceiptHash,
        String workLedgerHash,
        String workAuthorityHash,
        String postFreezeDisclosureHash,
        String classificationCatalogHash,
        String classificationRuleInventoryHash,
        String status,
        List<QualifiedCandidate> candidates,
        int qualifyingCandidateCount
    ) {
        QualificationEntry {
            candidates = List.copyOf(candidates);
            int derived = Math.toIntExact(candidates.stream()
                .filter(QualifiedCandidate::qualified).count());
            if (sequence < 1
                    || !Set.of(QUALIFIED, UNQUALIFIED).contains(status)
                    || qualifyingCandidateCount != derived
                    || (QUALIFIED.equals(status) != (derived > 0))) {
                throw new IllegalArgumentException(
                    "qualification entry does not balance");
            }
            for (String hash : List.of(
                    configurationId,
                    candidateBatchHash,
                    candidateSetHash,
                    candidateFreezeReceiptHash,
                    workLedgerHash,
                    workAuthorityHash,
                    postFreezeDisclosureHash,
                    classificationCatalogHash,
                    classificationRuleInventoryHash)) {
                requireSha256(hash, "qualification hash");
            }
        }
    }

    record Summary(
        int configuredEntries,
        int qualifiedEntries,
        int evaluatedCandidates,
        int qualifiedCandidates
    ) {
        static Summary from(List<QualificationEntry> entries) {
            return new Summary(
                entries.size(),
                Math.toIntExact(entries.stream()
                    .filter(value -> QUALIFIED.equals(value.status()))
                    .count()),
                entries.stream()
                    .mapToInt(value -> value.candidates().size()).sum(),
                entries.stream()
                    .mapToInt(QualificationEntry
                        ::qualifyingCandidateCount).sum()
            );
        }
    }

    record QualificationContent(
        String schema,
        String evidenceStatus,
        String repositoryRevision,
        String evaluationPlanHash,
        String candidateFreezeHash,
        String qualificationResource,
        String qualificationHash,
        long qualificationByteLength,
        String qualificationDisclosure,
        List<QualificationEntry> entries,
        Summary summary,
        String qualificationClaimBoundary,
        String claimBoundary
    ) {
        QualificationContent {
            entries = List.copyOf(entries);
            if (!SCHEMA.equals(schema)
                    || !EVIDENCE_STATUS.equals(evidenceStatus)
                    || !DISCLOSURE.equals(qualificationDisclosure)
                    || !repositoryRevision.matches("[0-9a-f]{40}")
                    || qualificationByteLength < 1
                    || !summary.equals(Summary.from(entries))) {
                throw new IllegalArgumentException(
                    "post-freeze qualification content does not balance");
            }
            requireSha256(evaluationPlanHash, "evaluationPlanHash");
            requireSha256(candidateFreezeHash, "candidateFreezeHash");
            requireSha256(qualificationHash, "qualificationHash");
        }
    }

    record QualificationArtifact(
        QualificationContent content,
        String contentHash
    ) {
        QualificationArtifact {
            contentHash = requireSha256(contentHash, "contentHash");
            if (!KnownStructureCatalog.sha256(json(content))
                    .equals(contentHash)) {
                throw new IllegalArgumentException(
                    "post-freeze qualification hash mismatch");
            }
        }

        static QualificationArtifact create(
            QualificationContent content
        ) {
            return new QualificationArtifact(
                content,
                KnownStructureCatalog.sha256(json(content))
            );
        }

        String toCanonicalJson() {
            return json(this);
        }

        static QualificationArtifact fromCanonicalJson(String source) {
            try {
                QualificationArtifact artifact =
                    QUALIFICATION_JSON.readValue(
                        Objects.requireNonNull(source, "source"),
                        QualificationArtifact.class);
                if (!artifact.toCanonicalJson().equals(source)) {
                    throw new IllegalArgumentException(
                        "qualification JSON is not canonical");
                }
                return artifact;
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException(
                    "invalid post-freeze qualification JSON",
                    exception
                );
            }
        }
    }

    private record Proof(
        CandidateProofStatus status,
        String oracleStatus
    ) {
    }
}
