package de.regelsuche.evolution;

import de.regelsuche.knowledge.DerivationType;
import de.regelsuche.knowledge.RuleDescriptor;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.knowledge.RuleStatus;
import de.regelsuche.knowledge.SearchEffect;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import java.util.Objects;

/**
 * Fail-closed promotion boundary from a raw evolutionary rewrite gene to a
 * registration-eligible, exactly proved pattern rule.
 *
 * <p>Raw compiled genome rules deliberately remain non-equivalence-preserving.
 * Promotion creates a new rule identity only after genome preflight, frozen
 * validation/counterexample/holdout/leakage evidence and an exact symbolic
 * identity proof all agree. The first revision accepts only assumption-free
 * polynomial identities; learned programs and conditional rules remain outside
 * this contract.</p>
 */
public final class LearnedPatternRulePromoter {
    public static final String PROMOTER_ID =
        "regelsuche.learned-pattern-rule-promoter/v1";
    public static final String RECEIPT_SCHEMA =
        "regelsuche.learned-pattern-rule-promotion-receipt/v1";

    private final EvolutionGenomeValidator preflight;
    private final ExactPolynomialPatternIdentityVerifier verifier;

    public LearnedPatternRulePromoter() {
        this(
            new EvolutionGenomeValidator(),
            new ExactPolynomialPatternIdentityVerifier());
    }

    public LearnedPatternRulePromoter(
        EvolutionGenomeValidator preflight,
        ExactPolynomialPatternIdentityVerifier verifier
    ) {
        this.preflight = Objects.requireNonNull(preflight, "preflight");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    public Promotion promote(
        EvolutionGenome genome,
        String geneId,
        PromotionEvidence evidence
    ) {
        Objects.requireNonNull(genome, "genome");
        PromotionEvidence checkedEvidence = Objects.requireNonNull(
            evidence, "evidence");
        if (geneId == null || geneId.isBlank()) {
            throw new IllegalArgumentException("geneId must not be blank");
        }

        EvolutionGenomeValidator.ValidationReport report =
            preflight.validate(genome);
        if (!report.accepted()) {
            throw new IllegalArgumentException(
                "genome preflight rejected promotion: "
                    + report.blockerCodes());
        }
        EvolutionGenome.RewriteGene gene = genome.rewrites().stream()
            .filter(value -> value.geneId().equals(geneId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "unknown rewrite gene: " + geneId));
        if (!gene.assumptions().isEmpty()) {
            throw new IllegalArgumentException(
                "conditional learned rules are unsupported by promotion v1");
        }

        PatternExpr source = EvolutionGenomeCompiler.parsePattern(
            gene.sourcePattern());
        PatternExpr target = EvolutionGenomeCompiler.parsePattern(
            gene.targetPattern());
        ExactPolynomialPatternIdentityVerifier.Verification proof =
            verifier.verify(source, target);
        if (!proof.proved()) {
            throw new IllegalArgumentException(
                "learned rule lacks an exact identity proof: "
                    + proof.detailCode());
        }

        String promotionMaterialHash = promotionMaterialHash(
            genome,
            gene,
            report,
            checkedEvidence,
            proof);
        String ruleId = promotedRuleId(genome, gene);
        RuleDescriptor descriptor = descriptor(
            ruleId,
            genome,
            gene,
            report,
            checkedEvidence,
            proof,
            promotionMaterialHash);
        PatternRewriteRule rule = new PatternRewriteRule(
            ruleId,
            source,
            target,
            gene.kind(),
            gene.maxAstGrowth() > 0,
            gene.estimatedCostDelta(),
            true,
            descriptor,
            RecognitionProfile.exact());
        RewriteApplicabilitySchema applicabilitySchema =
            RewriteApplicabilitySchema.fromPatternRule(rule);
        PromotionReceipt receipt = PromotionReceipt.create(
            genome,
            gene,
            report,
            checkedEvidence,
            proof,
            promotionMaterialHash,
            rule,
            applicabilitySchema);
        return new Promotion(rule, applicabilitySchema, receipt, proof);
    }

    private static String promotedRuleId(
        EvolutionGenome genome,
        EvolutionGenome.RewriteGene gene
    ) {
        String digest = genome.alphaStructuralHash()
            .substring("sha256:".length(), "sha256:".length() + 16);
        return "learned.promoted." + digest + "." + gene.geneId();
    }

    private static RuleDescriptor descriptor(
        String ruleId,
        EvolutionGenome genome,
        EvolutionGenome.RewriteGene gene,
        EvolutionGenomeValidator.ValidationReport report,
        PromotionEvidence evidence,
        ExactPolynomialPatternIdentityVerifier.Verification proof,
        String promotionMaterialHash
    ) {
        return new RuleDescriptor(
            ruleId,
            "learned-promoted",
            "Regelsuche Evolution",
            "PROJECT",
            RECEIPT_SCHEMA,
            "promotion=" + promotionMaterialHash
                + "; genome=" + genome.contentHash()
                + "; gene=" + gene.geneId()
                + "; preflight=" + report.contentHash()
                + "; proof=" + proof.proofHash()
                + "; validation=" + evidence.semanticValidationHash()
                + "; holdout=" + evidence.holdoutEvaluationHash(),
            DerivationType.GENERATED,
            RuleStatus.VALIDATED,
            "low",
            List.of("learned", "promoted", "exact-polynomial"),
            List.of(searchEffect(gene.kind())),
            List.of(),
            List.of());
    }

    private static SearchEffect searchEffect(RewriteKind kind) {
        return switch (kind) {
            case SIMPLIFY -> SearchEffect.SIMPLIFYING;
            case EXPAND -> SearchEffect.EXPANDING;
            case FACTOR -> SearchEffect.FACTORIZING;
            case NORMALIZE -> SearchEffect.NORMALIZING;
        };
    }

    private static String promotionMaterialHash(
        EvolutionGenome genome,
        EvolutionGenome.RewriteGene gene,
        EvolutionGenomeValidator.ValidationReport report,
        PromotionEvidence evidence,
        ExactPolynomialPatternIdentityVerifier.Verification proof
    ) {
        StringBuilder material = new StringBuilder();
        append(material, PROMOTER_ID);
        append(material, RECEIPT_SCHEMA);
        append(material, genome.contentHash());
        append(material, genome.alphaStructuralHash());
        append(material, gene.geneId());
        append(material, gene.sourcePattern());
        append(material, gene.targetPattern());
        append(material, gene.kind().name());
        append(material, Integer.toString(gene.estimatedCostDelta()));
        append(material, report.contentHash());
        append(material, evidence.semanticValidationHash());
        append(material, evidence.counterexampleSearchHash());
        append(material, evidence.holdoutEvaluationHash());
        append(material, evidence.leakageAuditHash());
        append(material, evidence.repositoryRevision());
        append(material, proof.proofHash());
        return EvolutionGenome.hash(material.toString());
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    public record PromotionEvidence(
        String semanticValidationHash,
        String counterexampleSearchHash,
        String holdoutEvaluationHash,
        String leakageAuditHash,
        String repositoryRevision
    ) {
        public PromotionEvidence {
            EvolutionGenome.requireSha256(
                semanticValidationHash, "semanticValidationHash");
            EvolutionGenome.requireSha256(
                counterexampleSearchHash, "counterexampleSearchHash");
            EvolutionGenome.requireSha256(
                holdoutEvaluationHash, "holdoutEvaluationHash");
            EvolutionGenome.requireSha256(
                leakageAuditHash, "leakageAuditHash");
            if (repositoryRevision == null
                    || !repositoryRevision.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException(
                    "repositoryRevision must be a lowercase commit SHA");
            }
        }
    }

    public record Promotion(
        PatternRewriteRule rule,
        RewriteApplicabilitySchema applicabilitySchema,
        PromotionReceipt receipt,
        ExactPolynomialPatternIdentityVerifier.Verification proof
    ) {
        public Promotion {
            rule = Objects.requireNonNull(rule, "rule");
            applicabilitySchema = Objects.requireNonNull(
                applicabilitySchema, "applicabilitySchema");
            receipt = Objects.requireNonNull(receipt, "receipt");
            proof = Objects.requireNonNull(proof, "proof");
            if (!proof.proved()
                    || !rule.isEquivalencePreservingByConstruction()
                    || !rule.descriptor().eligibleForRegistration()
                    || !rule.id().equals(applicabilitySchema.ruleId())
                    || !receipt.promotedRuleHash().equals(
                        RuleInventoryFingerprint.ruleContentHash(rule))
                    || !receipt.applicabilitySchemaHash().equals(
                        applicabilitySchema.contentHash())) {
                throw new IllegalArgumentException(
                    "promotion products are inconsistent");
            }
        }
    }

    public record PromotionReceipt(
        String schema,
        String promoterId,
        String genomeHash,
        String alphaStructuralHash,
        String geneId,
        String preflightHash,
        String semanticValidationHash,
        String counterexampleSearchHash,
        String holdoutEvaluationHash,
        String leakageAuditHash,
        String repositoryRevision,
        String proofHash,
        String promotionMaterialHash,
        String promotedRuleId,
        String promotedRuleHash,
        String applicabilitySchemaHash,
        String contentHash
    ) {
        public PromotionReceipt {
            if (!RECEIPT_SCHEMA.equals(schema)
                    || !PROMOTER_ID.equals(promoterId)
                    || geneId == null
                    || geneId.isBlank()
                    || promotedRuleId == null
                    || promotedRuleId.isBlank()
                    || repositoryRevision == null
                    || !repositoryRevision.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException(
                    "promotion receipt identity is invalid");
            }
            EvolutionGenome.requireSha256(genomeHash, "genomeHash");
            EvolutionGenome.requireSha256(
                alphaStructuralHash, "alphaStructuralHash");
            EvolutionGenome.requireSha256(preflightHash, "preflightHash");
            EvolutionGenome.requireSha256(
                semanticValidationHash, "semanticValidationHash");
            EvolutionGenome.requireSha256(
                counterexampleSearchHash, "counterexampleSearchHash");
            EvolutionGenome.requireSha256(
                holdoutEvaluationHash, "holdoutEvaluationHash");
            EvolutionGenome.requireSha256(
                leakageAuditHash, "leakageAuditHash");
            EvolutionGenome.requireSha256(proofHash, "proofHash");
            EvolutionGenome.requireSha256(
                promotionMaterialHash, "promotionMaterialHash");
            EvolutionGenome.requireSha256(
                promotedRuleHash, "promotedRuleHash");
            EvolutionGenome.requireSha256(
                applicabilitySchemaHash, "applicabilitySchemaHash");
            EvolutionGenome.requireSha256(contentHash, "contentHash");
            String expected = EvolutionGenome.hash(material(
                schema,
                promoterId,
                genomeHash,
                alphaStructuralHash,
                geneId,
                preflightHash,
                semanticValidationHash,
                counterexampleSearchHash,
                holdoutEvaluationHash,
                leakageAuditHash,
                repositoryRevision,
                proofHash,
                promotionMaterialHash,
                promotedRuleId,
                promotedRuleHash,
                applicabilitySchemaHash));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "promotion receipt contentHash mismatch");
            }
        }

        private static PromotionReceipt create(
            EvolutionGenome genome,
            EvolutionGenome.RewriteGene gene,
            EvolutionGenomeValidator.ValidationReport report,
            PromotionEvidence evidence,
            ExactPolynomialPatternIdentityVerifier.Verification proof,
            String promotionMaterialHash,
            PatternRewriteRule rule,
            RewriteApplicabilitySchema applicabilitySchema
        ) {
            String promotedRuleHash =
                RuleInventoryFingerprint.ruleContentHash(rule);
            String schemaHash = applicabilitySchema.contentHash();
            String material = material(
                RECEIPT_SCHEMA,
                PROMOTER_ID,
                genome.contentHash(),
                genome.alphaStructuralHash(),
                gene.geneId(),
                report.contentHash(),
                evidence.semanticValidationHash(),
                evidence.counterexampleSearchHash(),
                evidence.holdoutEvaluationHash(),
                evidence.leakageAuditHash(),
                evidence.repositoryRevision(),
                proof.proofHash(),
                promotionMaterialHash,
                rule.id(),
                promotedRuleHash,
                schemaHash);
            return new PromotionReceipt(
                RECEIPT_SCHEMA,
                PROMOTER_ID,
                genome.contentHash(),
                genome.alphaStructuralHash(),
                gene.geneId(),
                report.contentHash(),
                evidence.semanticValidationHash(),
                evidence.counterexampleSearchHash(),
                evidence.holdoutEvaluationHash(),
                evidence.leakageAuditHash(),
                evidence.repositoryRevision(),
                proof.proofHash(),
                promotionMaterialHash,
                rule.id(),
                promotedRuleHash,
                schemaHash,
                EvolutionGenome.hash(material));
        }

        private static String material(
            String schema,
            String promoterId,
            String genomeHash,
            String alphaStructuralHash,
            String geneId,
            String preflightHash,
            String semanticValidationHash,
            String counterexampleSearchHash,
            String holdoutEvaluationHash,
            String leakageAuditHash,
            String repositoryRevision,
            String proofHash,
            String promotionMaterialHash,
            String promotedRuleId,
            String promotedRuleHash,
            String applicabilitySchemaHash
        ) {
            StringBuilder value = new StringBuilder();
            append(value, schema);
            append(value, promoterId);
            append(value, genomeHash);
            append(value, alphaStructuralHash);
            append(value, geneId);
            append(value, preflightHash);
            append(value, semanticValidationHash);
            append(value, counterexampleSearchHash);
            append(value, holdoutEvaluationHash);
            append(value, leakageAuditHash);
            append(value, repositoryRevision);
            append(value, proofHash);
            append(value, promotionMaterialHash);
            append(value, promotedRuleId);
            append(value, promotedRuleHash);
            append(value, applicabilitySchemaHash);
            return value.toString();
        }

        public String toCanonicalJson() {
            return "{"
                + "\"schema\":\"" + schema + "\"," 
                + "\"promoterId\":\"" + promoterId + "\"," 
                + "\"genomeHash\":\"" + genomeHash + "\"," 
                + "\"alphaStructuralHash\":\""
                + alphaStructuralHash + "\"," 
                + "\"geneId\":\"" + geneId + "\"," 
                + "\"preflightHash\":\"" + preflightHash + "\"," 
                + "\"semanticValidationHash\":\""
                + semanticValidationHash + "\"," 
                + "\"counterexampleSearchHash\":\""
                + counterexampleSearchHash + "\"," 
                + "\"holdoutEvaluationHash\":\""
                + holdoutEvaluationHash + "\"," 
                + "\"leakageAuditHash\":\""
                + leakageAuditHash + "\"," 
                + "\"repositoryRevision\":\""
                + repositoryRevision + "\"," 
                + "\"proofHash\":\"" + proofHash + "\"," 
                + "\"promotionMaterialHash\":\""
                + promotionMaterialHash + "\"," 
                + "\"promotedRuleId\":\"" + promotedRuleId + "\"," 
                + "\"promotedRuleHash\":\""
                + promotedRuleHash + "\"," 
                + "\"applicabilitySchemaHash\":\""
                + applicabilitySchemaHash + "\"," 
                + "\"contentHash\":\"" + contentHash + "\"}";
        }
    }
}
