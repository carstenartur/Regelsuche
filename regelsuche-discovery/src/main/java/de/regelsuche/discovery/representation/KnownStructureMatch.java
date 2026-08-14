package de.regelsuche.discovery.representation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** One exact occurrence-local match against a known-structure catalog entry. */
public record KnownStructureMatch(
    String structureId,
    String domainId,
    ExpressionOccurrencePath occurrencePath,
    Map<String, String> bindings,
    List<String> requiredAssumptions,
    List<String> consequenceIds,
    String provenance
) {
    public KnownStructureMatch {
        structureId = requireText(structureId, "structureId");
        domainId = requireText(domainId, "domainId");
        occurrencePath = Objects.requireNonNull(occurrencePath, "occurrencePath");
        Objects.requireNonNull(bindings, "bindings");
        bindings = Collections.unmodifiableMap(
            new LinkedHashMap<>(new TreeMap<>(bindings)));
        requiredAssumptions = List.copyOf(
            Objects.requireNonNull(requiredAssumptions, "requiredAssumptions"));
        consequenceIds = List.copyOf(
            Objects.requireNonNull(consequenceIds, "consequenceIds"));
        provenance = requireText(provenance, "provenance");
    }

    public boolean wholeExpression() {
        return occurrencePath.isRoot();
    }

    public String identity() {
        StringBuilder identity = new StringBuilder(structureId)
            .append('|')
            .append(occurrencePath.canonical());
        bindings.forEach((name, value) -> identity
            .append('|')
            .append(name)
            .append('=')
            .append(value));
        return identity.toString();
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
