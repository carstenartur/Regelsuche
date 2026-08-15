package de.regelsuche.knowledge;

import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Deterministic content identity for the exact executable rewrite inventory. */
public final class RuleInventoryFingerprint {
    private static final String REVISION =
        "regelsuche.rule-inventory-fingerprint/v1";

    private RuleInventoryFingerprint() {
    }

    public static String contentHash(
        Collection<? extends PatternRewriteRule> rules
    ) {
        Objects.requireNonNull(rules, "rules");
        List<String> descriptors = rules.stream()
            .map(rule -> canonicalRule(
                Objects.requireNonNull(rule, "rule")))
            .sorted()
            .toList();
        StringBuilder inventory = new StringBuilder();
        append(inventory, REVISION);
        append(inventory, Integer.toString(descriptors.size()));
        descriptors.forEach(descriptor -> append(inventory, descriptor));
        return sha256(inventory.toString());
    }

    private static String canonicalRule(PatternRewriteRule rule) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, rule.id());
        append(descriptor, canonicalPattern(rule.source()));
        append(descriptor, canonicalPattern(rule.target()));
        append(descriptor, rule.kind().name());
        append(descriptor, Boolean.toString(rule.mayIncreaseComplexity()));
        append(descriptor, Integer.toString(rule.estimatedCostDelta()));
        append(descriptor, Boolean.toString(
            rule.isEquivalencePreservingByConstruction()));
        append(descriptor, canonicalRecognition(rule.recognitionProfile()));
        append(descriptor, canonicalDescriptor(rule.descriptor()));
        return descriptor.toString();
    }

    private static String canonicalPattern(PatternExpr pattern) {
        Objects.requireNonNull(pattern, "pattern");
        StringBuilder descriptor = new StringBuilder();
        if (pattern instanceof PatternExpr.Placeholder placeholder) {
            append(descriptor, "placeholder");
            append(descriptor, placeholder.name());
        } else if (pattern instanceof PatternExpr.LiteralNumber number) {
            append(descriptor, "number");
            append(descriptor, Long.toHexString(
                Double.doubleToLongBits(number.value())));
        } else if (pattern instanceof PatternExpr.LiteralVariable variable) {
            append(descriptor, "variable");
            append(descriptor, variable.name());
        } else if (pattern instanceof PatternExpr.Operation operation) {
            append(descriptor, "operation");
            append(descriptor, operation.operator().name());
            append(descriptor, canonicalPattern(operation.left()));
            append(descriptor, canonicalPattern(operation.right()));
        } else if (pattern instanceof PatternExpr.Function function) {
            append(descriptor, "function");
            append(descriptor, function.name());
            append(descriptor, Integer.toString(function.arguments().size()));
            function.arguments().forEach(argument ->
                append(descriptor, canonicalPattern(argument)));
        } else {
            throw new IllegalArgumentException(
                "Unsupported pattern type: " + pattern.getClass().getName());
        }
        return descriptor.toString();
    }

    private static String canonicalRecognition(
        RecognitionProfile profile
    ) {
        Objects.requireNonNull(profile, "profile");
        StringBuilder descriptor = new StringBuilder();
        appendStrings(descriptor, profile.associativeOperators().stream()
            .map(Enum::name)
            .sorted()
            .toList());
        appendStrings(descriptor, profile.commutativeOperators().stream()
            .map(Enum::name)
            .sorted()
            .toList());
        append(descriptor, Boolean.toString(profile.inferAlgebraicBindings()));
        appendStrings(descriptor, profile.recognitionRuleIds().stream()
            .sorted()
            .toList());
        append(descriptor, Integer.toString(profile.maxEquivalenceDepth()));
        return descriptor.toString();
    }

    private static String canonicalDescriptor(RuleDescriptor rule) {
        Objects.requireNonNull(rule, "rule descriptor");
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, rule.ruleId());
        append(descriptor, rule.packId());
        append(descriptor, rule.originProject());
        append(descriptor, rule.license());
        append(descriptor, rule.sourceVersion());
        append(descriptor, rule.sourceReference());
        append(descriptor, rule.derivationType().name());
        append(descriptor, rule.status().name());
        append(descriptor, rule.riskLevel());
        appendStrings(descriptor, rule.categories().stream()
            .sorted()
            .toList());
        appendStrings(descriptor, rule.searchEffects().stream()
            .map(Enum::name)
            .sorted()
            .toList());
        appendExamples(descriptor, rule.validationExamples());
        appendExamples(descriptor, rule.counterExamples());
        return descriptor.toString();
    }

    private static void appendExamples(
        StringBuilder descriptor,
        List<ValidationExample> examples
    ) {
        List<String> canonical = examples.stream()
            .map(example -> {
                StringBuilder value = new StringBuilder();
                append(value, example.from());
                append(value, example.to());
                return value.toString();
            })
            .sorted(Comparator.naturalOrder())
            .toList();
        appendStrings(descriptor, canonical);
    }

    private static void appendStrings(
        StringBuilder descriptor,
        List<String> values
    ) {
        append(descriptor, Integer.toString(values.size()));
        values.forEach(value -> append(descriptor, value));
    }

    private static void append(StringBuilder descriptor, String value) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(value, "value");
        descriptor.append(value.length()).append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
