package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.regelsuche.input.InputType;
import de.regelsuche.scoring.cost.TransformationGoal;
import de.regelsuche.search.SearchProfile;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Ensures the public search request reference cannot drift from runtime enums. */
class OpenApiSearchRequestEnumContractTest {
    @Test
    void searchRequestEnumsExactlyMatchRuntimeEnums() throws IOException {
        Map<String, Object> schemas = object(
            object(specification().get("components")).get("schemas")
        );
        Map<String, Object> properties = object(
            object(schemas.get("SearchRequest")).get("properties")
        );

        assertEquals(
            names(InputType.values()),
            strings(object(properties.get("type")).get("enum")),
            "OpenAPI input types must exactly match InputType"
        );
        assertEquals(
            names(SearchProfile.values()),
            strings(object(properties.get("profile")).get("enum")),
            "OpenAPI search profiles must exactly match SearchProfile"
        );
        assertEquals(
            names(TransformationGoal.values()),
            strings(object(properties.get("goal")).get("enum")),
            "OpenAPI goals must exactly match TransformationGoal"
        );
    }

    private Map<String, Object> specification() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(
            "/web/openapi/openapi.json"
        )) {
            assertNotNull(stream, "packaged OpenAPI specification must exist");
            return new StreamingJsonRequestBody(2 << 20).readObject(stream);
        }
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values)
            .map(Enum::name)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> strings(Object value) {
        return list(value).stream()
            .map(String::valueOf)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map
            ? (Map<String, Object>) map
            : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> values
            ? (List<Object>) values
            : List.of();
    }
}
