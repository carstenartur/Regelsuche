package de.regelsuche.extension.runtime;

import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * Numeric core compatibility level, deliberately separate from plugin release versions.
 * Supports one to three non-negative decimal components, with omitted components zero.
 * Qualifiers/ranges are unsupported; malformed or oversized declarations fail closed.
 */
record CoreCompatibilityVersion(BigInteger major, BigInteger minor, BigInteger patch)
        implements Comparable<CoreCompatibilityVersion> {
    private static final int MAX_LENGTH = 128;
    private static final Pattern NUMERIC_VERSION =
        Pattern.compile("[0-9]+(?:\\.[0-9]+){0,2}");

    static CoreCompatibilityVersion parse(String value) {
        if (value == null || value.length() > MAX_LENGTH
                || !NUMERIC_VERSION.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "core compatibility version must contain one to three unsigned decimal"
                    + " components and at most " + MAX_LENGTH + " characters");
        }
        String[] parts = value.split("\\.");
        return new CoreCompatibilityVersion(
            new BigInteger(parts[0]),
            parts.length > 1 ? new BigInteger(parts[1]) : BigInteger.ZERO,
            parts.length > 2 ? new BigInteger(parts[2]) : BigInteger.ZERO);
    }

    @Override
    public int compareTo(CoreCompatibilityVersion other) {
        int comparison = major.compareTo(other.major);
        if (comparison == 0) {
            comparison = minor.compareTo(other.minor);
        }
        return comparison == 0 ? patch.compareTo(other.patch) : comparison;
    }
}
