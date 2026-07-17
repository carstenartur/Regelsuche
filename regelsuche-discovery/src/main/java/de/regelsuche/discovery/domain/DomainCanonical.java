package de.regelsuche.discovery.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class DomainCanonical {
    private DomainCanonical() {
    }

    static String sha256(String material) {
        Objects.requireNonNull(material, "material");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static String requireIdentifier(String value, String name) {
        requireText(value, name);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,191}")) {
            throw new IllegalArgumentException(name + " contains unsupported characters: " + value);
        }
        return value;
    }

    static String requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
        return value;
    }

    static Map<String, String> sortedMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                requireText(entry.getKey(), "map key");
                Objects.requireNonNull(entry.getValue(), "map value");
                result.put(entry.getKey(), entry.getValue());
            });
        return java.util.Collections.unmodifiableMap(result);
    }

    static List<String> sortedDistinct(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .distinct()
            .sorted()
            .toList();
    }

    static <T> List<T> sortedCopy(List<T> values, Comparator<? super T> comparator) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        ArrayList<T> result = new ArrayList<>(values);
        result.sort(comparator);
        return List.copyOf(result);
    }

    static String canonicalMap(Map<String, String> values) {
        return sortedMap(values).entrySet().stream()
            .map(entry -> entry.getKey().length() + ":" + entry.getKey()
                + "=" + entry.getValue().length() + ":" + entry.getValue())
            .collect(java.util.stream.Collectors.joining("|"));
    }

    static String canonicalList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
            .map(value -> {
                String normalized = Objects.requireNonNull(value, "list value");
                return normalized.length() + ":" + normalized;
            })
            .collect(java.util.stream.Collectors.joining("|"));
    }
}
