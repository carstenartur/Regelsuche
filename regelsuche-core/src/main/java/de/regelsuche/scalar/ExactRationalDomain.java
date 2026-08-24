package de.regelsuche.scalar;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Versioned, fail-closed parser for exact rational scalar literals.
 *
 * <p>Version 1 accepts signed integers, explicit integer fractions and finite
 * decimal lexemes. Decimals are interpreted from their source characters and
 * never through {@code double}. Scientific notation, repeating decimals and
 * approximate values are outside this domain.</p>
 */
public final class ExactRationalDomain {
    public static final String DOMAIN_ID =
        "regelsuche.exact-rational-scalar/v1";
    public static final Limits DEFAULT_LIMITS =
        new Limits(4_096, 1_024, 256);

    private static final Pattern INTEGER =
        Pattern.compile("[+-]?[0-9]+");
    private static final Pattern FRACTION = Pattern.compile(
        "([+-]?[0-9]+)\\s*/\\s*([+-]?[0-9]+)");
    private static final Pattern DECIMAL = Pattern.compile(
        "([+-]?)([0-9]+)\\.([0-9]+)");

    private final Limits limits;

    public ExactRationalDomain() {
        this(DEFAULT_LIMITS);
    }

    public ExactRationalDomain(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public Limits limits() {
        return limits;
    }

    public ExactRationalParseEvidence parse(String literal) {
        String source = literal == null ? "" : literal.trim();
        if (source.isEmpty()) {
            return ExactRationalParseEvidence.failure(
                Status.UNSUPPORTED,
                "LITERAL_BLANK",
                source);
        }
        if (source.length() > limits.maxLiteralCharacters()) {
            return ExactRationalParseEvidence.failure(
                Status.LIMIT_EXCEEDED,
                "LITERAL_CHARACTER_LIMIT_EXCEEDED",
                source);
        }

        Matcher fraction = FRACTION.matcher(source);
        if (fraction.matches()) {
            String numeratorText = fraction.group(1);
            String denominatorText = fraction.group(2);
            if (digitCount(numeratorText) + digitCount(denominatorText)
                    > limits.maxDigits()) {
                return ExactRationalParseEvidence.failure(
                    Status.LIMIT_EXCEEDED,
                    "RATIONAL_DIGIT_LIMIT_EXCEEDED",
                    source);
            }
            BigInteger denominator = new BigInteger(denominatorText);
            if (denominator.signum() == 0) {
                return ExactRationalParseEvidence.failure(
                    Status.ZERO_DENOMINATOR,
                    "RATIONAL_DENOMINATOR_ZERO",
                    source);
            }
            return exact(
                source,
                new ExactRational(
                    new BigInteger(numeratorText),
                    denominator));
        }

        if (INTEGER.matcher(source).matches()) {
            if (digitCount(source) > limits.maxDigits()) {
                return ExactRationalParseEvidence.failure(
                    Status.LIMIT_EXCEEDED,
                    "INTEGER_DIGIT_LIMIT_EXCEEDED",
                    source);
            }
            return exact(
                source,
                ExactRational.integer(new BigInteger(source)));
        }

        Matcher decimal = DECIMAL.matcher(source);
        if (decimal.matches()) {
            String sign = decimal.group(1);
            String integral = decimal.group(2);
            String fractional = decimal.group(3);
            int digits = integral.length() + fractional.length();
            if (digits > limits.maxDigits()) {
                return ExactRationalParseEvidence.failure(
                    Status.LIMIT_EXCEEDED,
                    "DECIMAL_DIGIT_LIMIT_EXCEEDED",
                    source);
            }
            if (fractional.length() > limits.maxDecimalScale()) {
                return ExactRationalParseEvidence.failure(
                    Status.LIMIT_EXCEEDED,
                    "DECIMAL_SCALE_LIMIT_EXCEEDED",
                    source);
            }
            BigInteger unscaled = new BigInteger(
                sign + integral + fractional);
            BigInteger scale = BigInteger.TEN.pow(fractional.length());
            return exact(
                source,
                new ExactRational(unscaled, scale));
        }

        return ExactRationalParseEvidence.failure(
            Status.UNSUPPORTED,
            "LITERAL_GRAMMAR_UNSUPPORTED",
            source);
    }

    private ExactRationalParseEvidence exact(
        String source,
        ExactRational value
    ) {
        String canonical = value.canonicalText();
        String valueId = hash(lengthPrefixed(
            DOMAIN_ID + ".value",
            canonical));
        String certificate = hash(lengthPrefixed(
            DOMAIN_ID + ".parse",
            source,
            canonical,
            valueId));
        return ExactRationalParseEvidence.exact(
            source,
            value,
            canonical,
            valueId,
            certificate);
    }

    private static int digitCount(String value) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            if (Character.isDigit(value.charAt(index))) {
                count++;
            }
        }
        return count;
    }

    private static String lengthPrefixed(String... values) {
        StringBuilder material = new StringBuilder();
        for (String value : values) {
            material.append(value.length())
                .append(':')
                .append(value);
        }
        return material.toString();
    }

    private static String hash(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public enum Status {
        EXACT,
        UNSUPPORTED,
        ZERO_DENOMINATOR,
        LIMIT_EXCEEDED
    }

    public record Limits(
        int maxLiteralCharacters,
        int maxDigits,
        int maxDecimalScale
    ) {
        public Limits {
            if (maxLiteralCharacters < 1
                    || maxDigits < 1
                    || maxDecimalScale < 0) {
                throw new IllegalArgumentException(
                    "exact rational limits are invalid");
            }
        }
    }
}
