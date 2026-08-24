package de.regelsuche.scalar;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Independently replays serialized rational-content evidence. */
public final class ExactRationalPolynomialContentVerifier {
    private static final Pattern CANONICAL_RATIONAL = Pattern.compile(
        "^(?:0|-?[1-9][0-9]*|-?[1-9][0-9]*/[1-9][0-9]*)$");
    private static final int MAX_COEFFICIENT_TEXT = 5_000;

    public Verification verify(SerializedEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (!ExactRationalPolynomialContentNormalizer.DOMAIN_ID.equals(
                evidence.domainId())) {
            return Verification.rejected("DOMAIN_ID_MISMATCH");
        }
        ExactRationalPolynomial polynomial;
        try {
            polynomial = new ExactRationalPolynomial(
                parseCoefficients(evidence.sourceCoefficients()));
        } catch (IllegalArgumentException exception) {
            return Verification.rejected(
                "SOURCE_COEFFICIENTS_NOT_CANONICAL");
        }

        ExactRationalPolynomialContentEvidence replay =
            new ExactRationalPolynomialContentNormalizer(
                evidence.budget()).normalize(polynomial);
        if (!replay.serialized().equals(evidence)) {
            return Verification.rejected(
                "CONTENT_EVIDENCE_REPLAY_MISMATCH");
        }
        return evidence.status()
            == ExactRationalPolynomialContentNormalizer.Status.NORMALIZED
                ? Verification.verifiedNormalized()
                : Verification.verifiedFailure();
    }

    private List<ExactRational> parseCoefficients(
        List<String> source
    ) {
        if (source.isEmpty()
                || source.size()
                    > ExactRationalPolynomial.MAX_COEFFICIENTS) {
            throw new IllegalArgumentException(
                "invalid coefficient count");
        }
        List<ExactRational> result = new ArrayList<>(source.size());
        for (String text : source) {
            result.add(parseCanonical(text));
        }
        return List.copyOf(result);
    }

    private ExactRational parseCanonical(String text) {
        if (text == null
                || text.length() > MAX_COEFFICIENT_TEXT
                || !CANONICAL_RATIONAL.matcher(text).matches()) {
            throw new IllegalArgumentException(
                "coefficient is not canonical");
        }
        int slash = text.indexOf('/');
        ExactRational value = slash < 0
            ? ExactRational.integer(new BigInteger(text))
            : new ExactRational(
                new BigInteger(text.substring(0, slash)),
                new BigInteger(text.substring(slash + 1)));
        if (!value.canonicalText().equals(text)) {
            throw new IllegalArgumentException(
                "coefficient is not reduced");
        }
        return value;
    }

    public enum Status {
        VERIFIED_NORMALIZED,
        VERIFIED_FAILURE,
        REJECTED
    }

    public record SerializedNormalization(
        String denominatorClearingFactor,
        List<String> integralCoefficientsAscending,
        String integerContent,
        List<String> primitiveCoefficientsAscending,
        String scalar
    ) {
        public SerializedNormalization {
            Objects.requireNonNull(
                denominatorClearingFactor,
                "denominatorClearingFactor");
            integralCoefficientsAscending = List.copyOf(
                Objects.requireNonNull(
                    integralCoefficientsAscending,
                    "integralCoefficientsAscending"));
            Objects.requireNonNull(integerContent, "integerContent");
            primitiveCoefficientsAscending = List.copyOf(
                Objects.requireNonNull(
                    primitiveCoefficientsAscending,
                    "primitiveCoefficientsAscending"));
            Objects.requireNonNull(scalar, "scalar");
        }
    }

    public record SerializedEvidence(
        String domainId,
        ExactRationalPolynomialContentNormalizer.Status status,
        String detailCode,
        List<String> sourceCoefficients,
        ExactRationalPolynomialContentNormalizer.Budget budget,
        Optional<SerializedNormalization> normalization,
        ExactRationalPolynomialContentEvidence.WorkLedger work,
        String certificateHash
    ) {
        public SerializedEvidence {
            Objects.requireNonNull(domainId, "domainId");
            Objects.requireNonNull(status, "status");
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "detailCode must not be blank");
            }
            sourceCoefficients = List.copyOf(
                Objects.requireNonNull(
                    sourceCoefficients,
                    "sourceCoefficients"));
            Objects.requireNonNull(budget, "budget");
            normalization = Objects.requireNonNull(
                normalization,
                "normalization");
            Objects.requireNonNull(work, "work");
            if (certificateHash == null
                    || !certificateHash.matches(
                        "sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "certificateHash must be SHA-256");
            }
        }
    }

    public record Verification(
        Status status,
        String detailCode
    ) {
        public Verification {
            Objects.requireNonNull(status, "status");
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "detailCode must not be blank");
            }
        }

        private static Verification verifiedNormalized() {
            return new Verification(
                Status.VERIFIED_NORMALIZED,
                "CONTENT_NORMALIZATION_REPLAY_VERIFIED");
        }

        private static Verification verifiedFailure() {
            return new Verification(
                Status.VERIFIED_FAILURE,
                "CONTENT_FAILURE_REPLAY_VERIFIED");
        }

        private static Verification rejected(String detailCode) {
            return new Verification(Status.REJECTED, detailCode);
        }

        public boolean verified() {
            return status != Status.REJECTED;
        }
    }
}
