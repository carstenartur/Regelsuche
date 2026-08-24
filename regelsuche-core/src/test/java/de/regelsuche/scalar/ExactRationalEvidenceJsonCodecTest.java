package de.regelsuche.scalar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExactRationalEvidenceJsonCodecTest {
    private final ExactRationalEvidenceJsonCodec codec =
        new ExactRationalEvidenceJsonCodec();

    @Test
    void roundTripsCanonicalExactEvidenceDeterministically() {
        ExactRationalParseEvidence evidence =
            new ExactRationalDomain().parse("0.50");

        String first = codec.write(evidence);
        String second = codec.write(evidence);
        ExactRationalEvidenceJsonCodec.DecodedEvidence decoded =
            codec.readAndVerify(first);

        assertEquals(first, second);
        assertEquals(
            ExactRationalEvidenceVerifier.Status.VERIFIED_EXACT,
            decoded.verification().status());
        assertEquals(
            "1/2",
            decoded.evidence().canonicalValue());
        assertEquals(
            ExactRationalDomain.DEFAULT_LIMITS,
            decoded.evidence().limits());
    }

    @Test
    void roundTripsADeclaredFailureWithoutInventingExactFields() {
        ExactRationalParseEvidence evidence =
            new ExactRationalDomain().parse("NaN");

        ExactRationalEvidenceJsonCodec.DecodedEvidence decoded =
            codec.readAndVerify(codec.write(evidence));

        assertEquals(
            ExactRationalEvidenceVerifier.Status.VERIFIED_FAILURE,
            decoded.verification().status());
        assertTrue(decoded.evidence().canonicalValue().isEmpty());
        assertTrue(decoded.evidence().certificateHash().isEmpty());
    }

    @Test
    void rejectsDuplicateUnknownAndTrailingJsonContent() {
        String json = codec.write(
            new ExactRationalDomain().parse("1/2"));
        String duplicate = json.replaceFirst(
            "\\{",
            "{\"domainId\":\"regelsuche.exact-rational-scalar/v1\",");
        String unknown = json.replaceFirst(
            "\\{",
            "{\"unexpected\":true,");

        assertThrows(
            IllegalArgumentException.class,
            () -> codec.readAndVerify(duplicate));
        assertThrows(
            IllegalArgumentException.class,
            () -> codec.readAndVerify(unknown));
        assertThrows(
            IllegalArgumentException.class,
            () -> codec.readAndVerify(json + "{}"));
    }

    @Test
    void rejectsSchemaShapedButNoncanonicalOrReboundEvidence() {
        String json = codec.write(
            new ExactRationalDomain().parse("2/4"));
        String noncanonical = json.replace(
            "\"canonicalValue\":\"1/2\"",
            "\"canonicalValue\":\"2/4\"");
        String rebound = json.replace(
            "\"sourceLiteral\":\"2/4\"",
            "\"sourceLiteral\":\"3/6\"");

        assertThrows(
            IllegalArgumentException.class,
            () -> codec.readAndVerify(noncanonical));
        assertThrows(
            IllegalArgumentException.class,
            () -> codec.readAndVerify(rebound));
    }

    @Test
    void bindsCustomLimitsIntoCanonicalJsonAndCertificate() {
        ExactRationalDomain limited = new ExactRationalDomain(
            new ExactRationalDomain.Limits(20, 5, 2));
        ExactRationalParseEvidence evidence = limited.parse("0.50");
        String json = codec.write(evidence);

        ExactRationalEvidenceJsonCodec.DecodedEvidence decoded =
            codec.readAndVerify(json);

        assertEquals(20,
            decoded.evidence().limits().maxLiteralCharacters());
        assertEquals(5, decoded.evidence().limits().maxDigits());
        assertEquals(2,
            decoded.evidence().limits().maxDecimalScale());
    }
}
