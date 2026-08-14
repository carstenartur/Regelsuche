package de.regelsuche.knowledge;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Structured provenance and capability policy for one known mathematical structure. */
public record KnownStructureMetadata(
    String sourceProject,
    String license,
    String sourceUrl,
    String sourceVersion,
    String sourceReference,
    String translationNotes,
    List<String> enabledRulePackIds,
    List<String> compatibleBackendIds,
    KnownStructureEvidence minimumEvidence
) {
    public KnownStructureMetadata {
        sourceProject = requireText(sourceProject, "sourceProject");
        license = requireText(license, "license");
        sourceUrl = requireText(sourceUrl, "sourceUrl");
        sourceVersion = requireText(sourceVersion, "sourceVersion");
        sourceReference = requireText(sourceReference, "sourceReference");
        translationNotes = requireText(translationNotes, "translationNotes");
        enabledRulePackIds = sortedUnique(
            enabledRulePackIds, "enabledRulePackIds");
        compatibleBackendIds = sortedUnique(
            compatibleBackendIds, "compatibleBackendIds");
        minimumEvidence = Objects.requireNonNull(
            minimumEvidence, "minimumEvidence");
    }

    public static KnownStructureMetadata legacy(String provenance) {
        String normalized = requireText(provenance, "provenance");
        return new KnownStructureMetadata(
            normalized,
            "UNSPECIFIED",
            "urn:regelsuche:legacy-known-structure",
            "legacy",
            normalized,
            "Legacy in-process structure without structured external-source metadata.",
            List.of(),
            List.of(),
            KnownStructureEvidence.OBSERVED
        );
    }

    public String provenanceSummary() {
        return sourceProject + " " + sourceVersion + " — "
            + sourceReference + " [" + license + "]";
    }

    public String canonicalDescriptor() {
        StringBuilder descriptor = new StringBuilder();
        appendField(descriptor, sourceProject);
        appendField(descriptor, license);
        appendField(descriptor, sourceUrl);
        appendField(descriptor, sourceVersion);
        appendField(descriptor, sourceReference);
        appendField(descriptor, translationNotes);
        appendList(descriptor, enabledRulePackIds);
        appendList(descriptor, compatibleBackendIds);
        appendField(descriptor, minimumEvidence.name());
        return descriptor.toString();
    }

    static List<String> sortedUnique(List<String> values, String field) {
        Objects.requireNonNull(values, field);
        TreeSet<String> normalized = new TreeSet<>();
        for (String value : values) {
            normalized.add(requireText(value, field + " entry"));
        }
        return List.copyOf(normalized);
    }

    static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static void appendList(StringBuilder descriptor, List<String> values) {
        appendField(descriptor, Integer.toString(values.size()));
        values.forEach(value -> appendField(descriptor, value));
    }

    private static void appendField(StringBuilder descriptor, String value) {
        descriptor.append(value.length()).append(':').append(value);
    }
}
