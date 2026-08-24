package de.regelsuche.scalar;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable evidence issued by {@link ExactRationalDomain} after parsing a
 * source literal under its versioned, bounded contract.
 *
 * <p>The constructor is package-private so code outside the scalar package
 * cannot manufacture an exact status around arbitrary hashes or values.</p>
 */
public final class ExactRationalParseEvidence {
    private final String domainId;
    private final ExactRationalDomain.Status status;
    private final String detailCode;
    private final String sourceLiteral;
    private final Optional<ExactRational> value;
    private final String canonicalValue;
    private final String valueId;
    private final String certificateHash;

    ExactRationalParseEvidence(
        String domainId,
        ExactRationalDomain.Status status,
        String detailCode,
        String sourceLiteral,
        Optional<ExactRational> value,
        String canonicalValue,
        String valueId,
        String certificateHash
    ) {
        this.domainId = Objects.requireNonNull(domainId, "domainId");
        this.status = Objects.requireNonNull(status, "status");
        this.detailCode = requireText(detailCode, "detailCode");
        this.sourceLiteral = Objects.requireNonNull(
            sourceLiteral,
            "sourceLiteral");
        this.value = Objects.requireNonNull(value, "value");
        this.canonicalValue = Objects.requireNonNull(
            canonicalValue,
            "canonicalValue");
        this.valueId = Objects.requireNonNull(valueId, "valueId");
        this.certificateHash = Objects.requireNonNull(
            certificateHash,
            "certificateHash");
        validate();
    }

    static ExactRationalParseEvidence exact(
        String source,
        ExactRational value,
        String canonical,
        String valueId,
        String certificate
    ) {
        return new ExactRationalParseEvidence(
            ExactRationalDomain.DOMAIN_ID,
            ExactRationalDomain.Status.EXACT,
            "EXACT_RATIONAL_LITERAL_ACCEPTED",
            source,
            Optional.of(value),
            canonical,
            valueId,
            certificate);
    }

    static ExactRationalParseEvidence failure(
        ExactRationalDomain.Status status,
        String detailCode,
        String source
    ) {
        if (status == ExactRationalDomain.Status.EXACT) {
            throw new IllegalArgumentException(
                "failure evidence cannot use EXACT status");
        }
        return new ExactRationalParseEvidence(
            ExactRationalDomain.DOMAIN_ID,
            status,
            detailCode,
            source,
            Optional.empty(),
            "",
            "",
            "");
    }

    private void validate() {
        if (!ExactRationalDomain.DOMAIN_ID.equals(domainId)) {
            throw new IllegalArgumentException(
                "unexpected exact rational domain id");
        }
        if (status == ExactRationalDomain.Status.EXACT) {
            ExactRational exact = value.orElseThrow(() ->
                new IllegalArgumentException(
                    "exact evidence lacks a rational value"));
            if (!canonicalValue.equals(exact.canonicalText())
                    || !valueId.matches("sha256:[0-9a-f]{64}")
                    || !certificateHash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "exact evidence lacks canonical hashes");
            }
        } else if (value.isPresent()
                || !canonicalValue.isEmpty()
                || !valueId.isEmpty()
                || !certificateHash.isEmpty()) {
            throw new IllegalArgumentException(
                "failed parse must not expose exact evidence");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public String domainId() {
        return domainId;
    }

    public ExactRationalDomain.Status status() {
        return status;
    }

    public String detailCode() {
        return detailCode;
    }

    public String sourceLiteral() {
        return sourceLiteral;
    }

    public Optional<ExactRational> value() {
        return value;
    }

    public String canonicalValue() {
        return canonicalValue;
    }

    public String valueId() {
        return valueId;
    }

    public String certificateHash() {
        return certificateHash;
    }

    public boolean exact() {
        return status == ExactRationalDomain.Status.EXACT;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExactRationalParseEvidence evidence)) {
            return false;
        }
        return domainId.equals(evidence.domainId)
            && status == evidence.status
            && detailCode.equals(evidence.detailCode)
            && sourceLiteral.equals(evidence.sourceLiteral)
            && value.equals(evidence.value)
            && canonicalValue.equals(evidence.canonicalValue)
            && valueId.equals(evidence.valueId)
            && certificateHash.equals(evidence.certificateHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            domainId,
            status,
            detailCode,
            sourceLiteral,
            value,
            canonicalValue,
            valueId,
            certificateHash);
    }

    @Override
    public String toString() {
        return "ExactRationalParseEvidence[domainId=" + domainId
            + ", status=" + status
            + ", detailCode=" + detailCode
            + ", sourceLiteral=" + sourceLiteral
            + ", canonicalValue=" + canonicalValue
            + ", valueId=" + valueId
            + ", certificateHash=" + certificateHash
            + "]";
    }
}
