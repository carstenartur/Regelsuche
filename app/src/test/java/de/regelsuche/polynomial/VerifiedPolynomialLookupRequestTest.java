package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import de.regelsuche.polynomial.VerifiedPolynomialTransitionCacheStore.LookupRequest;
import org.junit.jupiter.api.Test;

class VerifiedPolynomialLookupRequestTest {
    private static final String EVIDENCE_HASH =
        "sha256:" + "0".repeat(64);
    private static final String OTHER_EVIDENCE_HASH =
        "sha256:" + "1".repeat(64);

    @Test
    void precomputesAndReusesTheExactLookupIdentity() {
        String source = "x".repeat(1_000_000);
        var request = new LookupRequest(
            "polynomial-factorization",
            "revision-1",
            EVIDENCE_HASH,
            source);

        String firstKeyId = request.keyId();
        var equalRequest = new LookupRequest(
            "polynomial-factorization",
            "revision-1",
            EVIDENCE_HASH,
            source);

        assertSame(firstKeyId, request.keyId());
        assertEquals(firstKeyId, equalRequest.keyId());
        assertEquals(request, equalRequest);
        assertEquals(request.hashCode(), equalRequest.hashCode());
        assertEquals(firstKeyId, request.canonicalMaterial());
    }

    @Test
    void exactTupleEqualityRejectsEveryChangedComponent() {
        var request = new LookupRequest(
            "polynomial-factorization",
            "revision-1",
            EVIDENCE_HASH,
            "x^2 - 1");

        assertNotEquals(request, new LookupRequest(
            "other-cache",
            "revision-1",
            EVIDENCE_HASH,
            "x^2 - 1"));
        assertNotEquals(request, new LookupRequest(
            "polynomial-factorization",
            "revision-2",
            EVIDENCE_HASH,
            "x^2 - 1"));
        assertNotEquals(request, new LookupRequest(
            "polynomial-factorization",
            "revision-1",
            OTHER_EVIDENCE_HASH,
            "x^2 - 1"));
        assertNotEquals(request, new LookupRequest(
            "polynomial-factorization",
            "revision-1",
            EVIDENCE_HASH,
            "x^2 + 1"));
    }
}
