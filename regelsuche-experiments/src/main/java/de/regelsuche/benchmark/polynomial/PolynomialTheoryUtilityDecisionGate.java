package de.regelsuche.benchmark.polynomial;

import de.regelsuche.json.JsonWriter;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.util.Objects;

/** Verifies detached authorizations from the external custodian and study reviewer. */
public final class PolynomialTheoryUtilityDecisionGate {
    public static final String RELEASE_SCHEMA = "regelsuche.polynomial-theory-utility-qualification-release/v1";
    public static final String DECISION_SCHEMA = "regelsuche.polynomial-theory-utility-decision/v1";

    private static final String ALGORITHM = NamedParameterSpec.ED25519.getName();
    private static final int SIGNATURE_BYTES = 64;

    private final EdECPublicKey custodian;
    private final EdECPublicKey reviewer;

    /** Both trust anchors must be provisioned independently of the submitted evidence. */
    public PolynomialTheoryUtilityDecisionGate(PublicKey custodian, PublicKey reviewer) {
        this.custodian = requireEd25519(custodian);
        this.reviewer = requireEd25519(reviewer);
        var custodianPoint = this.custodian.getPoint();
        var reviewerPoint = this.reviewer.getPoint();
        if (custodianPoint.getY().equals(reviewerPoint.getY())
                && custodianPoint.isXOdd() == reviewerPoint.isXOdd()) {
            throw new IllegalArgumentException("custody and review require distinct trust anchors");
        }
    }

    /** The custodian signs this payload to release the pinned qualification for the exact candidate freeze. */
    public static byte[] releasePayload(PolynomialTheoryUtilityCandidateFreeze freeze) {
        Objects.requireNonNull(freeze, "freeze");
        if (!PolynomialTheoryUtilityCandidateFreeze.OBSERVED_SCHEMA.equals(freeze.schema())) {
            throw new IllegalArgumentException("qualification release requires an observed candidate freeze");
        }
        return canonicalBytes(new JsonWriter().beginObject()
            .property("schema", RELEASE_SCHEMA)
            .property("studyId", freeze.studyId())
            .property("preregistrationHash", PolynomialTheoryUtilityPreregistration.CONTENT_HASH)
            .property("candidateFreezeSchema", freeze.schema())
            .property("candidateFreezeHash", freeze.contentHash())
            .property("candidateFreezeLength", freeze.byteLength())
            .property("qualificationPath", PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_FILE_NAME)
            .property("qualificationHash", PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_CONTENT_HASH)
            .property("qualificationLength", PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_BYTE_LENGTH));
    }

    /** Verifies the exact authorized freeze and sealed corpus identity without accessing the corpus. */
    public void requireReleased(PolynomialTheoryUtilityCandidateFreeze freeze, byte[] releaseSignature) {
        requireSignature(custodian, releasePayload(freeze), releaseSignature, "qualification release");
    }

    /** The reviewer signs the preregistered outcome for the exact release and comparison evidence. */
    public static byte[] decisionPayload(PolynomialTheoryUtilityCandidateFreeze freeze, byte[] releaseSignature,
            String outcome, byte[] comparisonEvidence) {
        if (outcome == null || !PolynomialTheoryUtilityPreregistration.DECISION_OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("decision outcome is not preregistered");
        }
        if (comparisonEvidence == null || comparisonEvidence.length == 0) {
            throw new IllegalArgumentException("comparison evidence is required");
        }
        return canonicalBytes(new JsonWriter().beginObject()
            .property("schema", DECISION_SCHEMA)
            .property("releasePayloadHash", PolynomialTheoryUtilityExecutionIdentity.sha256(releasePayload(freeze)))
            .property("releaseSignatureHash", PolynomialTheoryUtilityExecutionIdentity.sha256(
                requireSignatureLength(releaseSignature)))
            .property("comparisonHash", PolynomialTheoryUtilityExecutionIdentity.sha256(comparisonEvidence))
            .property("comparisonLength", comparisonEvidence.length)
            .property("outcome", outcome));
    }

    /** Returns the outcome only after both independent signatures have been verified. */
    public String requireAuthorized(PolynomialTheoryUtilityCandidateFreeze freeze, byte[] releaseSignature,
            String outcome, byte[] comparisonEvidence, byte[] decisionSignature) {
        byte[] release = requireSignatureLength(releaseSignature).clone();
        requireReleased(freeze, release);
        byte[] decision = decisionPayload(freeze, release, outcome, comparisonEvidence);
        requireSignature(reviewer, decision, decisionSignature, "study decision");
        return outcome;
    }

    private static byte[] canonicalBytes(JsonWriter json) {
        return (json.endObject() + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static EdECPublicKey requireEd25519(PublicKey key) {
        if (key instanceof EdECPublicKey edwards && ALGORITHM.equals(edwards.getParams().getName())) {
            return edwards;
        }
        throw new IllegalArgumentException("an Ed25519 trust anchor is required");
    }

    private static byte[] requireSignatureLength(byte[] signature) {
        if (signature == null || signature.length != SIGNATURE_BYTES) {
            throw new IllegalArgumentException("an Ed25519 signature must contain 64 bytes");
        }
        return signature;
    }

    private static void requireSignature(PublicKey key, byte[] payload, byte[] signature, String purpose) {
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key);
            verifier.update(payload);
            if (!verifier.verify(requireSignatureLength(signature))) {
                throw new IllegalArgumentException("invalid signature for " + purpose);
            }
        } catch (GeneralSecurityException cause) {
            throw new IllegalArgumentException("could not verify signature for " + purpose, cause);
        }
    }
}
