package de.regelsuche.evolution;

import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.MeasuredTransformationEngine;
import de.regelsuche.transform.MeasuredTransformationEngines;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Fail-closed production authorization boundary for learned rewrite programs.
 *
 * <p>A program is not promoted as one synthetic pattern rule. Its canonical
 * {@link EvolutionRewriteProgramPlan} remains the executable topology, while
 * every referenced source gene must independently arrive as a proved
 * {@link LearnedPatternRuleAuthorizationService.Authorization}. The compiler
 * then substitutes those promoted rules into the topology and deterministic
 * replay binds sequence/choice/repeat/guard/prioritize/prune behaviour together
 * with primitive and theory work accounting.</p>
 *
 * <p>The additive selected-program methods consume opaque combined-study leaf
 * authorities directly and retain the full selected search configuration in a
 * bounded internal executor. They do not require another genome-only study.</p>
 *
 * <p>This contract establishes executable identity and deterministic replay. It
 * does not by itself claim that the learned strategy improves search quality or
 * that an observed primitive path is globally shortest.</p>
 */
public final class LearnedRewriteProgramAuthorizationService {
    public static final String AUTHORIZATION_RECEIPT_SCHEMA =
        LearnedRewriteProgramAuthorizationReceipt.SCHEMA;
    public static final String REPLAY_EVIDENCE_SCHEMA =
        LearnedRewriteProgramReplayEvidence.SCHEMA;
    public static final String AUTHORIZER_ID =
        LearnedRewriteProgramAuthorizationReceipt.AUTHORIZER_ID;
    public static final String APPLICABILITY_SEMANTICS =
        LearnedRewriteProgramAuthorizationReceipt.APPLICABILITY_SEMANTICS;

    private final EvolutionRewriteProgramCompiler compiler;

    public LearnedRewriteProgramAuthorizationService() {
        this(new EvolutionRewriteProgramCompiler());
    }

    LearnedRewriteProgramAuthorizationService(
        EvolutionRewriteProgramCompiler compiler
    ) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
    }

    /**
     * Executes the canonical program with independently authorized leaf rules
     * and captures an exact replay artifact for a fixed case set.
     */
    public LearnedRewriteProgramReplayEvidence evaluateReplay(
        EvolutionRewriteProgramCandidate candidate,
        List<LearnedPatternRuleAuthorizationService.Authorization>
            leafAuthorizations,
        List<LearnedRewriteProgramReplayEvidence.ReplayInput> inputs,
        String repositoryRevision,
        Instant asOf
    ) {
        VerifiedLeaves leaves = verifyLeaves(
            candidate,
            leafAuthorizations,
            repositoryRevision,
            asOf);
        EvolutionRewriteProgramCompiler.CompiledRewriteProgram compiled =
            compiler.compileAuthorized(
                candidate.genome(),
                candidate.plan(),
                leaves.rulesByGeneId());
        return LearnedRewriteProgramReplayEvidence.capture(
            candidate,
            compiled,
            inputs);
    }

    /**
     * Issues one program authorization after independently replaying the
     * supplied evidence with the authorized leaf rules.
     *
     * <p>The program expiry is capped by the earliest leaf-rule authorization;
     * a composite program can never outlive one of the rules it depends on.</p>
     */
    public Authorization authorize(
        EvolutionRewriteProgramCandidate candidate,
        List<LearnedPatternRuleAuthorizationService.Authorization>
            leafAuthorizations,
        LearnedRewriteProgramReplayEvidence replayEvidence,
        String repositoryRevision,
        Instant asOf
    ) {
        VerifiedProgram verified = verifyProgram(
            candidate,
            leafAuthorizations,
            replayEvidence,
            repositoryRevision,
            asOf);
        Instant validUntil = verified.leaves().validUntil();
        LearnedRewriteProgramAuthorizationReceipt receipt =
            LearnedRewriteProgramAuthorizationReceipt.create(
                candidate,
                repositoryRevision,
                asOf,
                validUntil,
                replayEvidence,
                verified.leaves().authorizationHashes(),
                verified.leaves().applicabilitySchemaHashes());
        return new Authorization(
            candidate,
            verified.compiled(),
            replayEvidence,
            verified.leaves().authorizations(),
            receipt);
    }

    /**
     * Replays a stored authorization receipt without trusting its PASS state.
     * Every leaf validity/identity check and every program replay is repeated.
     *
     * <p>The reconstruction is performed at the receipt's retained
     * {@code authorizedAt} instant, matching the learned-pattern authorization
     * contract. This prevents a self-consistent receipt from claiming issuance
     * before one of its leaf authorities became valid. The entire reconstructed
     * receipt must match before current-time usability is checked.</p>
     */
    public Authorization replayStoredAuthorization(
        EvolutionRewriteProgramCandidate candidate,
        List<LearnedPatternRuleAuthorizationService.Authorization>
            leafAuthorizations,
        LearnedRewriteProgramReplayEvidence replayEvidence,
        LearnedRewriteProgramAuthorizationReceipt receipt,
        String repositoryRevision,
        Instant asOf
    ) {
        Objects.requireNonNull(receipt, "receipt");
        receipt.requireUsableAt(
            receipt.authorizedAt(),
            repositoryRevision,
            candidate);
        Authorization replayed = authorize(
            candidate,
            leafAuthorizations,
            replayEvidence,
            repositoryRevision,
            receipt.authorizedAt());
        if (!receipt.equals(replayed.receipt())) {
            throw new IllegalArgumentException(
                "stored rewrite-program authorization differs from evidence replay");
        }
        receipt.requireUsableAt(asOf, repositoryRevision, candidate);
        return replayed;
    }

    /** Captures real public-input replay using leaves verified from the same one-shot combined study. */
    public LearnedSelectedProgramReplayEvidence evaluateSelectedReplay(EvolutionRewriteProgramFinalTestPlan plan,
        List<LearnedPatternRuleAuthorizationService.SelectedLeafAuthorization> leafAuthorizations,
        List<LearnedSelectedProgramReplayEvidence.Input> inputs, String repositoryRevision, Instant asOf) {
        var selected = verifySelected(plan, leafAuthorizations, repositoryRevision, asOf);
        return LearnedSelectedProgramReplayEvidence.capture(plan, selected.bundle(), selected.leafHashes(),
            selected.compiled(), selected.engine(), inputs);
    }

    /** Internal execution authority only; scientific, publication and release claims remain separate. */
    public SelectedAuthorization authorizeSelected(EvolutionRewriteProgramFinalTestPlan plan,
        List<LearnedPatternRuleAuthorizationService.SelectedLeafAuthorization> leafAuthorizations,
        LearnedSelectedProgramReplayEvidence evidence, String repositoryRevision, Clock clock) {
        Objects.requireNonNull(clock, "clock");
        Instant startedAt = clock.instant();
        var selected = verifySelected(plan, leafAuthorizations, repositoryRevision, startedAt);
        Objects.requireNonNull(evidence, "evidence");
        var replay = LearnedSelectedProgramReplayEvidence.capture(plan, selected.bundle(), selected.leafHashes(),
            selected.compiled(), selected.engine(), evidence.inputs());
        if (!replay.equals(evidence)) {
            throw new IllegalArgumentException("selected-program replay differs from actual authorized execution");
        }
        if (replay.cases().stream().anyMatch(item -> !item.programReplay().complete() || !item.search().complete()
                || (item.search().reached() && item.search().correctness()
                    != EvolutionRewriteProgramTrainFitnessEvidence.PathCorrectness.CONFIRMED)
                || item.programReplay().candidates().stream().anyMatch(candidate ->
                    !item.input().assumptions().containsAll(candidate.assumptions())))) {
            throw new IllegalArgumentException("selected-program replay retains incomplete or unqualified concrete outcomes");
        }
        Instant authorizedAt = clock.instant();
        if (authorizedAt.isBefore(startedAt)) { throw new IllegalArgumentException("authorization clock moved backwards during replay"); }
        for (var leaf : selected.leaves()) { leaf.requireUsableAt(plan, repositoryRevision, authorizedAt); }
        return new SelectedAuthorization(plan, selected, replay, repositoryRevision, clock, authorizedAt);
    }

    private VerifiedSelected verifySelected(EvolutionRewriteProgramFinalTestPlan plan,
        List<LearnedPatternRuleAuthorizationService.SelectedLeafAuthorization> authorizations, String revision, Instant asOf) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(authorizations, "authorizations");
        LearnedPatternAuthorizationJson.requireRevision(revision, "repositoryRevision");
        var genome = plan.selectedConfiguration().candidate().genome();
        Set<String> executing = new TreeSet<>(genome.rewrites().stream().map(EvolutionGenome.RewriteGene::geneId).toList());
        if (authorizations.size() != executing.size()) {
            throw new IllegalArgumentException("selected runtime requires exactly one authority for every executing genome gene");
        }
        Map<String, RewriteRule> allRules = new TreeMap<>();
        Map<String, String> hashes = new TreeMap<>();
        LearnedSelectedProgramAuthorizationBundle bundle = null;
        for (var leaf : authorizations) {
            Objects.requireNonNull(leaf, "leaf").requireUsableAt(plan, revision, asOf);
            if (!executing.contains(leaf.geneId()) || allRules.put(leaf.geneId(), leaf.promotion().rule()) != null) {
                throw new IllegalArgumentException("duplicate or foreign selected leaf authority");
            }
            if (bundle != null && !bundle.equals(leaf.bundle())) {
                throw new IllegalArgumentException("selected leaves have different full evidence or validity bindings");
            }
            bundle = leaf.bundle();
            hashes.put(leaf.geneId(), leaf.contentHash());
        }
        if (!allRules.keySet().equals(executing)) { throw new IllegalArgumentException("selected leaf inventory differs from genome"); }
        var candidate = plan.selectedConfiguration().candidate();
        Map<String, RewriteRule> referenced = new TreeMap<>();
        for (String id : candidate.plan().referencedGeneIds()) { referenced.put(id, allRules.get(id)); }
        var compiled = compiler.compileAuthorized(genome, candidate.plan(), referenced);
        var budget = plan.selectedConfiguration().effectiveBudget();
        var ordinary = MeasuredTransformationEngines.counting(new AstRewriteTransformationEngine(
            AstRewriteTransformationEngine.defaultRules(), genome.budget().maxAstGrowthPerStep(), budget.maxCandidatesPerState()));
        var flat = MeasuredTransformationEngines.counting(new AstRewriteTransformationEngine(
            List.copyOf(allRules.values()), genome.budget().maxAstGrowthPerStep(), budget.maxCandidatesPerState()));
        return new VerifiedSelected(Objects.requireNonNull(bundle, "bundle"), Map.copyOf(hashes), compiled,
            MeasuredTransformationEngines.union(ordinary, flat, compiled.engine()), List.copyOf(authorizations));
    }

    private record VerifiedSelected(LearnedSelectedProgramAuthorizationBundle bundle, Map<String, String> leafHashes,
        EvolutionRewriteProgramCompiler.CompiledRewriteProgram compiled, MeasuredTransformationEngine engine,
        List<LearnedPatternRuleAuthorizationService.SelectedLeafAuthorization> leaves) { }

    /** Opaque, bounded executor. It never exposes its compiled program, promoted rules or underlying engine. */
    public static final class SelectedAuthorization {
        private final EvolutionRewriteProgramFinalTestPlan plan;
        private final VerifiedSelected verified;
        private final LearnedSelectedProgramReplayEvidence replay;
        private final String repositoryRevision;
        private final Clock clock;
        private final Instant authorizedAt;
        private final String bindingHash;

        private SelectedAuthorization(EvolutionRewriteProgramFinalTestPlan plan, VerifiedSelected verified,
            LearnedSelectedProgramReplayEvidence replay, String revision, Clock clock, Instant authorizedAt) {
            this.plan = plan;
            this.verified = verified;
            this.replay = replay;
            this.repositoryRevision = revision;
            this.clock = clock;
            this.authorizedAt = authorizedAt;
            this.bindingHash = EvolutionProgramValidationJson.hash(EvolutionProgramValidationJson.material(
                "regelsuche.learned-selected-program-internal-authorization/v1", "finalPlanHash", plan.contentHash(),
                "configurationHash", plan.selectedConfiguration().contentHash(), "bundleHash", verified.bundle().contentHash(),
                "leafAuthorizationHashes", verified.leafHashes(), "replayHash", replay.contentHash(), "scope", scope(),
                "authorizedAt", authorizedAt, "claimStatuses", claimStatuses()));
        }

        public EvolutionRewriteProgramValidationEvidence.Measurement execute(LearnedSelectedProgramReplayEvidence.Input input,
            String expectedRepositoryRevision) {
            Objects.requireNonNull(input, "input");
            if (!repositoryRevision.equals(expectedRepositoryRevision)) {
                throw new IllegalArgumentException("selected runtime repository revision has changed");
            }
            Instant asOf = clock.instant();
            if (asOf.isBefore(authorizedAt)) { throw new IllegalArgumentException("selected capability predates actual program authorization"); }
            for (var leaf : verified.leaves()) { leaf.requireUsableAt(plan, expectedRepositoryRevision, asOf); }
            return new NativeEvolutionRewriteProgramValidationEvaluator().evaluateSide(verified.engine(), input.inputExpression(),
                input.targetExpression(), input.assumptions(), plan.selectedConfiguration().effectiveBudget());
        }

        public String bindingHash() { return bindingHash; }
        public String scope() { return "INTERNAL_EXECUTION_ONLY"; }
        public LearnedSelectedProgramReplayEvidence replayEvidence() { return replay; }
        public Map<String, String> claimStatuses() {
            return Map.of("projectNovelty", "NOT_EVALUATED", "externalNovelty", "NOT_EVALUATED", "publicEvidence", "NOT_EVALUATED",
                "publicPromotion", "NOT_EVALUATED", "release", "NOT_EVALUATED");
        }
    }

    private VerifiedProgram verifyProgram(
        EvolutionRewriteProgramCandidate candidate,
        List<LearnedPatternRuleAuthorizationService.Authorization>
            leafAuthorizations,
        LearnedRewriteProgramReplayEvidence replayEvidence,
        String repositoryRevision,
        Instant asOf
    ) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(replayEvidence, "replayEvidence");
        replayEvidence.requireCandidate(candidate);
        VerifiedLeaves leaves = verifyLeaves(
            candidate,
            leafAuthorizations,
            repositoryRevision,
            asOf);
        EvolutionRewriteProgramCompiler.CompiledRewriteProgram compiled =
            compiler.compileAuthorized(
                candidate.genome(),
                candidate.plan(),
                leaves.rulesByGeneId());
        LearnedRewriteProgramReplayEvidence replay =
            LearnedRewriteProgramReplayEvidence.capture(
                candidate,
                compiled,
                replayEvidence.replayInputs());
        if (!replay.contentHash().equals(replayEvidence.contentHash())
                || !replay.toCanonicalJson().equals(
                    replayEvidence.toCanonicalJson())) {
            throw new IllegalArgumentException(
                "rewrite-program evidence differs from deterministic replay");
        }
        return new VerifiedProgram(compiled, leaves);
    }

    private static VerifiedLeaves verifyLeaves(
        EvolutionRewriteProgramCandidate candidate,
        List<LearnedPatternRuleAuthorizationService.Authorization>
            leafAuthorizations,
        String repositoryRevision,
        Instant asOf
    ) {
        Objects.requireNonNull(candidate, "candidate");
        LearnedPatternAuthorizationJson.requireRevision(
            repositoryRevision, "repositoryRevision");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(leafAuthorizations, "leafAuthorizations");

        Set<String> referenced = new TreeSet<>(
            candidate.plan().referencedGeneIds());
        if (referenced.isEmpty()) {
            throw new IllegalArgumentException(
                "learned rewrite program must reference at least one gene");
        }
        if (leafAuthorizations.size() != referenced.size()) {
            throw new IllegalArgumentException(
                "one leaf authorization is required for every referenced gene");
        }

        TreeMap<String, RewriteRule> rules = new TreeMap<>();
        TreeMap<String, String> receiptHashes = new TreeMap<>();
        TreeMap<String, String> applicabilityHashes = new TreeMap<>();
        ArrayList<LearnedPatternRuleAuthorizationService.Authorization>
            retained = new ArrayList<>();
        Instant validUntil = null;
        for (LearnedPatternRuleAuthorizationService.Authorization authorization
                : leafAuthorizations) {
            LearnedPatternRuleAuthorizationService.Authorization checked =
                Objects.requireNonNull(authorization, "leaf authorization");
            LearnedPatternAuthorizationReceipt receipt = checked.receipt();
            String geneId = receipt.geneId();
            if (!referenced.contains(geneId)) {
                throw new IllegalArgumentException(
                    "leaf authorization is not referenced by program: " + geneId);
            }
            if (!receipt.genomeHash().equals(candidate.genome().contentHash())) {
                throw new IllegalArgumentException(
                    "leaf authorization belongs to a different genome: " + geneId);
            }
            String promotedRuleHash =
                RuleInventoryFingerprint.ruleContentHash(
                    checked.promotion().rule());
            receipt.requireUsableAt(
                asOf,
                repositoryRevision,
                promotedRuleHash);
            if (!checked.promotion().rule()
                    .isEquivalencePreservingByConstruction()) {
                throw new IllegalArgumentException(
                    "learned program leaf is not equivalence preserving: " + geneId);
            }
            String applicabilityHash =
                checked.promotion().applicabilitySchema().contentHash();
            if (!receipt.applicabilitySchemaHash().equals(applicabilityHash)) {
                throw new IllegalArgumentException(
                    "leaf applicability schema differs from its authorization receipt: "
                        + geneId);
            }
            if (rules.put(geneId, checked.promotion().rule()) != null
                    || receiptHashes.put(geneId, receipt.contentHash()) != null
                    || applicabilityHashes.put(geneId, applicabilityHash) != null) {
                throw new IllegalArgumentException(
                    "duplicate leaf authorization for " + geneId);
            }
            retained.add(checked);
            validUntil = validUntil == null
                || receipt.validUntil().isBefore(validUntil)
                    ? receipt.validUntil()
                    : validUntil;
        }
        if (!rules.keySet().equals(referenced)
                || !applicabilityHashes.keySet().equals(referenced)) {
            throw new IllegalArgumentException(
                "leaf authorization set differs from referenced program genes");
        }
        retained.sort(Comparator.comparing(value -> value.receipt().geneId()));
        return new VerifiedLeaves(
            Map.copyOf(rules),
            Map.copyOf(receiptHashes),
            Map.copyOf(applicabilityHashes),
            List.copyOf(retained),
            Objects.requireNonNull(validUntil, "validUntil"));
    }

    private record VerifiedProgram(
        EvolutionRewriteProgramCompiler.CompiledRewriteProgram compiled,
        VerifiedLeaves leaves
    ) {
    }

    private record VerifiedLeaves(
        Map<String, RewriteRule> rulesByGeneId,
        Map<String, String> authorizationHashes,
        Map<String, String> applicabilitySchemaHashes,
        List<LearnedPatternRuleAuthorizationService.Authorization> authorizations,
        Instant validUntil
    ) {
    }

    /** Fully replayed executable authorization. */
    public record Authorization(
        EvolutionRewriteProgramCandidate candidate,
        EvolutionRewriteProgramCompiler.CompiledRewriteProgram compiledProgram,
        LearnedRewriteProgramReplayEvidence replayEvidence,
        List<LearnedPatternRuleAuthorizationService.Authorization>
            leafAuthorizations,
        LearnedRewriteProgramAuthorizationReceipt receipt
    ) {
        public Authorization {
            candidate = Objects.requireNonNull(candidate, "candidate");
            compiledProgram = Objects.requireNonNull(
                compiledProgram, "compiledProgram");
            replayEvidence = Objects.requireNonNull(
                replayEvidence, "replayEvidence");
            leafAuthorizations = List.copyOf(Objects.requireNonNull(
                leafAuthorizations, "leafAuthorizations"));
            receipt = Objects.requireNonNull(receipt, "receipt");
            replayEvidence.requireCandidate(candidate);
            if (!receipt.candidateHash().equals(candidate.contentHash())
                    || !receipt.replayEvidenceHash().equals(
                        replayEvidence.contentHash())
                    || !APPLICABILITY_SEMANTICS.equals(
                        receipt.applicabilitySemantics())
                    || !compiledProgram.genomeHash().equals(
                        candidate.genome().contentHash())
                    || !compiledProgram.planHash().equals(
                        candidate.plan().contentHash())) {
                throw new IllegalArgumentException(
                    "learned rewrite-program authorization products are inconsistent");
            }
            Map<String, String> leaves = new TreeMap<>();
            Map<String, String> applicability = new TreeMap<>();
            for (LearnedPatternRuleAuthorizationService.Authorization leaf
                    : leafAuthorizations) {
                String geneId = leaf.receipt().geneId();
                if (leaves.put(
                        geneId,
                        leaf.receipt().contentHash()) != null
                        || applicability.put(
                            geneId,
                            leaf.promotion().applicabilitySchema().contentHash())
                            != null) {
                    throw new IllegalArgumentException(
                        "duplicate authorized leaf in program result");
                }
            }
            if (!receipt.leafAuthorizationHashes().equals(leaves)
                    || !receipt.leafApplicabilitySchemaHashes().equals(
                        applicability)
                    || !Set.copyOf(compiledProgram.referencedGeneIds())
                        .equals(leaves.keySet())) {
                throw new IllegalArgumentException(
                    "compiled program, receipt and leaf authorities differ");
            }
        }

        /**
         * Program-level applicability is the canonical control-flow semantics,
         * not a flattened synthetic pattern: at least one candidate survives
         * the authorized program for the supplied expression.
         */
        public boolean isApplicable(String expression) {
            LearnedPatternAuthorizationJson.requireText(expression, "expression");
            return !compiledProgram.engine().execute(expression)
                .candidates().isEmpty();
        }
    }
}
