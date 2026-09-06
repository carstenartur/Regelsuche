package de.regelsuche.scalar;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
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
    public static final int MAX_LITERAL_CHARACTERS = 4_096;
    public static final int MAX_DIGITS = 1_024;
    public static final int MAX_DECIMAL_SCALE = 256;
    public static final Limits DEFAULT_LIMITS =
        new Limits(
            MAX_LITERAL_CHARACTERS,
            MAX_DIGITS,
            MAX_DECIMAL_SCALE);

    private static final int MAX_LEGACY_BRIDGE_BITS = 4_096;
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
        String raw = literal == null ? "" : literal;
        if (raw.length() > limits.maxLiteralCharacters()) {
            return failure(
                Status.LIMIT_EXCEEDED,
                "LITERAL_CHARACTER_LIMIT_EXCEEDED",
                boundedSource(raw));
        }

        String source = raw.strip();
        if (source.isEmpty()) {
            return failure(
                Status.UNSUPPORTED,
                "LITERAL_BLANK",
                source);
        }
        return parseBounded(source);
    }

    /**
     * Interprets an already-existing finite legacy {@code double} leaf by the
     * repository's shortest-decimal convention.
     *
     * <p>This is an explicit migration boundary, not evidence that the source
     * literal or a previous floating-point computation was exact. Callers that
     * possess parser-issued literal evidence must use that evidence instead.</p>
     */
    public static Optional<ExactRational> legacyDecimalValue(double value) {
        if (!Double.isFinite(value)) {
            return Optional.empty();
        }
        BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
        BigInteger numerator = decimal.unscaledValue();
        int scale = decimal.scale();
        if (scale < 0) {
            numerator = numerator.multiply(BigInteger.TEN.pow(-scale));
            return Optional.of(ExactRational.integer(numerator));
        }
        return Optional.of(new ExactRational(
            numerator,
            BigInteger.TEN.pow(scale)));
    }

    /**
     * Returns a legacy {@code double} only when its shortest-decimal
     * interpretation is exactly {@code value}.
     *
     * <p>Nonterminating rationals, overflowing values, oversized migration
     * inputs and rounded decimal projections return an empty result. This
     * method therefore cannot be used as authority for an approximate
     * equality.</p>
     */
    public static OptionalDouble exactLegacyDecimalDouble(
        ExactRational value
    ) {
        Objects.requireNonNull(value, "value");
        if (value.numerator().abs().bitLength() > MAX_LEGACY_BRIDGE_BITS
                || value.denominator().bitLength() > MAX_LEGACY_BRIDGE_BITS) {
            return OptionalDouble.empty();
        }
        try {
            BigDecimal decimal = new BigDecimal(value.numerator())
                .divide(new BigDecimal(value.denominator()));
            double legacy = decimal.doubleValue();
            if (!Double.isFinite(legacy)) {
                return OptionalDouble.empty();
            }
            Optional<ExactRational> roundTrip = legacyDecimalValue(legacy);
            return roundTrip.isPresent() && roundTrip.get().equals(value)
                ? OptionalDouble.of(legacy)
                : OptionalDouble.empty();
        } catch (ArithmeticException nonterminatingDecimal) {
            return OptionalDouble.empty();
        }
    }

    private ExactRationalParseEvidence parseBounded(String source) {
        Matcher fraction = FRACTION.matcher(source);
        if (fraction.matches()) {
            return parseFraction(
                source,
                fraction.group(1),
                fraction.group(2));
        }
        if (INTEGER.matcher(source).matches()) {
            return parseInteger(source);
        }
        Matcher decimal = DECIMAL.matcher(source);
        if (decimal.matches()) {
            return parseDecimal(
                source,
                decimal.group(1),
                decimal.group(2),
                decimal.group(3));
        }
        return failure(
            Status.UNSUPPORTED,
            "LITERAL_GRAMMAR_UNSUPPORTED",
            source);
    }

    private ExactRationalParseEvidence parseFraction(
        String source,
        String numeratorText,
        String denominatorText
    ) {
        if (signedDigitsAreZero(denominatorText)) {
            return failure(
                Status.ZERO_DENOMINATOR,
                "RATIONAL_DENOMINATOR_ZERO",
                source);
        }
        if (digitCount(numeratorText) + digitCount(denominatorText)
                > limits.maxDigits()) {
            return failure(
                Status.LIMIT_EXCEEDED,
                "RATIONAL_DIGIT_LIMIT_EXCEEDED",
                source);
        }
        return exact(
            source,
            new ExactRational(
                new BigInteger(numeratorText),
                new BigInteger(denominatorText)));
    }

    private ExactRationalParseEvidence parseInteger(String source) {
        if (digitCount(source) > limits.maxDigits()) {
            return failure(
                Status.LIMIT_EXCEEDED,
                "INTEGER_DIGIT_LIMIT_EXCEEDED",
                source);
        }
        return exact(
            source,
            ExactRational.integer(new BigInteger(source)));
    }

    private ExactRationalParseEvidence parseDecimal(
        String source,
        String sign,
        String integral,
        String fractional
    ) {
        if (integral.length() + fractional.length()
                > limits.maxDigits()) {
            return failure(
                Status.LIMIT_EXCEEDED,
                "DECIMAL_DIGIT_LIMIT_EXCEEDED",
                source);
        }
        if (fractional.length() > limits.maxDecimalScale()) {
            return failure(
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
            limits.canonicalMaterial(),
            source,
            canonical,
            valueId));
        return ExactRationalParseEvidence.exact(
            source,
            limits,
            value,
            canonical,
            valueId,
            certificate);
    }

    private ExactRationalParseEvidence failure(
        Status status,
        String detailCode,
        String source
    ) {
        return ExactRationalParseEvidence.failure(
            status,
            detailCode,
            source,
            limits);
    }

    private String boundedSource(String raw) {
        return raw.substring(0, limits.maxLiteralCharacters());
    }

    private static boolean signedDigitsAreZero(String value) {
        int index = value.startsWith("+") || value.startsWith("-")
            ? 1
            : 0;
        while (index < value.length()) {
            if (value.charAt(index) != '0') {
                return false;
            }
            index++;
        }
        return true;
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

    static String lengthPrefixed(String... values) {
        StringBuilder material = new StringBuilder();
        for (String value : values) {
            int byteLength = value.getBytes(StandardCharsets.UTF_8).length;
            material.append(byteLength)
                .append(':')
                .append(value);
        }
        return material.toString();
    }

    static String hash(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                "SHA-256 unavailable",
                exception);
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
                    || maxLiteralCharacters > MAX_LITERAL_CHARACTERS
                    || maxDigits < 1
                    || maxDigits > MAX_DIGITS
                    || maxDecimalScale < 0
                    || maxDecimalScale > MAX_DECIMAL_SCALE
                    || maxDecimalScale > maxDigits) {
                throw new IllegalArgumentException(
                    "exact rational limits are invalid");
            }
        }

        String canonicalMaterial() {
            return maxLiteralCharacters + ":"
                + maxDigits + ":"
                + maxDecimalScale;
        }
    }
}
