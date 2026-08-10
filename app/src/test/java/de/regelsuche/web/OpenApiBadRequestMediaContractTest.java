package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenApiBadRequestMediaContractTest {

    private static final Set<String> RULE_RADAR_JSON_OPERATIONS = Set.of(
        "POST /api/rule-radar/inspect",
        "POST /api/rule-radar/apply",
        "POST /api/rule-radar/search"
    );

    @Test
    void documentsTheMediaTypeActuallyProducedByEachApiSurface() throws IOException {
        Map<String, Object> document = specification();
        Map<String, Object> components = object(document.get("components"));
        Map<String, Object> responses = object(components.get("responses"));

        assertFalse(responses.containsKey("BadRequest"),
            "a combined 400 response would overpromise media types per operation");
        assertEquals(
            Set.of("text/plain"),
            object(object(responses.get("BadRequestText")).get("content")).keySet()
        );
        assertEquals(
            Set.of("application/json"),
            object(object(responses.get("BadRequestJson")).get("content")).keySet()
        );

        Map<String, String> observed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> pathEntry
                : object(document.get("paths")).entrySet()) {
            for (Map.Entry<String, Object> methodEntry
                    : object(pathEntry.getValue()).entrySet()) {
                Map<String, Object> operation = object(methodEntry.getValue());
                Map<String, Object> badRequest = object(
                    object(operation.get("responses")).get("400")
                );
                if (badRequest.isEmpty()) {
                    continue;
                }
                String operationKey = methodEntry.getKey().toUpperCase()
                    + " " + pathEntry.getKey();
                String expected = RULE_RADAR_JSON_OPERATIONS.contains(operationKey)
                    ? "#/components/responses/BadRequestJson"
                    : "#/components/responses/BadRequestText";
                String actual = String.valueOf(badRequest.get("$ref"));
                assertEquals(expected, actual, operationKey);
                observed.put(operationKey, actual);
            }
        }

        assertTrue(observed.keySet().containsAll(RULE_RADAR_JSON_OPERATIONS));
        assertTrue(observed.size() > RULE_RADAR_JSON_OPERATIONS.size(),
            "the text/plain Workbench surface must remain covered as well");
    }

    private Map<String, Object> specification() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(
                "/web/openapi/openapi.json")) {
            assertNotNull(input, "packaged OpenAPI specification must exist");
            return new StreamingJsonRequestBody(2 << 20).readObject(input);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map
            ? (Map<String, Object>) map
            : Map.of();
    }
}
