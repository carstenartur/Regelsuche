package de.regelsuche.transform;

import de.regelsuche.knowledge.RuleInventoryFingerprint;
import java.util.Objects;

/**
 * Explicit, content-addressed applicability contract for one concrete rewrite
 * executor.
 *
 * <p>The pattern and recognition profile describe when preparation should aim
 * to make the executor applicable. They are not a second rewrite
 * implementation and contain no target expression. A positive application is
 * authorized only by concrete replay through {@link #executor()}.</p>
 */
public record RewriteApplicabilitySchema(
    String schemaId,
    RewriteRule executor,
    PatternExpr pattern,
    RecognitionProfile recognitionProfile
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
    }

    public static RewriteApplicabilitySchema fromPatternRule(
        PatternRewriteRule rule
    ) {
        PatternRewriteRule checked = Objects.requireNonNull(rule, "rule");
        return new RewriteApplicabilitySchema(
            "pattern-rule-source/v1:" + checked.id(),
            checked,
            checked.source(),
            checked.recognitionProfile());
    }

    public String ruleId() {
        return executor.id();
    }

    public String contentHash() {
        return RuleInventoryFingerprint.applicabilitySchemaContentHash(
            schemaId,
            executor,
            pattern,
            recognitionProfile);
    }
}
