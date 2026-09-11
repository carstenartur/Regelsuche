package de.regelsuche.transform;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
            throw new IllegalArgumentException("schemaId must not be blank");
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

    /** Creates the source-pattern contract of an ordinary declarative rule. */
    public static RewriteApplicabilitySchema fromPatternRule(
        PatternRewriteRule rule
    ) {
        PatternRewriteRule checked = Objects.requireNonNull(rule, "rule");
        List<RequiredAssumptionTemplate> requiredAssumptions =
            inferNonZeroDenominatorAssumptions(checked);
        String schemaRevision = requiredAssumptions.isEmpty()
            ? "pattern-rule-source/v1:"
            : "pattern-rule-source-with-inferred-denominators/v1:";
        return new RewriteApplicabilitySchema(
            schemaRevision + checked.id(),
            checked,
            checked.source(),
            checked.recognitionProfile(),
            requiredAssumptions);
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

    /**
     * Returns a complete fail-closed preparation eligibility decision for each
     * supplied rule. Rule IDs, classes, examples and benchmarks are never used
     * to synthesize applicability contracts.
     */
    public static List<CoverageEntry> coverage(
        List<? extends RewriteRule> rules
    ) {
        Objects.requireNonNull(rules, "rules");
        List<CoverageEntry> result = new ArrayList<>(rules.size());
        Set<String> ids = new LinkedHashSet<>();
        for (RewriteRule supplied : rules) {
            RewriteRule rule = Objects.requireNonNull(supplied, "rule");
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException(
                    "duplicate rule ID in applicability inventory: " + rule.id());
            }
            result.add(coverageOf(rule));
        }
        return List.copyOf(result);
    }

    /** Returns only rules that carry a complete explicit safe schema. */
    public static List<RewriteApplicabilitySchema> safeSchemas(
        List<? extends RewriteRule> rules
    ) {
        return coverage(rules).stream()
            .filter(CoverageEntry::safeProfileEligible)
            .map(CoverageEntry::schema)
            .toList();
    }

    /** Evaluates one rule against the safe preparation boundary. */
    public static CoverageEntry coverageOf(RewriteRule rule) {
        RewriteRule checked = Objects.requireNonNull(rule, "rule");
        if (!checked.isEquivalencePreservingByConstruction()) {
            return CoverageEntry.excluded(
                checked,
                CoverageStatus.OUTSIDE_SAFE_PROFILE_NOT_EQUIVALENCE_PRESERVING,
                "RULE_NOT_EQUIVALENCE_PRESERVING");
        }

        Optional<RewriteApplicabilitySchema> explicit = Objects.requireNonNull(
            checked.explicitApplicabilitySchema(),
            "explicitApplicabilitySchema");
        if (explicit.isPresent()) {
            RewriteApplicabilitySchema schema = explicit.orElseThrow();
            if (schema.executor() != checked) {
                throw new IllegalArgumentException(
                    "explicit applicability schema must retain the exact executor object: "
                        + checked.id());
            }
            if (!schema.ruleId().equals(checked.id())) {
                throw new IllegalArgumentException(
                    "explicit applicability schema rule ID mismatch: "
                        + checked.id());
            }
            return CoverageEntry.eligible(
                checked, CoverageStatus.EXPLICIT_CUSTOM_SCHEMA, schema);
        }

        if (checked instanceof PatternRewriteRule patternRule) {
            if (checked.mayEmitAssumptions()) {
                return CoverageEntry.excluded(
                    checked,
                    CoverageStatus.OUTSIDE_SAFE_PROFILE_UNDECLARED_ASSUMPTIONS,
                    "PATTERN_RULE_ASSUMPTIONS_REQUIRE_EXPLICIT_SCHEMA");
            }
            return CoverageEntry.eligible(
                checked,
                CoverageStatus.DECLARATIVE_PATTERN_SCHEMA,
                fromPatternRule(patternRule));
        }

        return CoverageEntry.excluded(
            checked,
            CoverageStatus.OUTSIDE_SAFE_PROFILE_NO_EXPLICIT_SCHEMA,
            "NO_EXPLICIT_APPLICABILITY_SCHEMA");
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

    /** Preparation eligibility status retained for positive and negative rules. */
    public enum CoverageStatus {
        DECLARATIVE_PATTERN_SCHEMA,
        EXPLICIT_CUSTOM_SCHEMA,
        OUTSIDE_SAFE_PROFILE_NO_EXPLICIT_SCHEMA,
        OUTSIDE_SAFE_PROFILE_UNDECLARED_ASSUMPTIONS,
        OUTSIDE_SAFE_PROFILE_NOT_EQUIVALENCE_PRESERVING
    }

    /** One retained safe-profile coverage decision. */
    public record CoverageEntry(
        RewriteRule rule,
        CoverageStatus status,
        RewriteApplicabilitySchema schema,
        String exclusionReason
    ) {
        public CoverageEntry {
            rule = Objects.requireNonNull(rule, "rule");
            status = Objects.requireNonNull(status, "status");
            exclusionReason = exclusionReason == null ? "" : exclusionReason;
            boolean eligible = status == CoverageStatus.DECLARATIVE_PATTERN_SCHEMA
                || status == CoverageStatus.EXPLICIT_CUSTOM_SCHEMA;
            if (eligible != (schema != null)
                    || eligible != exclusionReason.isEmpty()) {
                throw new IllegalArgumentException(
                    "applicability coverage entry is inconsistent");
            }
        }

        public boolean safeProfileEligible() {
            return schema != null;
        }

        private static CoverageEntry eligible(
            RewriteRule rule,
            CoverageStatus status,
            RewriteApplicabilitySchema schema
        ) {
            return new CoverageEntry(rule, status, schema, "");
        }

        private static CoverageEntry excluded(
            RewriteRule rule,
            CoverageStatus status,
            String reason
        ) {
            return new CoverageEntry(rule, status, null, reason);
        }
    }

    private static List<RequiredAssumptionTemplate>
            inferNonZeroDenominatorAssumptions(PatternRewriteRule rule) {
        Set<PatternExpr> denominators = new LinkedHashSet<>();
        collectDenominators(rule.source(), denominators);
        collectDenominators(rule.target(), denominators);
        return denominators.stream()
            .map(RequiredAssumptionTemplate::nonZero)
            .toList();
    }

    private static void collectDenominators(
        PatternExpr expression,
        Set<PatternExpr> denominators
    ) {
        if (expression instanceof PatternExpr.Operation operation) {
            if (operation.operator() == BinaryOperator.DIV) {
                collectNonZeroFactors(operation.right(), denominators);
            }
            collectDenominators(operation.left(), denominators);
            collectDenominators(operation.right(), denominators);
        } else if (expression instanceof PatternExpr.Function function) {
            function.arguments().forEach(argument ->
                collectDenominators(argument, denominators));
        }
    }

    private static void collectNonZeroFactors(
        PatternExpr expression,
        Set<PatternExpr> denominators
    ) {
        if (expression instanceof PatternExpr.LiteralNumber number) {
            if (number.value().equalsInteger(0)) {
                throw new IllegalArgumentException(
                    "applicability schema contains division by zero");
            }
            return;
        }
        if (expression instanceof PatternExpr.Operation operation
                && operation.operator() == BinaryOperator.MUL) {
            collectNonZeroFactors(operation.left(), denominators);
            collectNonZeroFactors(operation.right(), denominators);
            return;
        }
        if (expression instanceof PatternExpr.Operation operation
                && operation.operator() == BinaryOperator.POW
                && operation.right() instanceof PatternExpr.LiteralNumber exponent
                && exponent.value().signum() > 0
                && exponent.value().isInteger()) {
            collectNonZeroFactors(operation.left(), denominators);
            return;
        }
        denominators.add(expression);
    }
}
