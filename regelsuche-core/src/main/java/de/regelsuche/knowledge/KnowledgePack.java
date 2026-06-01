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
        List<String> categories,
        List<PatternRewriteRule> rules) {

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
        categories = categories == null ? List.of() : List.copyOf(categories);
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
