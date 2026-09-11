package de.regelsuche.transform;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the preparation-facing applicability inventory without heuristic
 * schema inference.
 */
public final class RewriteApplicabilityCatalog {
    private RewriteApplicabilityCatalog() {
    }

    public static List<Entry> inspect(List<? extends RewriteRule> rules) {
        Objects.requireNonNull(rules, "rules");
        List<Entry> result = new ArrayList<>(rules.size());
        Set<String> ids = new LinkedHashSet<>();
        for (RewriteRule supplied : rules) {
            RewriteRule rule = Objects.requireNonNull(supplied, "rule");
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException(
                    "duplicate rule ID in applicability inventory: " + rule.id());
            }
            result.add(inspect(rule));
        }
        return List.copyOf(result);
    }

    public static List<RewriteApplicabilitySchema> safeSchemas(
        List<? extends RewriteRule> rules
    ) {
        return inspect(rules).stream()
            .filter(Entry::safeProfileEligible)
            .map(Entry::schema)
            .toList();
    }

    public static Entry inspect(RewriteRule rule) {
        RewriteRule checked = Objects.requireNonNull(rule, "rule");
        if (!checked.isEquivalencePreservingByConstruction()) {
            return Entry.excluded(
                checked,
                Status.OUTSIDE_SAFE_PROFILE_NOT_EQUIVALENCE_PRESERVING,
                "RULE_NOT_EQUIVALENCE_PRESERVING");
        }
        if (checked instanceof RewriteApplicabilitySchemaProvider provider) {
            RewriteApplicabilitySchema schema = Objects.requireNonNull(
                provider.applicabilitySchema(),
                "explicit applicability schema");
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
            return Entry.eligible(
                checked,
                Status.EXPLICIT_ALGORITHMIC_SCHEMA,
                schema);
        }
        if (checked instanceof PatternRewriteRule patternRule) {
            if (checked.mayEmitAssumptions()) {
                return Entry.excluded(
                    checked,
                    Status.OUTSIDE_SAFE_PROFILE_UNDECLARED_ASSUMPTIONS,
                    "PATTERN_RULE_ASSUMPTIONS_REQUIRE_EXPLICIT_SCHEMA");
            }
            return Entry.eligible(
                checked,
                Status.DECLARATIVE_PATTERN_SCHEMA,
                RewriteApplicabilitySchema.fromPatternRule(patternRule));
        }
        return Entry.excluded(
            checked,
            Status.OUTSIDE_SAFE_PROFILE_NO_EXPLICIT_SCHEMA,
            "NO_EXPLICIT_APPLICABILITY_SCHEMA");
    }

    public enum Status {
        DECLARATIVE_PATTERN_SCHEMA,
        EXPLICIT_ALGORITHMIC_SCHEMA,
        OUTSIDE_SAFE_PROFILE_NO_EXPLICIT_SCHEMA,
        OUTSIDE_SAFE_PROFILE_UNDECLARED_ASSUMPTIONS,
        OUTSIDE_SAFE_PROFILE_NOT_EQUIVALENCE_PRESERVING
    }

    public record Entry(
        RewriteRule rule,
        Status status,
        RewriteApplicabilitySchema schema,
        String exclusionReason
    ) {
        public Entry {
            rule = Objects.requireNonNull(rule, "rule");
            status = Objects.requireNonNull(status, "status");
            exclusionReason = exclusionReason == null ? "" : exclusionReason;
            boolean eligible = status == Status.DECLARATIVE_PATTERN_SCHEMA
                || status == Status.EXPLICIT_ALGORITHMIC_SCHEMA;
            if (eligible != (schema != null) || eligible != exclusionReason.isEmpty()) {
                throw new IllegalArgumentException(
                    "applicability catalog entry is inconsistent");
            }
        }

        public boolean safeProfileEligible() {
            return schema != null;
        }

        static Entry eligible(
            RewriteRule rule,
            Status status,
            RewriteApplicabilitySchema schema
        ) {
            return new Entry(rule, status, schema, "");
        }

        static Entry excluded(
            RewriteRule rule,
            Status status,
            String reason
        ) {
            return new Entry(rule, status, null, reason);
        }
    }
}
