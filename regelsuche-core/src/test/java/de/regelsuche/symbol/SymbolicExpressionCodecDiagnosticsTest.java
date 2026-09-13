package de.regelsuche.symbol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SymbolicExpressionCodecDiagnosticsTest {
    private final SymbolicExpressionCodec codec = new SymbolicExpressionCodec();

    @Test
    void malformedUtf8RetainsItsEncodingDiagnosticAndCause() {
        var failure = assertThrows(IllegalArgumentException.class,
            () -> codec.decode(new byte[] {(byte) 0xc3, 0x28}));
        assertEquals("invalid UTF-8 symbolic JSON document", failure.getMessage());
        assertInstanceOf(CharacterCodingException.class, failure.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"schema\":1,\"schema\":2}", "{} {}"})
    void validUtf8WithInvalidJsonReportsJsonRatherThanAnEncodingFailure(String text) {
        var failure = assertThrows(IllegalArgumentException.class,
            () -> codec.decode(text.getBytes(StandardCharsets.UTF_8)));
        assertEquals("invalid symbolic JSON document", failure.getMessage());
        assertInstanceOf(JsonProcessingException.class, failure.getCause());
    }

    @Test
    void semanticDocumentFailuresRetainTheirSpecificDiagnostic() {
        var failure = assertThrows(IllegalArgumentException.class,
            () -> codec.decode("{}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("symbolic document has missing or unknown fields", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void validUnicodeRetainsCanonicalBytesAndSymbolIdentity() {
        var document = SymbolicExpression.parse("α+α+0.25", new SymbolScope(new UUID(0, 17)));
        var renamed = document.withDisplayName(document.sourceBindings().get("α"), "Winkel");
        byte[] encoded = codec.encode(renamed);
        var decoded = codec.decode(encoded);
        assertEquals(renamed.expression(), decoded.expression());
        assertEquals(renamed.sourceBindings(), decoded.sourceBindings());
        assertEquals(renamed.displayNames(), decoded.displayNames());
        assertEquals("α+α+0.25", decoded.original().source());
        assertArrayEquals(encoded, codec.encode(decoded));
    }
}
