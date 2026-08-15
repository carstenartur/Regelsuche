package de.regelsuche.discovery.representation;

import java.util.List;
import java.util.Objects;

/** Shared canonical validation helpers for representation-discovery run values. */
final class RepresentationDiscoveryRunContractSupport {
    static final String WORKSPACE_SCHEMA =
        "regelsuche.representation-discovery-run-workspace/v1";

    private RepresentationDiscoveryRunContractSupport() {
    }

    static void append(StringBuilder descriptor, String value) {
        KnownStructureCatalog.appendCanonicalField(descriptor, value);
    }

    static String sha256(String value) {
        return KnownStructureCatalog.sha256(value);
    }

    static String requireText(String value, String field) {
        return RepresentationCandidateAssessment.requireText(value, field);
    }

    static String optionalText(String value, String field) {
        Objects.requireNonNull(value, field);
        return value.trim();
    }

    static String requireSha256(String value, String field) {
        String normalized = requireText(value, field);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                field + " must be a lowercase SHA-256 identity");
        }
        return normalized;
    }

    static String optionalSha256(String value, String field) {
        String normalized = optionalText(value, field);
        return normalized.isEmpty()
            ? ""
            : requireSha256(normalized, field);
    }

    static List<String> sortedStrings(List<String> values, String field) {
        return RepresentationCandidateAssessment.sortedUnique(
            Objects.requireNonNull(values, field), field);
    }

    static List<String> sortedRequiredStrings(
        List<String> values,
        String field
    ) {
        List<String> result = sortedStrings(values, field);
        if (result.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return result;
    }
}
