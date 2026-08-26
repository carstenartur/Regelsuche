package de.regelsuche.math.sympy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Package-local content addressing for the SymPy adapters. */
final class SymPyEvidence {
    private SymPyEvidence() {
    }

    static String sha256(String material) {
        return sha256(material.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] material) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(material));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    static void append(StringBuilder target, String value) {
        String checked = value == null ? "" : value;
        target.append('|')
            .append(checked.length())
            .append(':')
            .append(checked);
    }
}
