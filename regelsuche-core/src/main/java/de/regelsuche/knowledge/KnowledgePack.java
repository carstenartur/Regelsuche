package de.regelsuche.knowledge;

import de.regelsuche.transform.PatternRewriteRule;

import java.util.List;

public record KnowledgePack(
        String packId,
        String displayName,
        String sourceProject,
        String license,
        String sourceUrl,
        String sourceVersion,
        String sourceReference,
        boolean enabledByDefault,
        KnowledgePackMaturity maturity,
        RuleTier tier,
        List<String> categories,
        List<PatternRewriteRule> rules) {

    /** Backwards compatible constructor defaulting to the {@link RuleTier#FIRST_PARTY} tier. */
    public KnowledgePack(
            String packId,
            String displayName,
            String sourceProject,
            String license,
            String sourceUrl,
            String sourceVersion,
            String sourceReference,
            boolean enabledByDefault,
            KnowledgePackMaturity maturity,
            List<String> categories,
            List<PatternRewriteRule> rules) {
        this(packId, displayName, sourceProject, license, sourceUrl, sourceVersion, sourceReference,
                enabledByDefault, maturity, RuleTier.FIRST_PARTY, categories, rules);
    }

    public KnowledgePack {
        if (isBlank(packId)) {
            throw new IllegalArgumentException("packId is required");
        }
        if (isBlank(displayName)) {
            throw new IllegalArgumentException("displayName is required");
        }
        if (isBlank(sourceProject)) {
            throw new IllegalArgumentException("sourceProject is required");
        }
        if (isBlank(license)) {
            throw new IllegalArgumentException("license is required");
        }
        if (isBlank(sourceUrl)) {
            throw new IllegalArgumentException("sourceUrl is required");
        }
        if (isBlank(sourceVersion)) {
            throw new IllegalArgumentException("sourceVersion is required");
        }
        if (isBlank(sourceReference)) {
            throw new IllegalArgumentException("sourceReference is required");
        }
        if (maturity == null) {
            throw new IllegalArgumentException("maturity is required");
        }
        tier = tier == null ? RuleTier.FIRST_PARTY : tier;
        if (tier == RuleTier.KERNEL && !enabledByDefault) {
            throw new IllegalArgumentException("Kernel knowledge pack must be enabled by default: " + packId);
        }
        categories = categories == null ? List.of() : List.copyOf(categories);
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
