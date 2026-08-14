package de.regelsuche.discovery.representation;

import de.regelsuche.transform.PatternExpr;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** One versioned, provenance-bearing known mathematical structure. */
public record KnownStructure(
    String id,
    String domainId,
    PatternExpr pattern,
    List<String> requiredAssumptions,
    List<String> consequenceIds,
    String provenance
) {
    public KnownStructure {
        id = requireText(id, "id");
        domainId = requireText(domainId, "domainId");
        pattern = Objects.requireNonNull(pattern, "pattern");
        requiredAssumptions = normalizedStrings(requiredAssumptions, "requiredAssumptions");
        consequenceIds = normalizedStrings(consequenceIds, "consequenceIds");
        provenance = requireText(provenance, "provenance");
    }

    String canonicalDescriptor() {
        return id + "\u0000"
            + domainId + "\u0000"
            + pattern + "\u0000"
            + String.join("\u0001", requiredAssumptions) + "\u0000"
            + String.join("\u0001", consequenceIds) + "\u0000"
            + provenance;
    }

    private static List<String> normalizedStrings(List<String> values, String field) {
        Objects.requireNonNull(values, field);
        TreeSet<String> normalized = new TreeSet<>();
        for (String value : values) {
            normalized.add(requireText(value, field + " entry"));
        }
        return List.copyOf(normalized);
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
