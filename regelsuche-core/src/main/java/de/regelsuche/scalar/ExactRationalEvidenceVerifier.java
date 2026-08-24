package de.regelsuche.scalar;

import java.util.Objects;
import java.util.Optional;

/**
 * Replays serialized exact-rational evidence against its declared domain and
 * limits.
 *
 * <p>JSON Schema can constrain shapes and canonical spelling, but it cannot
 * prove greatest-common-divisor reduction or recompute content hashes. This
 * verifier performs those semantic checks. Over-limit failures retain only a
 * bounded prefix and are therefore verified as fail-closed failure shape, not
 * as a replay of the discarded suffix.</p>
 */
public final class ExactRationalEvidenceVerifier {

    public Verification verify(SerializedEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (!ExactRationalDomain.DOMAIN_ID.equals(evidence.domainId())) {
            return Verification.rejected("DOMAIN_ID_MISMATCH");
        }
        if (evidence.sourceLiteral().length()
                > evidence.limits().maxLiteralCharacters()) {
            return Verification.rejected(
                "SOURCE_LITERAL_EXCEEDS_DECLARED_LIMIT");
        }
        if (evidence.status() != ExactRationalDomain.Status.EXACT) {
            return verifyFailureShape(evidence);
        }

        ExactRationalParseEvidence replay =
            new ExactRationalDomain(evidence.limits())
                .parse(evidence.sourceLiteral());
        if (!replay.exact()) {
            return Verification.rejected(
                "EXACT_EVIDENCE_SOURCE_DOES_NOT_REPLAY");
        }
        if (!sameExactFields(evidence, replay)) {
            return Verification.rejected(
                "EXACT_EVIDENCE_REPLAY_MISMATCH");
        }
        return Verification.verified(
            replay.value().orElseThrow());
    }

    private Verification verifyFailureShape(
        SerializedEvidence evidence
    ) {
        if (!evidence.canonicalValue().isEmpty()
                || !evidence.valueId().isEmpty()
                || !evidence.certificateHash().isEmpty()) {
            return Verification.rejected(
                "FAILED_EVIDENCE_EXPOSES_EXACT_FIELDS");
        }
        if (!validFailureDetail(
                evidence.status(),
                evidence.detailCode())) {
            return Verification.rejected(
                "FAILED_EVIDENCE_DETAIL_MISMATCH");
        }
        return Verification.verifiedFailure();
    }

    private boolean sameExactFields(
        SerializedEvidence serialized,
        ExactRationalParseEvidence replay
    ) {
        return serialized.status() == replay.status()
            && serialized.detailCode().equals(replay.detailCode())
            && serialized.canonicalValue().equals(
                replay.canonicalValue())
            && serialized.valueId().equals(replay.valueId())
            && serialized.certificateHash().equals(
                replay.certificateHash());
    }

    private boolean validFailureDetail(
        ExactRationalDomain.Status status,
        String detailCode
    ) {
        return switch (status) {
            case UNSUPPORTED ->
                "LITERAL_BLANK".equals(detailCode)
                    || "LITERAL_GRAMMAR_UNSUPPORTED".equals(detailCode);
            case ZERO_DENOMINATOR ->
                "RATIONAL_DENOMINATOR_ZERO".equals(detailCode);
            case LIMIT_EXCEEDED ->
                detailCode.endsWith("_LIMIT_EXCEEDED");
            case EXACT -> false;
        };
    }

    public enum Status {
        VERIFIED_EXACT,
        VERIFIED_FAILURE_SHAPE,
        REJECTED
    }

    public record SerializedEvidence(
        String domainId,
        ExactRationalDomain.Status status,
        String detailCode,
        String sourceLiteral,
        ExactRationalDomain.Limits limits,
        String canonicalValue,
        String valueId,
        String certificateHash
    ) {
        public SerializedEvidence {
            Objects.requireNonNull(domainId, "domainId");
            Objects.requireNonNull(status, "status");
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "detailCode must not be blank");
            }
            Objects.requireNonNull(sourceLiteral, "sourceLiteral");
            Objects.requireNonNull(limits, "limits");
            Objects.requireNonNull(
                canonicalValue,
                "canonicalValue");
            Objects.requireNonNull(valueId, "valueId");
            Objects.requireNonNull(
                certificateHash,
                "certificateHash");
        }
    }

    public record Verification(
        Status status,
        String detailCode,
        Optional<ExactRational> value
    ) {
        public Verification {
            Objects.requireNonNull(status, "status");
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "detailCode must not be blank");
            }
            value = Objects.requireNonNull(value, "value");
            if (status == Status.VERIFIED_EXACT
                    && value.isEmpty()) {
                throw new IllegalArgumentException(
                    "verified exact evidence requires a value");
            }
            if (status != Status.VERIFIED_EXACT
                    && value.isPresent()) {
                throw new IllegalArgumentException(
                    "non-exact verification must not expose a value");
            }
        }

        private static Verification verified(
            ExactRational value
        ) {
            return new Verification(
                Status.VERIFIED_EXACT,
                "EXACT_EVIDENCE_REPLAY_VERIFIED",
                Optional.of(value));
        }

        private static Verification verifiedFailure() {
            return new Verification(
                Status.VERIFIED_FAILURE_SHAPE,
                "FAILURE_EVIDENCE_SHAPE_VERIFIED",
                Optional.empty());
        }

        private static Verification rejected(
            String detailCode
        ) {
            return new Verification(
                Status.REJECTED,
                detailCode,
                Optional.empty());
        }

        public boolean verified() {
            return status != Status.REJECTED;
        }
    }
}
