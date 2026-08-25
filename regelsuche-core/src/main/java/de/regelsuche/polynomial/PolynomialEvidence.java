package de.regelsuche.polynomial;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Package-local content-addressing helpers for exact polynomial evidence. */
final class PolynomialEvidence {
    private PolynomialEvidence() {
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
