package de.regelsuche.evolution;

import static de.regelsuche.evolution.LearnedPatternAuthorizationJson.hash;
import static de.regelsuche.evolution.LearnedPatternAuthorizationJson.requireHash;
import static de.regelsuche.evolution.LearnedPatternAuthorizationJson.requireRevision;
import static de.regelsuche.evolution.LearnedPatternAuthorizationJson.requireText;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Content-addressed production authorization receipt for one learned pattern rule. */
public record LearnedPatternAuthorizationReceipt(
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
    public static final String SCHEMA =
        "regelsuche.learned-pattern-rule-authorization-receipt/v1";
    public static final String AUTHORIZER_ID =
        "regelsuche.learned-pattern-rule-authorizer/v1";

    public LearnedPatternAuthorizationReceipt {
        if (!SCHEMA.equals(schema) || !AUTHORIZER_ID.equals(authorizerId)) {
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
        for (String value : List.of(
                evidenceBundleHash,
                semanticValidationHash,
                counterexampleSearchHash,
                holdoutEvaluationHash,
                leakageAuditHash,
                promotionReceiptHash,
                promotedRuleHash,
                applicabilitySchemaHash,
                contentHash)) {
            requireHash(value, "authorization hash");
        }
        requireText(promotedRuleId, "promotedRuleId");
        String expected = hash(payload(
            schema,
            authorizerId,
            genomeHash,
            geneId,
            repositoryRevision,
            authorizedAt,
            validUntil,
            evidenceBundleHash,
            semanticValidationHash,
            counterexampleSearchHash,
            holdoutEvaluationHash,
            leakageAuditHash,
            promotionReceiptHash,
            promotedRuleId,
            promotedRuleHash,
            applicabilitySchemaHash));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "authorization receipt contentHash mismatch");
        }
    }

    static LearnedPatternAuthorizationReceipt create(
        EvolutionGenome genome,
        String geneId,
        String repositoryRevision,
        Instant authorizedAt,
        LearnedPatternAuthorizationBundle bundle,
        EvolutionSplitManifest split,
        EvolutionValidationSelection validation,
        EvolutionFinalTestEvaluation holdout,
        LearnedPatternCounterexampleEvidence counterexample,
        LearnedPatternRulePromoter.Promotion promotion,
        String promotedRuleHash
    ) {
        Objects.requireNonNull(genome, "genome");
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(split, "split");
        Objects.requireNonNull(validation, "validation");
        Objects.requireNonNull(holdout, "holdout");
        Objects.requireNonNull(counterexample, "counterexample");
        Objects.requireNonNull(promotion, "promotion");
        requireHash(promotedRuleHash, "promotedRuleHash");
        String applicabilityHash = promotion.applicabilitySchema().contentHash();
        String contentHash = hash(payload(
            SCHEMA,
            AUTHORIZER_ID,
            genome.contentHash(),
            geneId,
            repositoryRevision,
            authorizedAt,
            bundle.expiresAt(),
            bundle.contentHash(),
            validation.contentHash(),
            counterexample.contentHash(),
            holdout.contentHash(),
            split.contentHash(),
            promotion.receipt().contentHash(),
            promotion.rule().id(),
            promotedRuleHash,
            applicabilityHash));
        return new LearnedPatternAuthorizationReceipt(
            SCHEMA,
            AUTHORIZER_ID,
            genome.contentHash(),
            geneId,
            repositoryRevision,
            authorizedAt,
            bundle.expiresAt(),
            bundle.contentHash(),
            validation.contentHash(),
            counterexample.contentHash(),
            holdout.contentHash(),
            split.contentHash(),
            promotion.receipt().contentHash(),
            promotion.rule().id(),
            promotedRuleHash,
            applicabilityHash,
            contentHash);
    }

    public static LearnedPatternAuthorizationReceipt fromCanonicalJson(
        String json
    ) {
        return LearnedPatternAuthorizationJson.read(
            json, LearnedPatternAuthorizationReceipt.class,
            "authorization receipt");
    }

    public String toCanonicalJson() {
        return LearnedPatternAuthorizationJson.write(this);
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
