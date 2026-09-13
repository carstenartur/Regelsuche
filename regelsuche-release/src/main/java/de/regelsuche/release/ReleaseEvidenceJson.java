package de.regelsuche.release;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Strict JSON and offline Draft 2020-12 validation of checkout-owned schemas. */
final class ReleaseEvidenceJson {
    static final String VALIDATOR_VERSION = "2.0.7";
    private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final SchemaRegistry registry;

    ReleaseEvidenceJson() throws IOException {
        var properties = new Properties();
        try (var resource = SchemaRegistry.class.getResourceAsStream("/META-INF/maven/com.networknt/json-schema-validator/pom.properties")) {
            if (resource == null) throw new IOException("schema validator version metadata is unavailable");
            properties.load(resource);
        }
        if (!VALIDATOR_VERSION.equals(properties.getProperty("version"))) throw new IOException("schema validator version drift");
        registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
            .schemaLoader(loader -> loader.fetchRemoteResources(false))
            .schemaRegistryConfig(SchemaRegistryConfig.builder().typeLoose(false).formatAssertionsEnabled(false).build()));
    }

    static JsonNode parse(byte[] bytes, String label) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        JsonNode node = JSON.readTree(text);
        if (node == null || !node.isObject()) throw new IOException("JSON root must be an object: " + label);
        return node;
    }

    void validate(JsonNode document, JsonNode schema, String label) {
        ReleaseReadinessBindings.require(DIALECT.equals(schema.path("$schema").asText()), "unsupported schema dialect: " + label);
        requireLocalReferences(schema);
        var metaErrors = registry.getSchema(SchemaLocation.of(DIALECT)).validate(schema);
        ReleaseReadinessBindings.require(metaErrors.isEmpty(), "invalid checkout schema " + label + ": " + metaErrors);
        // Existing checkout schemas use relative $id values. Supply a resolution context;
        // schema bytes stay unchanged and the loader cannot fetch this reserved-domain URI.
        var errors = registry.getSchema(SchemaLocation.of("https://regelsuche.invalid/checkout-schemas/"), schema).validate(document);
        ReleaseReadinessBindings.require(errors.isEmpty(), "schema violation for " + label + ": " + errors);
    }

    private static void requireLocalReferences(JsonNode node) {
        if (node.isObject()) {
            for (String keyword : java.util.List.of("$ref", "$dynamicRef")) {
                if (node.has(keyword)) ReleaseReadinessBindings.require(node.get(keyword).isTextual()
                    && node.get(keyword).textValue().startsWith("#/"), "external schema references are not admitted");
            }
            node.elements().forEachRemaining(ReleaseEvidenceJson::requireLocalReferences);
        } else if (node.isArray()) node.elements().forEachRemaining(ReleaseEvidenceJson::requireLocalReferences);
    }
}
