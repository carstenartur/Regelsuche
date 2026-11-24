package de.regelsuche.benchmark.polynomial;

import static de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityDecisionGate.decisionPayload;
import static de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityDecisionGate.releasePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.json.JsonReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityDecisionGateTest {
    private static final String OUTCOME = "KEEP_OPT_IN";
    private static final byte[] COMPARISON = "synthetic comparison evidence".getBytes(StandardCharsets.UTF_8);
    private static final PolynomialTheoryUtilityCandidateFreeze FREEZE = freeze(true, 0);

    private final KeyPair custodian = keyPair("Ed25519");
    private final KeyPair reviewer = keyPair("Ed25519");
    private final PolynomialTheoryUtilityDecisionGate gate =
        new PolynomialTheoryUtilityDecisionGate(custodian.getPublic(), reviewer.getPublic());
    private final byte[] release = sign(custodian, releasePayload(FREEZE));

    @Test
    void acceptsEachPreregisteredDecisionWithBothBoundSignatures() {
        gate.requireReleased(FREEZE, release);
        for (String outcome : PolynomialTheoryUtilityPreregistration.DECISION_OUTCOMES) {
            byte[] decision = sign(reviewer, decisionPayload(FREEZE, release, outcome, COMPARISON));
            assertEquals(outcome, gate.requireAuthorized(FREEZE, release, outcome, COMPARISON, decision));
        }
    }

    @Test
    void releasePayloadCommitsToTheSameSealedIdentityAsTheObservedFreeze() {
        var payload = new JsonReader(new String(releasePayload(FREEZE), StandardCharsets.UTF_8)).readObject();
        var canonicalFreeze = new JsonReader(FREEZE.canonicalJson()).readObject();
        var sealed = (Map<?, ?>) canonicalFreeze.get("qualificationBinding");
        assertEquals(sealed.get("path"), payload.get("qualificationPath"));
        assertEquals(sealed.get("byteLength"), payload.get("qualificationLength"));
        assertEquals(sealed.get("contentHash"), payload.get("qualificationHash"));
        assertEquals(FREEZE.contentHash(), payload.get("candidateFreezeHash"));
    }

    @Test
    void rejectsHistoricalAndDifferentObservedFreezes() {
        assertThrows(IllegalArgumentException.class, () -> gate.requireReleased(freeze(false, 0), release));
        var foreignFreeze = freeze(true, 1);
        assertThrows(IllegalArgumentException.class, () -> gate.requireReleased(foreignFreeze, release));
        byte[] foreignRelease = sign(custodian, releasePayload(foreignFreeze));
        byte[] decision = sign(reviewer, decisionPayload(FREEZE, release, OUTCOME, COMPARISON));
        assertThrows(IllegalArgumentException.class,
            () -> gate.requireAuthorized(foreignFreeze, foreignRelease, OUTCOME, COMPARISON, decision));
    }

    @Test
    void rejectsMissingForeignAndRoleSwappedSignatures() {
        byte[] decisionPayload = decisionPayload(FREEZE, release, OUTCOME, COMPARISON);
        assertThrows(IllegalArgumentException.class, () -> gate.requireReleased(FREEZE, null));
        assertThrows(IllegalArgumentException.class, () -> gate.requireReleased(FREEZE, new byte[63]));
        assertThrows(IllegalArgumentException.class,
            () -> gate.requireReleased(FREEZE, sign(reviewer, releasePayload(FREEZE))));
        assertThrows(IllegalArgumentException.class,
            () -> gate.requireReleased(FREEZE, sign(keyPair("Ed25519"), releasePayload(FREEZE))));
        assertThrows(IllegalArgumentException.class,
            () -> gate.requireAuthorized(FREEZE, release, OUTCOME, COMPARISON, null));
        assertThrows(IllegalArgumentException.class,
            () -> gate.requireAuthorized(FREEZE, release, OUTCOME, COMPARISON, sign(custodian, decisionPayload)));
    }

    @Test
    void rejectsChangedComparisonOrOutcomeAndUnregisteredOutcomes() {
        byte[] decision = sign(reviewer, decisionPayload(FREEZE, release, OUTCOME, COMPARISON));
        byte[] amendedComparison = COMPARISON.clone();
        amendedComparison[0] ^= 1;
        assertThrows(IllegalArgumentException.class,
            () -> gate.requireAuthorized(FREEZE, release, OUTCOME, amendedComparison, decision));
        assertThrows(IllegalArgumentException.class,
            () -> gate.requireAuthorized(FREEZE, release, OUTCOME, new byte[0], decision));
        assertThrows(IllegalArgumentException.class,
            () -> gate.requireAuthorized(FREEZE, release, OUTCOME, null, decision));
        assertThrows(IllegalArgumentException.class,
            () -> gate.requireAuthorized(FREEZE, release, "HYBRID_POLICY_JUSTIFIED", COMPARISON, decision));
        assertThrows(IllegalArgumentException.class,
            () -> gate.requireAuthorized(FREEZE, release, "UNKNOWN_OUTCOME", COMPARISON, decision));
        assertThrows(IllegalArgumentException.class,
            () -> gate.requireAuthorized(FREEZE, release, null, COMPARISON, decision));
    }

    @Test
    void rejectsDifferentSchemasAndDoesNotInferTrustFromValidSignatures() {
        byte[] wrongVersion = new String(releasePayload(FREEZE), StandardCharsets.UTF_8)
            .replace("/v1", "/v2").getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> gate.requireReleased(FREEZE, sign(custodian, wrongVersion)));
        assertThrows(IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityDecisionGate(custodian.getPublic(), custodian.getPublic()));
        assertThrows(IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityDecisionGate(custodian.getPublic(), keyPair("Ed448").getPublic()));
    }

    private static PolynomialTheoryUtilityCandidateFreeze freeze(boolean observed, long firstRawUnits) {
        return PolynomialTheoryUtilityCandidateFreeze.create(
            PolynomialTheoryUtilityObservedFreezeContractTest.batch(observed, null, firstRawUnits));
    }

    private static KeyPair keyPair(String algorithm) {
        try {
            return KeyPairGenerator.getInstance(algorithm).generateKeyPair();
        } catch (GeneralSecurityException cause) {
            throw new AssertionError(cause);
        }
    }

    private static byte[] sign(KeyPair signer, byte[] payload) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(signer.getPrivate());
            signature.update(payload);
            return signature.sign();
        } catch (GeneralSecurityException cause) {
            throw new AssertionError(cause);
        }
    }
}
