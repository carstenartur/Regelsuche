package de.regelsuche.evolution;

import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.transform.RewriteRule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
                verified.leaves().authorizationHashes());
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
        receipt.requireUsableAt(asOf, repositoryRevision, candidate);
        VerifiedProgram verified = verifyProgram(
            candidate,
            leafAuthorizations,
            replayEvidence,
            repositoryRevision,
            asOf);
        if (!receipt.replayEvidenceHash().equals(replayEvidence.contentHash())
                || !receipt.leafAuthorizationHashes().equals(
                    verified.leaves().authorizationHashes())
                || !receipt.validUntil().equals(verified.leaves().validUntil())) {
            throw new IllegalArgumentException(
                "stored rewrite-program authorization differs from replayed authority");
        }
        List<String> workRevisions = replayEvidence.cases().stream()
            .map(LearnedRewriteProgramReplayEvidence.ReplayCase::workRevision)
            .distinct()
            .sorted()
            .toList();
        if (!receipt.workRevisions().equals(workRevisions)) {
            throw new IllegalArgumentException(
                "stored rewrite-program authorization uses different work semantics");
        }
        return new Authorization(
            candidate,
            verified.compiled(),
            replayEvidence,
            verified.leaves().authorizations(),
            receipt);
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
            if (rules.put(geneId, checked.promotion().rule()) != null
                    || receiptHashes.put(geneId, receipt.contentHash()) != null) {
                throw new IllegalArgumentException(
                    "duplicate leaf authorization for " + geneId);
            }
            retained.add(checked);
            validUntil = validUntil == null
                || receipt.validUntil().isBefore(validUntil)
                    ? receipt.validUntil()
                    : validUntil;
        }
        if (!rules.keySet().equals(referenced)) {
            throw new IllegalArgumentException(
                "leaf authorization set differs from referenced program genes");
        }
        retained.sort(Comparator.comparing(value -> value.receipt().geneId()));
        return new VerifiedLeaves(
            Map.copyOf(rules),
            Map.copyOf(receiptHashes),
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
                    || !compiledProgram.genomeHash().equals(
                        candidate.genome().contentHash())
                    || !compiledProgram.planHash().equals(
                        candidate.plan().contentHash())) {
                throw new IllegalArgumentException(
                    "learned rewrite-program authorization products are inconsistent");
            }
            Map<String, String> leaves = new TreeMap<>();
            for (LearnedPatternRuleAuthorizationService.Authorization leaf
                    : leafAuthorizations) {
                if (leaves.put(
                        leaf.receipt().geneId(),
                        leaf.receipt().contentHash()) != null) {
                    throw new IllegalArgumentException(
                        "duplicate authorized leaf in program result");
                }
            }
            if (!receipt.leafAuthorizationHashes().equals(leaves)
                    || !Set.copyOf(compiledProgram.referencedGeneIds())
                        .equals(leaves.keySet())) {
                throw new IllegalArgumentException(
                    "compiled program, receipt and leaf authorities differ");
            }
        }
    }
}