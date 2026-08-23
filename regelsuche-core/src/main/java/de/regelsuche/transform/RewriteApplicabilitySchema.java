package de.regelsuche.transform;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
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
            if (number.value() == 0.0d) {
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
                && operation.right()
                    instanceof PatternExpr.LiteralNumber exponent
                && exponent.value() > 0.0d
                && exponent.value() == Math.rint(exponent.value())) {
            collectNonZeroFactors(operation.left(), denominators);
            return;
        }
        denominators.add(expression);
    }
}
