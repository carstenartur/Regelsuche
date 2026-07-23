package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RuleInspectUiResourceTest {

    @Test
    void legacyRuleIdeDisplaysFullExpressionAfter() throws IOException {
        String script = resource("/web/app.js");
        assertTrue(script.contains("match.expressionAfter"), script);
        assertTrue(script.contains("Gesamtausdruck nachher"), script);
    }

    @Test
    void astRadarOnlyAppliesTheNewestInspectionResponse() throws IOException {
        String script = resource("/web/rule-radar.js");
        assertTrue(script.contains("inspectionSequence: 0"), script);
        assertTrue(script.contains("const requestSequence = ++state.inspectionSequence"), script);
        assertTrue(script.contains("requestSequence !== state.inspectionSequence"), script);
        assertTrue(script.contains("requestSequence === state.inspectionSequence"), script);
    }

    private String resource(String path) throws IOException {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream, path + " resource must exist");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
