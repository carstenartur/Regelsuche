package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.validation.CounterexampleSearchService;
import de.regelsuche.validation.DeterministicCounterexampleSearchService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Fail-closed production authorization boundary for exactly proved learned
 * pattern rules.
 *
 * <p>The existing {@link LearnedPatternRulePromoter} remains the mathematical
 * boundary: genome preflight plus an exact commutative-polynomial identity
 * proof. This service adds the missing qualification boundary required by
 * issue #745. It does not trust self-declared PASS envelopes. It reconstructs
 * the repository's native split, VALIDATION and FINAL TEST artifacts, replays
 * deterministic counterexample search, cross-checks all identities, and only
 * then invokes the promoter.</p>
 *
 * <p>This class deliberately does not authorize {@code RewriteProgram}. A
 * program has sequence/choice/repeat/guard/pruning semantics and needs its own
 * replay contract.</p>
 */
public final class LearnedPatternRuleAuthorizationService {
    public static final String BUNDLE_SCHEMA =
        "regelsuche.learned-pattern-rule-authorization-bundle/v1";
    public static final String COUNTEREXAMPLE_SCHEMA =
        "regelsuche.learned-pattern-rule-counterexample-evidence/v1";
    public static final String COUNTEREXAMPLE_ENGINE_ID =
        "regelsuche.deterministic-counterexample-search/pattern-authorization-v1";
    public static final String AUTHORIZATION_RECEIPT_SCHEMA =
        "regelsuche.learned-pattern-rule-authorization-receipt/v1";
    public static final String AUTHORIZER_ID =
        "regelsuche.learned-pattern-rule-authorizer/v1";

    /**
     * Fixed deterministic scalar/commutative challenge budget for promotion.
     * Matrix assignments are intentionally excluded because the promoted v1
     * proof contract is the commutative polynomial ring, not matrix algebra.
     */
    private static final CounterexampleSearchService.CounterexampleBudget
        AUTHORIZATION_COUNTEREXAMPLE_BUDGET =
            new CounterexampleSearchService.CounterexampleBudget(
                64, true, false, 745L, true, true, 0, 0L);

    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
        .findAndRegisterModules()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final LearnedPatternRulePromoter promoter;
    private final CounterexampleSearchService counterexamples;
    private final EvolutionStudyContractCodec studyCodec;

    public LearnedPatternRuleAuthorizationService() {
        this(
            new LearnedPatternRulePromoter(),
            new DeterministicCounterexampleSearchService(),
            new EvolutionStudyContractCodec());
    }

    LearnedPatternRuleAuthorizationService(
        LearnedPatternRulePromoter promoter,
        CounterexampleSearchService counterexamples,
        EvolutionStudyContractCodec studyCodec
    ) {
        this.promoter = Objects.requireNonNull(promoter, "promoter");
        this.counterexamples = Objects.requireNonNull(
            counterexamples, "counterexamples");
        this.studyCodec = Objects.requireNonNull(studyCodec, "studyCodec");
    }

    /** Returns the exact replay budget required by this authorization revision. */
    public static CounterexampleSearchService.CounterexampleBudget
            authorizationCounterexampleBudget() {
        return AUTHORIZATION_COUNTEREXAMPLE_BUDGET;
    }

    /**
     * Produces the gene-specific counterexample artifact later consumed and
     * independently replayed by {@link #authorize}.
     */
    public CounterexampleEvidence evaluateCounterexamples(
        EvolutionGenome genome,
        String geneId,
        String repositoryRevision
    ) {
        Objects.requireNonNull(genome, "genome");
        EvolutionGenome.RewriteGene gene = gene(genome, geneId);
        requireRevision(repositoryRevision, "repositoryRevision");
        PatternSubject subject = patternSubject(gene);
        CounterexampleSearchService.CounterexampleSearchResult result =
            counterexamples.search(
                new CounterexampleSearchService.HypothesisInput(
                    geneId,
                    subject.leftExpression(),
                    subject.rightExpression(),
                    List.of()),
                AUTHORIZATION_COUNTEREXAMPLE_BUDGET);
        return CounterexampleEvidence.create(
            genome,
            gene,
            repositoryRevision,
            subject,
            AUTHORIZATION_COUNTEREXAMPLE_BUDGET,
            result);
    }

    /**
     * Loads, reconstructs and cross-verifies every qualification artifact.
     *
     * @param asOf explicit deterministic validity instant; no implicit wall
     *             clock participates in authorization
     */
    public Authorization authorize(
        EvolutionGenome genome,
        String geneId,
        String repositoryRevision,
        EvidenceFiles evidenceFiles,
        Instant asOf
    ) throws IOException {
        Objects.requireNonNull(genome, "genome");
        EvolutionGenome.RewriteGene gene = gene(genome, geneId);
        requireRevision(repositoryRevision, "repositoryRevision");
        Objects.requireNonNull(evidenceFiles, "evidenceFiles");
        Objects.requireNonNull(asOf, "asOf");

        EvidenceBundle bundle = EvidenceBundle.fromCanonicalJson(
            readRegularFile(evidenceFiles.bundle(), "authorization bundle"));
        bundle.requireUsableAt(
            asOf, genome.contentHash(), geneId, repositoryRevision);

        EvolutionSplitManifest split = studyCodec.readSplitManifest(
            readRegularFile(evidenceFiles.splitManifest(), "split manifest"));
        EvolutionValidationSelection validation =
            EvolutionValidationSelection.fromCanonicalJson(
                readRegularFile(
                    evidenceFiles.validationSelection(),
                    "validation selection"));
        EvolutionFinalTestEvaluation holdout =
            EvolutionFinalTestEvaluation.fromCanonicalJson(
                readRegularFile(
                    evidenceFiles.finalTestEvaluation(),
                    "FINAL TEST evaluation"));
        CounterexampleEvidence counterexample =
            CounterexampleEvidence.fromCanonicalJson(
                readRegularFile(
                    evidenceFiles.counterexampleEvidence(),
                    "counterexample evidence"));

        requireHashMatch(
            bundle.splitManifestHash(), split.contentHash(), "split manifest");
        requireHashMatch(
            bundle.validationSelectionHash(),
            validation.contentHash(),
            "validation selection");
        requireHashMatch(
            bundle.finalTestEvaluationHash(),
            holdout.contentHash(),
            "FINAL TEST evaluation");
        requireHashMatch(
            bundle.counterexampleEvidenceHash(),
            counterexample.contentHash(),
            "counterexample evidence");

        verifySplit(genome, split);
        verifyValidation(genome, split, validation);
        verifyHoldout(genome, split, validation, holdout);
        verifyCounterexample(
            genome, gene, repositoryRevision, counterexample);

        LearnedPatternRulePromoter.PromotionEvidence promotionEvidence =
            new LearnedPatternRulePromoter.PromotionEvidence(
                validation.contentHash(),
                counterexample.contentHash(),
                holdout.contentHash(),
                split.contentHash(),
                repositoryRevision);
        LearnedPatternRulePromoter.Promotion promotion =
            promoter.promote(genome, geneId, promotionEvidence);

        AuthorizationReceipt receipt = AuthorizationReceipt.create(
            genome,
            geneId,
            repositoryRevision,
            asOf,
            bundle,
            split,
            validation,
            holdout,
            counterexample,
            promotion);
        return new Authorization(
            promotion,
            bundle,
            split,
            validation,
            holdout,
            counterexample,
            receipt);
    }

    private static void verifySplit(
        EvolutionGenome genome,
        EvolutionSplitManifest split
    ) {
        if (split.heldOutMaterializationDeferred()) {
            throw new IllegalArgumentException(
                "learned-rule authorization requires concrete VALIDATION and FINAL TEST splits");
        }
        if (!genome.trainingScope().equals(split.trainingScope())) {
            throw new IllegalArgumentException(
                "split manifest TRAIN scope differs from learned genome");
        }
    }

    private static void verifyValidation(
        EvolutionGenome genome,
        EvolutionSplitManifest split,
        EvolutionValidationSelection validation
    ) {
        if (!validation.splitManifestHash().equals(split.contentHash())) {
            throw new IllegalArgumentException(
                "validation selection is not bound to the supplied split manifest");
        }
        if (!validation.hasSelection()
                || !validation.selectedGenomeHash().equals(genome.contentHash())) {
            throw new IllegalArgumentException(
                "VALIDATION did not select the learned genome being authorized");
        }
        EvolutionValidationCandidate selected = validation.candidates().stream()
            .filter(candidate -> candidate.genomeHash().equals(genome.contentHash()))
            .filter(candidate -> candidate.configurationHash().equals(
                validation.selectedConfigurationHash()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "selected VALIDATION candidate is missing"));
        if (!selected.eligible()) {
            throw new IllegalArgumentException(
                "selected VALIDATION candidate has correctness/reachability blockers");
        }
    }

    private static void verifyHoldout(
        EvolutionGenome genome,
        EvolutionSplitManifest split,
        EvolutionValidationSelection validation,
        EvolutionFinalTestEvaluation holdout
    ) {
        if (!holdout.splitManifestHash().equals(split.contentHash())
                || !holdout.validationSelectionHash().equals(
                    validation.contentHash())) {
            throw new IllegalArgumentException(
                "FINAL TEST does not continue the supplied split and VALIDATION selection");
        }
        if (!holdout.selectedGenomeHash().equals(genome.contentHash())
                || !holdout.selectedConfigurationHash().equals(
                    validation.selectedConfigurationHash())) {
            throw new IllegalArgumentException(
                "FINAL TEST subject differs from the learned genome/configuration");
        }
        if (!holdout.qualificationEligible()) {
            throw new IllegalArgumentException(
                "FINAL TEST contains technical, reachability or correctness blockers");
        }
    }

    private void verifyCounterexample(
        EvolutionGenome genome,
        EvolutionGenome.RewriteGene gene,
        String repositoryRevision,
        CounterexampleEvidence evidence
    ) {
        PatternSubject subject = patternSubject(gene);
        evidence.requireSubject(
            genome.contentHash(),
            gene.geneId(),
            repositoryRevision,
            subject);
        if (!evidence.budget().toBudget().equals(
                AUTHORIZATION_COUNTEREXAMPLE_BUDGET)) {
            throw new IllegalArgumentException(
                "counterexample evidence uses a non-authorized replay budget");
        }

        CounterexampleSearchService.CounterexampleSearchResult replay =
            counterexamples.search(
                new CounterexampleSearchService.HypothesisInput(
                    gene.geneId(),
                    subject.leftExpression(),
                    subject.rightExpression(),
                    List.of()),
                AUTHORIZATION_COUNTEREXAMPLE_BUDGET);
        String replayHash = resultHash(replay);
        if (!replayHash.equals(evidence.resultHash())
                || replay.status() != evidence.status()
                || !replay.attemptedSources().equals(evidence.attemptedSources())
                || !replay.inferredAssumptions().equals(
                    evidence.inferredAssumptions())
                || !replay.explanation().equals(evidence.explanation())) {
            throw new IllegalArgumentException(
                "counterexample evidence differs from deterministic replay");
        }
        if (replay.status()
                != CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND
                || replay.counterexample().isPresent()
                || replay.attemptedSources().isEmpty()
                || !replay.inferredAssumptions().isEmpty()
                || !replay.typedAssumptions().isEmpty()) {
            throw new IllegalArgumentException(
                "counterexample qualification is not assumption-free and conclusive");
        }
    }

    private static EvolutionGenome.RewriteGene gene(
        EvolutionGenome genome,
        String geneId
    ) {
        requireText(geneId, "geneId");
        return genome.rewrites().stream()
            .filter(value -> value.geneId().equals(geneId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "unknown rewrite gene: " + geneId));
    }

    private static PatternSubject patternSubject(
        EvolutionGenome.RewriteGene gene
    ) {
        PatternExpr source = EvolutionGenomeCompiler.parsePattern(
            gene.sourcePattern());
        PatternExpr target = EvolutionGenomeCompiler.parsePattern(
            gene.targetPattern());
        Set<String> placeholders = new TreeSet<>();
        Set<String> literalVariables = new HashSet<>();
        collectNames(source, placeholders, literalVariables);
        collectNames(target, placeholders, literalVariables);

        Map<String, Expr> bindings = new HashMap<>();
        int index = 0;
        for (String placeholder : placeholders) {
            String variable;
            do {
                variable = "p" + index++;
            } while (literalVariables.contains(variable));
            bindings.put(placeholder, new VariableExpr(variable));
        }
        return new PatternSubject(
            EvolutionGenomeCompiler.renderPattern(source),
            EvolutionGenomeCompiler.renderPattern(target),
            ExpressionFormatter.format(source.instantiate(bindings)),
            ExpressionFormatter.format(target.instantiate(bindings)));
    }

    private static void collectNames(
        PatternExpr expression,
        Set<String> placeholders,
        Set<String> literalVariables
    ) {
        if (expression instanceof PatternExpr.Placeholder placeholder) {
            placeholders.add(placeholder.name());
            return;
        }
        if (expression instanceof PatternExpr.LiteralVariable variable) {
            literalVariables.add(variable.name());
            return;
        }
        if (expression instanceof PatternExpr.Operation operation) {
            collectNames(operation.left(), placeholders, literalVariables);
            collectNames(operation.right(), placeholders, literalVariables);
            return;
        }
        if (expression instanceof PatternExpr.Function function) {
            function.arguments().forEach(argument ->
                collectNames(argument, placeholders, literalVariables));
        }
    }

    private static String readRegularFile(Path path, String name)
            throws IOException {
        Objects.requireNonNull(path, name + " path");
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException(
                name + " must be a regular non-symlink file: " + normalized);
        }
        return Files.readString(normalized, StandardCharsets.UTF_8);
    }

    private static void requireHashMatch(
        String expected,
        String actual,
        String name
    ) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(name + " hash mismatch");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String requireRevision(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                field + " must be a lowercase commit SHA");
        }
        return value;
    }

    private static void requireHash(String value, String field) {
        EvolutionGenome.requireSha256(value, field);
    }

    private static <T> T readJson(String json, Class<T> type, String name) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(name + " JSON must not be blank");
        }
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid " + name + " JSON", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value) + "\n";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize authorization evidence", exception);
        }
    }

    private static String hashMaterial(Map<String, ?> value) {
        return EvolutionGenome.hash(writeJson(value));
    }

    private static String resultHash(
        CounterexampleSearchService.CounterexampleSearchResult result
    ) {
        StringBuilder material = new StringBuilder();
        append(material, result.status().name());
        appendList(material, result.inferredAssumptions());
        appendList(material, result.attemptedSources());
        append(material, result.explanation());
        if (result.counterexample().isPresent()) {
            CounterexampleSearchService.Counterexample counterexample =
                result.counterexample().orElseThrow();
            append(material, "COUNTEREXAMPLE");
            appendList(material, counterexample.assignments());
            append(material, counterexample.leftValue());
            append(material, counterexample.rightValue());
        } else {
            append(material, "NO_COUNTEREXAMPLE");
        }
        for (CounterexampleSearchService.TypedAssumption assumption
                : result.typedAssumptions()) {
            append(material, assumption.kind().name());
            append(material, assumption.normalizedPredicate());
            append(material, assumption.subjectExpression());
            appendList(material, assumption.affectedVariables());
            appendList(material, assumption.evidenceSources());
            for (CounterexampleSearchService.ProofEncoding proof
                    : assumption.proofEncodings()) {
                append(material, proof.dialect());
                append(material, proof.expression());
            }
            append(material, assumption.classification().name());
        }
        return EvolutionGenome.hash(material.toString());
    }

    private static void appendList(StringBuilder target, List<String> values) {
        append(target, Integer.toString(values.size()));
        values.forEach(value -> append(target, value));
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private record PatternSubject(
        String sourcePattern,
        String targetPattern,
        String leftExpression,
        String rightExpression
    ) {
    }

    /** Exact files consumed by one authorization attempt. */
    public record EvidenceFiles(
        Path bundle,
        Path splitManifest,
        Path validationSelection,
        Path finalTestEvaluation,
        Path counterexampleEvidence
    ) {
        public EvidenceFiles {
            List<Path> paths = List.of(
                Objects.requireNonNull(bundle, "bundle"),
                Objects.requireNonNull(splitManifest, "splitManifest"),
                Objects.requireNonNull(validationSelection, "validationSelection"),
                Objects.requireNonNull(finalTestEvaluation, "finalTestEvaluation"),
                Objects.requireNonNull(counterexampleEvidence, "counterexampleEvidence"));
            Set<Path> distinct = new HashSet<>();
            for (Path path : paths) {
                if (!distinct.add(path.toAbsolutePath().normalize())) {
                    throw new IllegalArgumentException(
                        "authorization evidence files must be distinct");
                }
            }
        }
    }

    /**
     * Temporal/identity manifest around self-verifying native evidence. It has
     * no PASS field and therefore cannot replace semantic verification.
     */
    public record EvidenceBundle(
        String schema,
        String genomeHash,
        String geneId,
        String repositoryRevision,
        Instant issuedAt,
        Instant expiresAt,
        String splitManifestHash,
        String validationSelectionHash,
        String finalTestEvaluationHash,
        String counterexampleEvidenceHash,
        String contentHash
    ) {
        public EvidenceBundle {
            if (!BUNDLE_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported learned-rule authorization bundle schema");
            }
            requireHash(genomeHash, "genomeHash");
            requireText(geneId, "geneId");
            requireRevision(repositoryRevision, "repositoryRevision");
            issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            if (!issuedAt.isBefore(expiresAt)) {
                throw new IllegalArgumentException(
                    "authorization bundle expiresAt must be after issuedAt");
            }
            requireHash(splitManifestHash, "splitManifestHash");
            requireHash(validationSelectionHash, "validationSelectionHash");
            requireHash(finalTestEvaluationHash, "finalTestEvaluationHash");
            requireHash(counterexampleEvidenceHash, "counterexampleEvidenceHash");
            requireHash(contentHash, "contentHash");
            if (!hashMaterial(payload(
                    schema, genomeHash, geneId, repositoryRevision,
                    issuedAt, expiresAt, splitManifestHash,
                    validationSelectionHash, finalTestEvaluationHash,
                    counterexampleEvidenceHash)).equals(contentHash)) {
                throw new IllegalArgumentException(
                    "authorization bundle contentHash mismatch");
            }
        }

        public static EvidenceBundle create(
            EvolutionGenome genome,
            String geneId,
            String repositoryRevision,
            Instant issuedAt,
            Instant expiresAt,
            EvolutionSplitManifest split,
            EvolutionValidationSelection validation,
            EvolutionFinalTestEvaluation holdout,
            CounterexampleEvidence counterexample
        ) {
            Objects.requireNonNull(genome, "genome");
            requireText(geneId, "geneId");
            requireRevision(repositoryRevision, "repositoryRevision");
            String contentHash = hashMaterial(payload(
                BUNDLE_SCHEMA, genome.contentHash(), geneId,
                repositoryRevision, issuedAt, expiresAt,
                split.contentHash(), validation.contentHash(),
                holdout.contentHash(), counterexample.contentHash()));
            return new EvidenceBundle(
                BUNDLE_SCHEMA, genome.contentHash(), geneId,
                repositoryRevision, issuedAt, expiresAt,
                split.contentHash(), validation.contentHash(),
                holdout.contentHash(), counterexample.contentHash(),
                contentHash);
        }

        public static EvidenceBundle fromCanonicalJson(String json) {
            return readJson(json, EvidenceBundle.class, "authorization bundle");
        }

        public String toCanonicalJson() {
            return writeJson(this);
        }

        void requireUsableAt(
            Instant asOf,
            String expectedGenomeHash,
            String expectedGeneId,
            String expectedRepositoryRevision
        ) {
            if (asOf.isBefore(issuedAt) || !asOf.isBefore(expiresAt)) {
                throw new IllegalArgumentException(
                    "authorization evidence bundle is not valid at " + asOf);
            }
            if (!genomeHash.equals(expectedGenomeHash)
                    || !geneId.equals(expectedGeneId)
                    || !repositoryRevision.equals(expectedRepositoryRevision)) {
                throw new IllegalArgumentException(
                    "authorization evidence bundle subject/revision mismatch");
            }
        }

        private static Map<String, Object> payload(
            String schema,
            String genomeHash,
            String geneId,
            String repositoryRevision,
            Instant issuedAt,
            Instant expiresAt,
            String splitManifestHash,
            String validationSelectionHash,
            String finalTestEvaluationHash,
            String counterexampleEvidenceHash
        ) {
            Map<String, Object> value = new TreeMap<>();
            value.put("counterexampleEvidenceHash", counterexampleEvidenceHash);
            value.put("expiresAt", expiresAt);
            value.put("finalTestEvaluationHash", finalTestEvaluationHash);
            value.put("geneId", geneId);
            value.put("genomeHash", genomeHash);
            value.put("issuedAt", issuedAt);
            value.put("repositoryRevision", repositoryRevision);
            value.put("schema", schema);
            value.put("splitManifestHash", splitManifestHash);
            value.put("validationSelectionHash", validationSelectionHash);
            return value;
        }
    }

    /** Replayable counterexample-search evidence for one exact learned gene. */
    public record CounterexampleEvidence(
        String schema,
        String engineId,
        String genomeHash,
        String geneId,
        String repositoryRevision,
        String sourcePattern,
        String targetPattern,
        String leftExpression,
        String rightExpression,
        CounterexampleBudgetEvidence budget,
        CounterexampleSearchService.Status status,
        List<String> attemptedSources,
        List<String> inferredAssumptions,
        String explanation,
        String resultHash,
        String contentHash
    ) {
        public CounterexampleEvidence {
            if (!COUNTEREXAMPLE_SCHEMA.equals(schema)
                    || !COUNTEREXAMPLE_ENGINE_ID.equals(engineId)) {
                throw new IllegalArgumentException(
                    "unsupported counterexample evidence identity");
            }
            requireHash(genomeHash, "genomeHash");
            requireText(geneId, "geneId");
            requireRevision(repositoryRevision, "repositoryRevision");
            requireText(sourcePattern, "sourcePattern");
            requireText(targetPattern, "targetPattern");
            requireText(leftExpression, "leftExpression");
            requireText(rightExpression, "rightExpression");
            budget = Objects.requireNonNull(budget, "budget");
            status = Objects.requireNonNull(status, "status");
            attemptedSources = List.copyOf(
                Objects.requireNonNull(attemptedSources, "attemptedSources"));
            inferredAssumptions = List.copyOf(
                Objects.requireNonNull(inferredAssumptions, "inferredAssumptions"));
            requireText(explanation, "explanation");
            requireHash(resultHash, "resultHash");
            requireHash(contentHash, "contentHash");
            if (!hashMaterial(payload(
                    schema, engineId, genomeHash, geneId,
                    repositoryRevision, sourcePattern, targetPattern,
                    leftExpression, rightExpression, budget, status,
                    attemptedSources, inferredAssumptions, explanation,
                    resultHash)).equals(contentHash)) {
                throw new IllegalArgumentException(
                    "counterexample evidence contentHash mismatch");
            }
        }

        private static CounterexampleEvidence create(
            EvolutionGenome genome,
            EvolutionGenome.RewriteGene gene,
            String repositoryRevision,
            PatternSubject subject,
            CounterexampleSearchService.CounterexampleBudget budget,
            CounterexampleSearchService.CounterexampleSearchResult result
        ) {
            CounterexampleBudgetEvidence retainedBudget =
                CounterexampleBudgetEvidence.fromBudget(budget);
            String retainedResultHash = resultHash(result);
            String contentHash = hashMaterial(payload(
                COUNTEREXAMPLE_SCHEMA, COUNTEREXAMPLE_ENGINE_ID,
                genome.contentHash(), gene.geneId(), repositoryRevision,
                subject.sourcePattern(), subject.targetPattern(),
                subject.leftExpression(), subject.rightExpression(),
                retainedBudget, result.status(), result.attemptedSources(),
                result.inferredAssumptions(), result.explanation(),
                retainedResultHash));
            return new CounterexampleEvidence(
                COUNTEREXAMPLE_SCHEMA, COUNTEREXAMPLE_ENGINE_ID,
                genome.contentHash(), gene.geneId(), repositoryRevision,
                subject.sourcePattern(), subject.targetPattern(),
                subject.leftExpression(), subject.rightExpression(),
                retainedBudget, result.status(), result.attemptedSources(),
                result.inferredAssumptions(), result.explanation(),
                retainedResultHash, contentHash);
        }

        public static CounterexampleEvidence fromCanonicalJson(String json) {
            return readJson(json, CounterexampleEvidence.class,
                "counterexample evidence");
        }

        public String toCanonicalJson() {
            return writeJson(this);
        }

        void requireSubject(
            String expectedGenomeHash,
            String expectedGeneId,
            String expectedRepositoryRevision,
            PatternSubject subject
        ) {
            if (!genomeHash.equals(expectedGenomeHash)
                    || !geneId.equals(expectedGeneId)
                    || !repositoryRevision.equals(expectedRepositoryRevision)
                    || !sourcePattern.equals(subject.sourcePattern())
                    || !targetPattern.equals(subject.targetPattern())
                    || !leftExpression.equals(subject.leftExpression())
                    || !rightExpression.equals(subject.rightExpression())) {
                throw new IllegalArgumentException(
                    "counterexample evidence subject/revision mismatch");
            }
        }

        private static Map<String, Object> payload(
            String schema,
            String engineId,
            String genomeHash,
            String geneId,
            String repositoryRevision,
            String sourcePattern,
            String targetPattern,
            String leftExpression,
            String rightExpression,
            CounterexampleBudgetEvidence budget,
            CounterexampleSearchService.Status status,
            List<String> attemptedSources,
            List<String> inferredAssumptions,
            String explanation,
            String resultHash
        ) {
            Map<String, Object> value = new TreeMap<>();
            value.put("attemptedSources", attemptedSources);
            value.put("budget", budget);
            value.put("engineId", engineId);
            value.put("explanation", explanation);
            value.put("geneId", geneId);
            value.put("genomeHash", genomeHash);
            value.put("inferredAssumptions", inferredAssumptions);
            value.put("leftExpression", leftExpression);
            value.put("repositoryRevision", repositoryRevision);
            value.put("resultHash", resultHash);
            value.put("rightExpression", rightExpression);
            value.put("schema", schema);
            value.put("sourcePattern", sourcePattern);
            value.put("status", status);
            value.put("targetPattern", targetPattern);
            return value;
        }
    }

    /** Complete deterministic budget retained by counterexample evidence. */
    public record CounterexampleBudgetEvidence(
        int numericRandomSamples,
        boolean includeEdgeCases,
        boolean includeMatrixAssignments,
        long randomSeed,
        boolean includeComplexAssignments,
        boolean includeRationalAssignments,
        int maxMatrixDimension,
        long timeoutMillis
    ) {
        public CounterexampleBudgetEvidence {
            toBudget();
        }

        static CounterexampleBudgetEvidence fromBudget(
            CounterexampleSearchService.CounterexampleBudget budget
        ) {
            return new CounterexampleBudgetEvidence(
                budget.numericRandomSamples(),
                budget.includeEdgeCases(),
                budget.includeMatrixAssignments(),
                budget.randomSeed(),
                budget.includeComplexAssignments(),
                budget.includeRationalAssignments(),
                budget.maxMatrixDimension(),
                budget.timeoutMillis());
        }

        CounterexampleSearchService.CounterexampleBudget toBudget() {
            return new CounterexampleSearchService.CounterexampleBudget(
                numericRandomSamples, includeEdgeCases,
                includeMatrixAssignments, randomSeed,
                includeComplexAssignments, includeRationalAssignments,
                maxMatrixDimension, timeoutMillis);
        }
    }

    /** Narrow pattern-rule authorization receipt; never a program receipt. */
    public record AuthorizationReceipt(
        String schema,
        String authorizerId,
        String genomeHash,
        String geneId,
        String repositoryRevision,
        Instant authorizedAt,
        Instant validUntil,
        String evidenceBundleHash,
        String semanticValidationHash,
        String counterexampleSearchHash,
        String holdoutEvaluationHash,
        String leakageAuditHash,
        String promotionReceiptHash,
        String promotedRuleId,
        String promotedRuleHash,
        String applicabilitySchemaHash,
        String contentHash
    ) {
        public AuthorizationReceipt {
            if (!AUTHORIZATION_RECEIPT_SCHEMA.equals(schema)
                    || !AUTHORIZER_ID.equals(authorizerId)) {
                throw new IllegalArgumentException(
                    "learned pattern authorization receipt identity is invalid");
            }
            requireHash(genomeHash, "genomeHash");
            requireText(geneId, "geneId");
            requireRevision(repositoryRevision, "repositoryRevision");
            authorizedAt = Objects.requireNonNull(authorizedAt, "authorizedAt");
            validUntil = Objects.requireNonNull(validUntil, "validUntil");
            if (!authorizedAt.isBefore(validUntil)) {
                throw new IllegalArgumentException(
                    "authorization must expire after authorizedAt");
            }
            for (String hash : List.of(
                    evidenceBundleHash, semanticValidationHash,
                    counterexampleSearchHash, holdoutEvaluationHash,
                    leakageAuditHash, promotionReceiptHash,
                    promotedRuleHash, applicabilitySchemaHash, contentHash)) {
                requireHash(hash, "authorization hash");
            }
            requireText(promotedRuleId, "promotedRuleId");
            if (!hashMaterial(payload(
                    schema, authorizerId, genomeHash, geneId,
                    repositoryRevision, authorizedAt, validUntil,
                    evidenceBundleHash, semanticValidationHash,
                    counterexampleSearchHash, holdoutEvaluationHash,
                    leakageAuditHash, promotionReceiptHash, promotedRuleId,
                    promotedRuleHash, applicabilitySchemaHash)).equals(
                        contentHash)) {
                throw new IllegalArgumentException(
                    "authorization receipt contentHash mismatch");
            }
        }

        private static AuthorizationReceipt create(
            EvolutionGenome genome,
            String geneId,
            String repositoryRevision,
            Instant authorizedAt,
            EvidenceBundle bundle,
            EvolutionSplitManifest split,
            EvolutionValidationSelection validation,
            EvolutionFinalTestEvaluation holdout,
            CounterexampleEvidence counterexample,
            LearnedPatternRulePromoter.Promotion promotion
        ) {
            String promotedRuleHash =
                RuleInventoryFingerprint.ruleContentHash(promotion.rule());
            String applicabilityHash = promotion.applicabilitySchema().contentHash();
            String contentHash = hashMaterial(payload(
                AUTHORIZATION_RECEIPT_SCHEMA, AUTHORIZER_ID,
                genome.contentHash(), geneId, repositoryRevision,
                authorizedAt, bundle.expiresAt(), bundle.contentHash(),
                validation.contentHash(), counterexample.contentHash(),
                holdout.contentHash(), split.contentHash(),
                promotion.receipt().contentHash(), promotion.rule().id(),
                promotedRuleHash, applicabilityHash));
            return new AuthorizationReceipt(
                AUTHORIZATION_RECEIPT_SCHEMA, AUTHORIZER_ID,
                genome.contentHash(), geneId, repositoryRevision,
                authorizedAt, bundle.expiresAt(), bundle.contentHash(),
                validation.contentHash(), counterexample.contentHash(),
                holdout.contentHash(), split.contentHash(),
                promotion.receipt().contentHash(), promotion.rule().id(),
                promotedRuleHash, applicabilityHash, contentHash);
        }

        public void requireUsableAt(
            Instant asOf,
            String expectedRepositoryRevision,
            String expectedPromotedRuleHash
        ) {
            Objects.requireNonNull(asOf, "asOf");
            if (asOf.isBefore(authorizedAt) || !asOf.isBefore(validUntil)) {
                throw new IllegalArgumentException(
                    "learned rule authorization is not valid at " + asOf);
            }
            if (!repositoryRevision.equals(expectedRepositoryRevision)
                    || !promotedRuleHash.equals(expectedPromotedRuleHash)) {
                throw new IllegalArgumentException(
                    "learned rule authorization identity mismatch");
            }
        }

        public String toCanonicalJson() {
            return writeJson(this);
        }

        private static Map<String, Object> payload(
            String schema,
            String authorizerId,
            String genomeHash,
            String geneId,
            String repositoryRevision,
            Instant authorizedAt,
            Instant validUntil,
            String evidenceBundleHash,
            String semanticValidationHash,
            String counterexampleSearchHash,
            String holdoutEvaluationHash,
            String leakageAuditHash,
            String promotionReceiptHash,
            String promotedRuleId,
            String promotedRuleHash,
            String applicabilitySchemaHash
        ) {
            Map<String, Object> value = new TreeMap<>();
            value.put("applicabilitySchemaHash", applicabilitySchemaHash);
            value.put("authorizedAt", authorizedAt);
            value.put("authorizerId", authorizerId);
            value.put("counterexampleSearchHash", counterexampleSearchHash);
            value.put("evidenceBundleHash", evidenceBundleHash);
            value.put("geneId", geneId);
            value.put("genomeHash", genomeHash);
            value.put("holdoutEvaluationHash", holdoutEvaluationHash);
            value.put("leakageAuditHash", leakageAuditHash);
            value.put("promotedRuleHash", promotedRuleHash);
            value.put("promotedRuleId", promotedRuleId);
            value.put("promotionReceiptHash", promotionReceiptHash);
            value.put("repositoryRevision", repositoryRevision);
            value.put("schema", schema);
            value.put("semanticValidationHash", semanticValidationHash);
            value.put("validUntil", validUntil);
            return value;
        }
    }

    public record Authorization(
        LearnedPatternRulePromoter.Promotion promotion,
        EvidenceBundle evidenceBundle,
        EvolutionSplitManifest splitManifest,
        EvolutionValidationSelection validationSelection,
        EvolutionFinalTestEvaluation finalTestEvaluation,
        CounterexampleEvidence counterexampleEvidence,
        AuthorizationReceipt receipt
    ) {
        public Authorization {
            promotion = Objects.requireNonNull(promotion, "promotion");
            evidenceBundle = Objects.requireNonNull(
                evidenceBundle, "evidenceBundle");
            splitManifest = Objects.requireNonNull(splitManifest, "splitManifest");
            validationSelection = Objects.requireNonNull(
                validationSelection, "validationSelection");
            finalTestEvaluation = Objects.requireNonNull(
                finalTestEvaluation, "finalTestEvaluation");
            counterexampleEvidence = Objects.requireNonNull(
                counterexampleEvidence, "counterexampleEvidence");
            receipt = Objects.requireNonNull(receipt, "receipt");
            if (!receipt.evidenceBundleHash().equals(evidenceBundle.contentHash())
                    || !receipt.semanticValidationHash().equals(
                        validationSelection.contentHash())
                    || !receipt.counterexampleSearchHash().equals(
                        counterexampleEvidence.contentHash())
                    || !receipt.holdoutEvaluationHash().equals(
                        finalTestEvaluation.contentHash())
                    || !receipt.leakageAuditHash().equals(
                        splitManifest.contentHash())
                    || !receipt.promotionReceiptHash().equals(
                        promotion.receipt().contentHash())
                    || !receipt.promotedRuleId().equals(promotion.rule().id())
                    || !receipt.promotedRuleHash().equals(
                        RuleInventoryFingerprint.ruleContentHash(promotion.rule()))
                    || !receipt.applicabilitySchemaHash().equals(
                        promotion.applicabilitySchema().contentHash())) {
                throw new IllegalArgumentException(
                    "learned pattern authorization products are inconsistent");
            }
        }
    }
}
