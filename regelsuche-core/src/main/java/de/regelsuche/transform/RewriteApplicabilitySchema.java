package de.regelsuche.transform;

import de.regelsuche.knowledge.RuleInventoryFingerprint;
import java.util.List;
import java.util.Objects;

/** Explicit applicability contract for one concrete rewrite executor. */
public record RewriteApplicabilitySchema(
    String schemaId,
    RewriteRule executor,
    PatternExpr pattern,
    RecognitionProfile recognitionProfile,
    List<RequiredAssumptionTemplate> requiredAssumptions
) {
    public RewriteApplicabilitySchema {
        if (schemaId == null || schemaId.isBlank()) {
            throw new IllegalArgumentException(
                "schemaId must not be blank");
        }
        schemaId = schemaId.trim();
        executor = Objects.requireNonNull(executor, "executor");
        pattern = Objects.requireNonNull(pattern, "pattern");
        recognitionProfile = recognitionProfile == null
            ? RecognitionProfile.exact()
            : recognitionProfile;
        requiredAssumptions = List.copyOf(Objects.requireNonNull(
            requiredAssumptions, "requiredAssumptions"));
        if (requiredAssumptions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                "requiredAssumptions must not contain null");
        }
    }

    public RewriteApplicabilitySchema(
        String schemaId,
        RewriteRule executor,
        PatternExpr pattern,
        RecognitionProfile recognitionProfile
    ) {
        this(schemaId, executor, pattern, recognitionProfile, List.of());
    }

    public static RewriteApplicabilitySchema fromPatternRule(
        PatternRewriteRule rule
    ) {
        PatternRewriteRule checked = Objects.requireNonNull(rule, "rule");
        return new RewriteApplicabilitySchema(
            "pattern-rule-source/v1:" + checked.id(),
            checked,
            checked.source(),
            checked.recognitionProfile(),
            List.of());
    }

    public static RewriteApplicabilitySchema fromPatternRule(
        PatternRewriteRule rule,
        List<RequiredAssumptionTemplate> requiredAssumptions
    ) {
        PatternRewriteRule checked = Objects.requireNonNull(rule, "rule");
        return new RewriteApplicabilitySchema(
            "pattern-rule-source-with-conditions/v1:" + checked.id(),
            checked,
            checked.source(),
            checked.recognitionProfile(),
            requiredAssumptions);
    }

    public String ruleId() {
        return executor.id();
    }

    public String contentHash() {
        return RuleInventoryFingerprint.applicabilitySchemaContentHash(
            schemaId,
            executor,
            pattern,
            recognitionProfile,
            requiredAssumptions);
    }
}
