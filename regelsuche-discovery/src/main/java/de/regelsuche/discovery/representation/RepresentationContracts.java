package de.regelsuche.discovery.representation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

final class RepresentationContracts {
    private RepresentationContracts() {
    }

    static String text(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    static List<String> sortedUnique(List<String> values, String field) {
        Objects.requireNonNull(values, field);
        TreeSet<String> normalized = new TreeSet<>();
        for (String value : values) {
            normalized.add(text(value, field + " entry"));
        }
        return List.copyOf(normalized);
    }

    static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
