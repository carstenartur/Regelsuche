package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RuleInspectUiResourceTest {

    @Test
    void ruleIdeDisplaysFullExpressionAfter() throws IOException {
        try (var stream = getClass().getResourceAsStream("/web/app.js")) {
            assertNotNull(stream, "web/app.js resource must exist");
            String script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(script.contains("match.expressionAfter"), script);
            assertTrue(script.contains("Gesamtausdruck nachher"), script);
        }
    }
}
