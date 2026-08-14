package de.regelsuche.knowledge;

import java.util.List;
import java.util.Objects;

/** Source and policy metadata retained with one imported mathematical form. */
public record KnownStructureMetadata(
    String sourceProject, String license, String sourceUrl,
    String sourceVersion, String sourceReference, String translationNotes,
    List<String> enabledRulePackIds, List<String> compatibleBackendIds,
    KnownStructureEvidence minimumEvidence
) {
    public KnownStructureMetadata {
        sourceProject = text(sourceProject, "sourceProject");
        license = text(license, "license");
        sourceUrl = text(sourceUrl, "sourceUrl");
        sourceVersion = text(sourceVersion, "sourceVersion");
        sourceReference = text(sourceReference, "sourceReference");
        translationNotes = text(translationNotes, "translationNotes");
        enabledRulePackIds = normalized(enabledRulePackIds);
        compatibleBackendIds = normalized(compatibleBackendIds);
        minimumEvidence = Objects.requireNonNull(minimumEvidence);
    }

    public static KnownStructureMetadata legacy(String provenance) {
        return new KnownStructureMetadata(
            provenance, "UNSPECIFIED", "urn:regelsuche:legacy-known-structure",
            "legacy", provenance, "Legacy in-process structure.",
            List.of(), List.of(), KnownStructureEvidence.OBSERVED);
    }

    public String provenanceSummary() {
        return sourceProject + " " + sourceVersion + " — " + sourceReference
            + " [" + license + "]";
    }

    public String canonicalDescriptor() {
        StringBuilder value = new StringBuilder();
        append(value, sourceProject, license, sourceUrl, sourceVersion,
            sourceReference, translationNotes);
        appendList(value, enabledRulePackIds);
        appendList(value, compatibleBackendIds);
        append(value, minimumEvidence.name());
        return value.toString();
    }

    private static List<String> normalized(List<String> values) {
        return Objects.requireNonNull(values).stream()
            .map(value -> text(value, "list entry")).distinct().sorted().toList();
    }

    private static String text(String value, String field) {
        String result = Objects.requireNonNull(value, field).trim();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return result;
    }

    private static void appendList(StringBuilder target, List<String> values) {
        append(target, Integer.toString(values.size()));
        values.forEach(value -> append(target, value));
    }

    private static void append(StringBuilder target, String... values) {
        for (String value : values) {
            target.append(value.length()).append(':').append(value);
        }
    }
}
