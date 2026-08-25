package de.regelsuche.scalar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class ExactRationalPolynomialContentJsonCodecTest {
    private final ExactRationalPolynomialContentJsonCodec codec =
        new ExactRationalPolynomialContentJsonCodec();

    @Test
    void roundTripsSuccessAndFailureEvidence() {
        var success = normalizedEvidence();
        String first = codec.write(success);
        var decoded = codec.readAndVerify(first);
        var failure = new ExactRationalPolynomialContentNormalizer()
            .normalize(ExactRationalPolynomial.of(ExactRational.ZERO));
        var decodedFailure = codec.readAndVerify(codec.write(failure));

        assertEquals(first, codec.write(success));
        assertEquals(success.serialized(), decoded.evidence());
        assertEquals(
            ExactRationalPolynomialContentVerifier.Status
                .VERIFIED_NORMALIZED,
            decoded.verification().status());
        assertEquals(
            ExactRationalPolynomialContentVerifier.Status.VERIFIED_FAILURE,
            decodedFailure.verification().status());
        assertTrue(decodedFailure.evidence().normalization().isEmpty());
    }

    @Test
    void jsonEnvelopeCoversEveryDeclaredV1NumericVector() {
        int sourceDigits = maximumDecimalDigits(
            ExactRationalPolynomialContentNormalizer.MAX_COEFFICIENT_BITS);
        int intermediateDigits = maximumDecimalDigits(
            ExactRationalPolynomialContentNormalizer.MAX_INTERMEDIATE_BITS);
        long sourceVectors = (long) ExactRationalPolynomial.MAX_COEFFICIENTS
            * (2L * sourceDigits + 2L);
        long normalizedVectors = 2L
            * ExactRationalPolynomial.MAX_COEFFICIENTS
            * (intermediateDigits + 1L);
        long scalarAndStructuralHeadroom =
            4L * (intermediateDigits + 1L) + 1_000_000L;
        long required = sourceVectors
            + normalizedVectors
            + scalarAndStructuralHeadroom;

        assertTrue(
            ExactRationalPolynomialContentJsonCodec.MAX_JSON_CHARACTERS
                >= required,
            () -> "v1 JSON envelope is smaller than its declared numeric "
                + "bounds: required=" + required);
    }

    @Test
    void rejectsStructuralAndSemanticTampering() {
        String json = codec.write(normalizedEvidence());
        String duplicate = json.replaceFirst(
            "\\{",
            "{\"domainId\":\"regelsuche.exact-rational-polynomial-content/v1\",");
        String unknown = json.replaceFirst(
            "\\{",
            "{\"unexpected\":true,");
        String impossibleBudget = json.replace(
            "\"maxIntermediateBits\":131072",
            "\"maxIntermediateBits\":1024");
        String scalar = json.replace(
            "\"scalar\":\"1/4\"",
            "\"scalar\":\"1/2\"");
        String work = json.replaceFirst(
            "\"totalSteps\":[0-9]+",
            "\"totalSteps\":0");
        String certificate = json.replaceFirst(
            "sha256:[0-9a-f]{64}",
            "sha256:" + "0".repeat(64));

        assertRejected(duplicate);
        assertRejected(unknown);
        assertRejected(json + "{}");
        assertRejected(impossibleBudget);
        assertRejected(scalar);
        assertRejected(work);
        assertRejected(certificate);
    }

    private static int maximumDecimalDigits(int bits) {
        return BigInteger.ONE.shiftLeft(bits)
            .subtract(BigInteger.ONE)
            .toString()
            .length();
    }

    private void assertRejected(String json) {
        assertThrows(
            IllegalArgumentException.class,
            () -> codec.readAndVerify(json));
    }

    private ExactRationalPolynomialContentEvidence normalizedEvidence() {
        return new ExactRationalPolynomialContentNormalizer().normalize(
            ExactRationalPolynomial.of(
                rational(1, 2),
                rational(-3, 4),
                rational(1, 4)));
    }

    private ExactRational rational(long numerator, long denominator) {
        return new ExactRational(
            BigInteger.valueOf(numerator),
            BigInteger.valueOf(denominator));
    }
}
