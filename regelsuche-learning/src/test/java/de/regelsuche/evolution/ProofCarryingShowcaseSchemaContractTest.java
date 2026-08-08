package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class ProofCarryingShowcaseSchemaContractTest {
    @Test
    void schemasMatchTheStrictJavaArtifactVocabulary() {
        Path schemaRoot = ProofCarryingShowcaseTestFixtures
            .repositoryRoot()
            .resolve("docs/schemas");
        List<ExpectedSchema> schemas = List.of(
            new ExpectedSchema(
                "regelsuche-proof-carrying-showcase-plan-v1.schema.json",
                ProofCarryingShowcasePlan.SCHEMA),
            new ExpectedSchema(
                "regelsuche-proof-carrying-showcase-candidate-freeze-v1.schema.json",
                ProofCarryingShowcaseCandidateFreeze.SCHEMA),
            new ExpectedSchema(
                "regelsuche-proof-carrying-showcase-public-randomness-receipt-v1.schema.json",
                ProofCarryingShowcasePublicRandomnessReceipt.SCHEMA),
            new ExpectedSchema(
                "regelsuche-proof-carrying-showcase-seed-receipt-v1.schema.json",
                ProofCarryingShowcaseSeedReceipt.SCHEMA),
            new ExpectedSchema(
                "regelsuche-proof-carrying-showcase-generated-final-test-v1.schema.json",
                ProofCarryingShowcaseGeneratedFinalTest.SCHEMA));

        for (ExpectedSchema expected : schemas) {
            JsonNode schema = ProofCarryingShowcaseJsonSupport.readTree(
                schemaRoot.resolve(expected.fileName()),
                expected.fileName());
            assertEquals(
                "https://json-schema.org/draft/2020-12/schema",
                schema.path("$schema").asText());
            assertEquals(
                expected.runtimeSchema(),
                schema.path("$id").asText());
            requireClosedObject(schema, expected.fileName());
        }

        JsonNode generated =
            ProofCarryingShowcaseJsonSupport.readTree(
                schemaRoot.resolve(
                    "regelsuche-proof-carrying-showcase-generated-final-test-v1.schema.json"),
                "generated FINAL TEST schema");
        requireClosedObject(
            generated.path("$defs").path("familySummary"),
            "generated family summary");
        requireClosedObject(
            generated.path("$defs").path("case"),
            "generated case");
    }

    private static void requireClosedObject(
        JsonNode schema,
        String context
    ) {
        assertEquals("object", schema.path("type").asText(), context);
        assertFalse(
            schema.path("additionalProperties").asBoolean(true),
            context);
        JsonNode required = schema.path("required");
        JsonNode properties = schema.path("properties");
        assertTrue(required.isArray(), context);
        assertTrue(properties.isObject(), context);
        Set<String> requiredNames = StreamSupport.stream(
                required.spliterator(), false)
            .map(JsonNode::asText)
            .collect(Collectors.toSet());
        Set<String> propertyNames = new java.util.HashSet<>();
        properties.fieldNames().forEachRemaining(propertyNames::add);
        assertEquals(propertyNames, requiredNames, context);
    }

    private record ExpectedSchema(
        String fileName,
        String runtimeSchema
    ) {
    }
}
