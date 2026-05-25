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
            String canonical = assumption.trim().replaceAll("\\s+", " ");
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
            String canonical = assumption.kind() + "|" + assumption.expression().trim().replaceAll("\\s+", " ");
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
}
