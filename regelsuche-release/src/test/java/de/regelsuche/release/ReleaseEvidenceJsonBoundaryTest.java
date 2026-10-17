package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReleaseEvidenceJsonBoundaryTest {
    private static final String SCHEMA = """
        {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
         "required":["n"],"additionalProperties":false,"properties":{"n":%s}}
        """;

    @Test void adjacentLargeIntegersRemainDistinctAcrossTheValidatorBoundary() throws Exception {
        var schema = parse(SCHEMA.formatted("{\"const\":9007199254740993}"));
        var validator = new ReleaseEvidenceJson();
        assertDoesNotThrow(() -> validator.validate(parse("{\"n\":9007199254740993}"), schema, "exact"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(parse("{\"n\":9007199254740992}"), schema, "neighbor"));
    }

    @Test void decimalBoundsAreNotRoundedAtTheParserBoundary() throws Exception {
        var schema = parse(SCHEMA.formatted("{\"type\":\"number\",\"maximum\":0.1000000000000000000000000000000000000000000001}"));
        var validator = new ReleaseEvidenceJson();
        assertDoesNotThrow(() -> validator.validate(parse("{\"n\":0.1000000000000000000000000000000000000000000001}"), schema, "exact"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(parse("{\"n\":0.1000000000000000000000000000000000000000000002}"), schema, "rounded-neighbor"));
    }

    @Test void strictTypesAndClosedObjectSchemasRemainEnforced() throws Exception {
        var schema = parse(SCHEMA.formatted("{\"type\":\"integer\"}"));
        var validator = new ReleaseEvidenceJson();
        assertDoesNotThrow(() -> validator.validate(parse("{\"n\":1}"), schema, "integer"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(parse("{\"n\":\"1\"}"), schema, "string"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(parse("{\"n\":1,\"extra\":true}"), schema, "extra"));
    }

    private static com.fasterxml.jackson.databind.JsonNode parse(String text) throws Exception {
        return ReleaseEvidenceJson.parse(text.getBytes(StandardCharsets.UTF_8), "fixture");
    }
}
