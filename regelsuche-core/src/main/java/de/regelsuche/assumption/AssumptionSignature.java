package de.regelsuche.assumption;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Normalized, comparable signature for a set of assumptions.
 */
public record AssumptionSignature(List<String> normalizedAssumptions, String fingerprint) {
    public AssumptionSignature {
        normalizedAssumptions = normalizedAssumptions == null ? List.of() : List.copyOf(normalizedAssumptions);
        fingerprint = fingerprint == null ? "" : fingerprint;
    }

    public static AssumptionSignature ofExpressions(Collection<String> assumptions) {
        if (assumptions == null || assumptions.isEmpty()) {
            return new AssumptionSignature(List.of(), "");
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String assumption : assumptions) {
            if (assumption == null) {
                continue;
            }
            String canonical = normalizeExpression(assumption);
            if (!canonical.isBlank()) {
                normalized.add(canonical);
            }
        }
        List<String> list = List.copyOf(normalized);
        return new AssumptionSignature(list, String.join(";", list));
    }

    public static AssumptionSignature ofAssumptions(Collection<Assumption> assumptions) {
        if (assumptions == null || assumptions.isEmpty()) {
            return new AssumptionSignature(List.of(), "");
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (Assumption assumption : assumptions) {
            if (assumption == null) {
                continue;
            }
            String canonical = assumption.kind() + "|" + normalizeExpression(assumption.expression());
            if (!canonical.isBlank()) {
                normalized.add(canonical);
            }
        }
        List<String> list = List.copyOf(normalized);
        return new AssumptionSignature(list, String.join(";", list));
    }

    public static AssumptionSignature merge(AssumptionSignature left, AssumptionSignature right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        TreeSet<String> merged = new TreeSet<>(left.normalizedAssumptions());
        merged.addAll(right.normalizedAssumptions());
        List<String> list = List.copyOf(merged);
        return new AssumptionSignature(list, String.join(";", list));
    }

    /**
     * Normalize common textual variants of assumptions.
     */
    public static String normalizeExpression(String expression) {
        if (expression == null) {
            return "";
        }
        String canonical = expression.trim()
            .replace("≠", "!=")
            .replaceAll("\\s+", " ")
            .replaceAll("^not\\((.*)=0\\)$", "$1 != 0")
            .replaceAll("\\s*!=\\s*", " != ")
            .replaceAll("\\s*>=\\s*", " >= ")
            .replaceAll("\\s*<=\\s*", " <= ")
            .replaceAll("\\s*>\\s*", " > ")
            .replaceAll("\\s*<\\s*", " < ")
            .trim();
        int notEquals = canonical.indexOf(" != ");
        if (notEquals >= 0) {
            String left = canonical.substring(0, notEquals).trim();
            String right = canonical.substring(notEquals + 4).trim();
            left = stripOuterParens(left);
            right = stripOuterParens(right);
            if (isZero(left) && !right.isBlank()) {
                return right + " != 0";
            }
            if (isZero(right) && !left.isBlank()) {
                return left + " != 0";
            }
        }
        return canonical;
    }

    private static boolean isZero(String value) {
        return value.equals("0") || value.equals("0.0");
    }

    private static String stripOuterParens(String value) {
        String result = value;
        while (result.startsWith("(") && result.endsWith(")") && result.length() > 1) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }
}
