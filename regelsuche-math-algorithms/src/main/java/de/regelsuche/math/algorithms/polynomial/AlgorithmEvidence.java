package de.regelsuche.math.algorithms.polynomial;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Package-local content addressing for mathematical algorithm evidence. */
final class AlgorithmEvidence {
    private AlgorithmEvidence() {
    }

    static String sha256(String material) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                "SHA-256 unavailable",
                exception);
        }
    }

    static void append(StringBuilder target, String value) {
        target.append('|')
            .append(value.length())
            .append(':')
            .append(value);
    }
}
