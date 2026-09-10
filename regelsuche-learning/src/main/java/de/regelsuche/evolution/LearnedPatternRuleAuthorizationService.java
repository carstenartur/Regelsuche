package de.regelsuche.evolution;

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
 * Fail-closed production qualification boundary for an exactly proved learned
 * pattern rule.
 *
 * <p>The service orchestrates existing native evidence models rather than
 * defining a parallel validation system: split/leakage, VALIDATION selection
 * and FINAL TEST are reconstructed with their existing codecs. Counterexample
 * search is replayed deterministically. Only after those checks pass does the
 * existing exact {@link LearnedPatternRulePromoter} run.</p>
 *
 * <p>This class deliberately does not authorize {@code RewriteProgram}; program
 * semantics require a separate sequence/choice/repeat/guard/pruning replay
 * contract under issue #745.</p>
 */
public final class LearnedPatternRuleAuthorizationService {
    public static final String BUNDLE_SCHEMA =
        LearnedPatternAuthorizationBundle.SCHEMA;
    public static final String COUNTEREXAMPLE_SCHEMA =
        LearnedPatternCounterexampleEvidence.SCHEMA;
    public static final String COUNTEREXAMPLE_ENGINE_ID =
        LearnedPatternCounterexampleEvidence.ENGINE_ID;
    public static final String AUTHORIZATION_RECEIPT_SCHEMA =
        LearnedPatternAuthorizationReceipt.SCHEMA;
    public static final String AUTHORIZER_ID =
        LearnedPatternAuthorizationReceipt.AUTHORIZER_ID;

    /**
     * The exact promoter proves a commutative polynomial identity. This
     * supplemental challenge therefore uses deterministic scalar, rational and
     * complex samples, not noncommutative matrix assignments.
     */
    private static final CounterexampleSearchService.CounterexampleBudget
        AUTHORIZATION_COUNTEREXAMPLE_BUDGET =
            new CounterexampleSearchService.CounterexampleBudget(
                64, true, false, 745L, true, true, 0, 0L);

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

    public static CounterexampleSearchService.CounterexampleBudget
            authorizationCounterexampleBudget() {
        return AUTHORIZATION_COUNTEREXAMPLE_BUDGET;
    }

    /** Produces the gene-specific artifact that {@link #authorize} later replays. */
    public LearnedPatternCounterexampleEvidence evaluateCounterexamples(
        EvolutionGenome genome,
        String geneId,
        String repositoryRevision
    ) {
        Objects.requireNonNull(genome, "genome");
        EvolutionGenome.RewriteGene gene = gene(genome, geneId);
        LearnedPatternAuthorizationJson.requireRevision(
            repositoryRevision, "repositoryRevision");
        PatternSubject subject = patternSubject(gene);
        CounterexampleSearchService.CounterexampleSearchResult result =
            counterexamples.search(
                new CounterexampleSearchService.HypothesisInput(
                    geneId,
                    subject.leftExpression(),
                    subject.rightExpression(),
                    List.of()),
                AUTHORIZATION_COUNTEREXAMPLE_BUDGET);
        return LearnedPatternCounterexampleEvidence.create(
            genome,
            gene,
            repositoryRevision,
            subject.sourcePattern(),
            subject.targetPattern(),
            subject.leftExpression(),
            subject.rightExpression(),
            AUTHORIZATION_COUNTEREXAMPLE_BUDGET,
            result);
    }

    /**
     * Reconstructs and cross-verifies the complete qualification bundle and
     * creates a new authorization receipt at the supplied deterministic time.
     *
     * @param asOf explicit validity instant; no implicit wall clock participates
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
        LearnedPatternAuthorizationJson.requireRevision(
            repositoryRevision, "repositoryRevision");
        Objects.requireNonNull(evidenceFiles, "evidenceFiles");
        Objects.requireNonNull(asOf, "asOf");

        LearnedPatternAuthorizationBundle bundle =
            LearnedPatternAuthorizationBundle.fromCanonicalJson(
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
        LearnedPatternCounterexampleEvidence counterexample =
            LearnedPatternCounterexampleEvidence.fromCanonicalJson(
                readRegularFile(
                    evidenceFiles.counterexampleEvidence(),
                    "counterexample evidence"));

        requireHash(
            bundle.splitManifestHash(), split.contentHash(), "split manifest");
        requireHash(
            bundle.validationSelectionHash(),
            validation.contentHash(),
            "validation selection");
        requireHash(
            bundle.finalTestEvaluationHash(),
            holdout.contentHash(),
            "FINAL TEST evaluation");
        requireHash(
            bundle.counterexampleEvidenceHash(),
            counterexample.contentHash(),
            "counterexample evidence");

        verifySplit(genome, split);
        EvolutionValidationCandidate selected =
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
        String promotedRuleHash =
            RuleInventoryFingerprint.ruleContentHash(promotion.rule());
        LearnedPatternAuthorizationReceipt receipt =
            LearnedPatternAuthorizationReceipt.create(
                genome,
                geneId,
                repositoryRevision,
                asOf,
                bundle,
                split,
                validation,
                holdout,
                counterexample,
                promotion,
                promotedRuleHash);
        return new Authorization(
            promotion,
            bundle,
            split,
            validation,
            selected,
            holdout,
            counterexample,
            receipt);
    }

    /**
     * Loads a retained authorization receipt and replays every authority it
     * references before admitting the learned rule for use.
     *
     * <p>The receipt is not trusted as a capability token by itself. The method
     * reconstructs the same qualification at the original {@code authorizedAt}
     * instant, which reloads split/VALIDATION/FINAL-TEST evidence, reruns the
     * deterministic counterexample search and reruns exact promotion. The
     * retained and reconstructed receipts must be identical. Only then is the
     * retained receipt checked at the current explicit {@code asOf} instant.</p>
     */
    public Authorization verifyAuthorization(
        EvolutionGenome genome,
        String geneId,
        String repositoryRevision,
        EvidenceFiles evidenceFiles,
        Path authorizationReceipt,
        Instant asOf
    ) throws IOException {
        Objects.requireNonNull(genome, "genome");
        LearnedPatternAuthorizationJson.requireText(geneId, "geneId");
        LearnedPatternAuthorizationJson.requireRevision(
            repositoryRevision, "repositoryRevision");
        Objects.requireNonNull(evidenceFiles, "evidenceFiles");
        Objects.requireNonNull(asOf, "asOf");

        LearnedPatternAuthorizationReceipt retained =
            LearnedPatternAuthorizationReceipt.fromCanonicalJson(
                readRegularFile(
                    authorizationReceipt,
                    "learned pattern authorization receipt"));
        if (!retained.genomeHash().equals(genome.contentHash())
                || !retained.geneId().equals(geneId)
                || !retained.repositoryRevision().equals(repositoryRevision)) {
            throw new IllegalArgumentException(
                "retained learned-rule authorization subject/revision mismatch");
        }

        Authorization replayed = authorize(
            genome,
            geneId,
            repositoryRevision,
            evidenceFiles,
            retained.authorizedAt());
        if (!retained.equals(replayed.receipt())) {
            throw new IllegalArgumentException(
                "retained learned-rule authorization differs from evidence replay");
        }
        retained.requireUsableAt(
            asOf,
            repositoryRevision,
            replayed.receipt().promotedRuleHash());
        return replayed;
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

    private static EvolutionValidationCandidate verifyValidation(
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

        Map<String, String> expectedCases = splitCases(split.validationCases());
        Map<String, String> actualCases = new TreeMap<>();
        for (EvolutionValidationCaseEvidence evidence : selected.cases()) {
            actualCases.put(evidence.caseId(), evidence.family());
        }
        if (!expectedCases.equals(actualCases)
                || !expectedCases.keySet().equals(
                    new TreeSet<>(validation.validationCaseIds()))) {
            throw new IllegalArgumentException(
                "VALIDATION case identities/families differ from split manifest");
        }
        return selected;
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

        Map<String, String> expectedCases = splitCases(split.finalTestCases());
        Map<String, String> actualCases = new TreeMap<>();
        for (EvolutionFinalTestCaseEvidence evidence : holdout.cases()) {
            actualCases.put(evidence.caseId(), evidence.family());
        }
        if (!expectedCases.equals(actualCases)
                || !expectedCases.keySet().equals(
                    new TreeSet<>(holdout.finalTestCaseIds()))) {
            throw new IllegalArgumentException(
                "FINAL TEST case identities/families differ from split manifest");
        }
    }

    private void verifyCounterexample(
        EvolutionGenome genome,
        EvolutionGenome.RewriteGene gene,
        String repositoryRevision,
        LearnedPatternCounterexampleEvidence evidence
    ) {
        PatternSubject subject = patternSubject(gene);
        evidence.requireSubject(
            genome.contentHash(),
            gene.geneId(),
            repositoryRevision,
            subject.sourcePattern(),
            subject.targetPattern(),
            subject.leftExpression(),
            subject.rightExpression());
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
        if (!LearnedPatternCounterexampleEvidence.resultHash(replay).equals(
                    evidence.resultHash())
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

    private static Map<String, String> splitCases(
        List<EvolutionSplitManifest.CaseReference> cases
    ) {
        Map<String, String> result = new TreeMap<>();
        for (EvolutionSplitManifest.CaseReference reference : cases) {
            result.put(reference.caseId(), reference.family());
        }
        return Map.copyOf(result);
    }

    private static EvolutionGenome.RewriteGene gene(
        EvolutionGenome genome,
        String geneId
    ) {
        LearnedPatternAuthorizationJson.requireText(geneId, "geneId");
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

    private static void requireHash(
        String expected,
        String actual,
        String name
    ) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(name + " hash mismatch");
        }
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

    /** Fully reconstructed qualification result for one pattern rule. */
    public record Authorization(
        LearnedPatternRulePromoter.Promotion promotion,
        LearnedPatternAuthorizationBundle evidenceBundle,
        EvolutionSplitManifest splitManifest,
        EvolutionValidationSelection validationSelection,
        EvolutionValidationCandidate selectedValidationCandidate,
        EvolutionFinalTestEvaluation finalTestEvaluation,
        LearnedPatternCounterexampleEvidence counterexampleEvidence,
        LearnedPatternAuthorizationReceipt receipt
    ) {
        public Authorization {
            promotion = Objects.requireNonNull(promotion, "promotion");
            evidenceBundle = Objects.requireNonNull(
                evidenceBundle, "evidenceBundle");
            splitManifest = Objects.requireNonNull(splitManifest, "splitManifest");
            validationSelection = Objects.requireNonNull(
                validationSelection, "validationSelection");
            selectedValidationCandidate = Objects.requireNonNull(
                selectedValidationCandidate, "selectedValidationCandidate");
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
