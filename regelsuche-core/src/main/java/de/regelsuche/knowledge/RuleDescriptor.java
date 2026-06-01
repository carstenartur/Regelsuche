package de.regelsuche.knowledge;

import java.util.List;

public record RuleDescriptor(
        String ruleId,
        String packId,
        String originProject,
        String license,
        String sourceVersion,
        String sourceReference,
        DerivationType derivationType,
        RuleStatus status,
        String riskLevel,
        List<String> categories,
        List<ValidationExample> validationExamples) {

    public RuleDescriptor {
        if (isBlank(ruleId)) {
            throw new IllegalArgumentException("ruleId is required");
        }
        if (isBlank(packId)) {
            throw new IllegalArgumentException("packId is required");
        }
        if (isBlank(originProject)) {
            throw new IllegalArgumentException("originProject is required");
        }
        if (isBlank(license)) {
            throw new IllegalArgumentException("license is required");
        }
        if (isBlank(sourceVersion)) {
            throw new IllegalArgumentException("sourceVersion is required");
        }
        if (isBlank(sourceReference)) {
            throw new IllegalArgumentException("sourceReference is required");
        }
        if (derivationType == null) {
            throw new IllegalArgumentException("derivationType is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        riskLevel = isBlank(riskLevel) ? "low" : riskLevel;
        categories = categories == null ? List.of() : List.copyOf(categories);
        validationExamples = validationExamples == null ? List.of() : List.copyOf(validationExamples);
    }

    public static RuleDescriptor core(String ruleId, List<String> categories) {
        return new RuleDescriptor(ruleId, "core", "Regelsuche", "PROJECT", "local", "built-in core rule",
                DerivationType.ORIGINAL, RuleStatus.VALIDATED, "low", categories, List.of());
    }

    public boolean eligibleForRegistration() {
        return status == RuleStatus.REVIEWED || status == RuleStatus.VALIDATED;
    }

    public boolean external() {
        return !"core".equals(packId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
