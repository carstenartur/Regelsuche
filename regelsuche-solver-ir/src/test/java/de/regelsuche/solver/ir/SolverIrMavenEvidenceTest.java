package de.regelsuche.solver.ir;

import static org.junit.jupiter.api.Assertions.*;

import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maven/JUnit-owned replay of the canonical Solver IR examples and their Draft 2020-12
 * schema and outcome contract. The historic Python/Gradle runner remains only until
 * independent raw-result and negative-case parity has been established.
 */
class SolverIrMavenEvidenceTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
    @TempDir Path temporary;

    @Test
    void freshGenerationIsDeterministicAndSchemasAndBackendOutcomesAreBound() throws Exception {
        Path first = temporary.resolve("first");
        Path second = temporary.resolve("second");
        SolverIrExampleMain.main(new String[] { first.toString() });
        SolverIrExampleMain.main(new String[] { second.toString() });

        var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
            .schemaLoader(loader -> loader.fetchRemoteResources(false))
            .schemaRegistryConfig(SchemaRegistryConfig.builder()
                .typeLoose(false).formatAssertionsEnabled(false).build()));
        var obligationSchema = schema(registry, "regelsuche-solver-obligation-v1.schema.json");
        var resultSchema = schema(registry, "regelsuche-solver-result-v1.schema.json");

        List<String> obligations = List.of("obligation.json", "assumption-obligation.json");
        List<String> results = List.of("regelsuche-search-result.json",
            "polynomial-normal-form-result.json", "unsupported-result.json");
        for (String file : obligations) {
            assertTrue(obligationSchema.validate(read(first.resolve(file))).isEmpty(), file);
        }
        for (String file : results) {
            assertTrue(resultSchema.validate(read(first.resolve(file))).isEmpty(), file);
        }
        for (String file : List.of("obligation.json", "assumption-obligation.json",
                "regelsuche-search-result.json", "polynomial-normal-form-result.json",
                "unsupported-result.json", "regelsuche-search-translation.json",
                "polynomial-normal-form-translation.json", "unsupported-translation.json",
                "regelsuche-search-execution.json", "polynomial-normal-form-execution.json",
                "unsupported-execution.json", "manifest.json")) {
            assertArrayEquals(Files.readAllBytes(first.resolve(file)),
                Files.readAllBytes(second.resolve(file)), "non-deterministic Solver IR example: " + file);
        }

        var obligation = read(first.resolve("obligation.json"));
        var assumed = read(first.resolve("assumption-obligation.json"));
        var search = read(first.resolve("regelsuche-search-result.json"));
        var polynomial = read(first.resolve("polynomial-normal-form-result.json"));
        var unsupported = read(first.resolve("unsupported-result.json"));
        var manifest = read(first.resolve("manifest.json"));
        checkOutcomes(obligation, assumed, search, polynomial, unsupported, manifest);
    }

    @Test
    void schemaAndSemanticGatesRejectSeparateKindsOfTampering() throws Exception {
        Path output = temporary.resolve("negative");
        SolverIrExampleMain.main(new String[] { output.toString() });
        var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
            .schemaLoader(loader -> loader.fetchRemoteResources(false))
            .schemaRegistryConfig(SchemaRegistryConfig.builder()
                .typeLoose(false).formatAssertionsEnabled(false).build()));
        var resultSchema = schema(registry, "regelsuche-solver-result-v1.schema.json");
        var polynomial = read(output.resolve("polynomial-normal-form-result.json"));
        var malformed = polynomial.deepCopy();
        malformed.put("status", "INVENTED_PROOF");
        assertFalse(resultSchema.validate(malformed).isEmpty(), "schema accepted a fabricated result status");
        var missing = polynomial.deepCopy();
        missing.remove("obligationHash");
        assertFalse(resultSchema.validate(missing).isEmpty(), "schema accepted an unbound result");

        var obligation = read(output.resolve("obligation.json"));
        var assumed = read(output.resolve("assumption-obligation.json"));
        var search = read(output.resolve("regelsuche-search-result.json"));
        var unsupported = read(output.resolve("unsupported-result.json"));
        var manifest = read(output.resolve("manifest.json"));

        var rebound = polynomial.deepCopy();
        rebound.put("obligationHash", assumed.path("contentHash").asText());
        assertTrue(resultSchema.validate(rebound).isEmpty(), "schema unexpectedly replaces semantic binding");
        assertThrows(AssertionError.class,
            () -> checkOutcomes(obligation, assumed, search, rebound, unsupported, manifest));

        var promoted = unsupported.deepCopy();
        promoted.put("status", "CONFIRMED");
        promoted.put("translationStatus", "LOSSLESS");
        assertTrue(resultSchema.validate(promoted).isEmpty(), "schema unexpectedly replaces outcome checks");
        assertThrows(AssertionError.class,
            () -> checkOutcomes(obligation, assumed, search, polynomial, promoted, manifest));
    }

    private static void checkOutcomes(ObjectNode obligation, ObjectNode assumed,
            ObjectNode search, ObjectNode polynomial, ObjectNode unsupported, ObjectNode manifest) {
        for (var confirmed : List.of(search, polynomial)) {
            assertEquals("CONFIRMED", confirmed.path("status").asText());
            assertEquals("LOSSLESS", confirmed.path("translationStatus").asText());
            assertEquals(obligation.path("contentHash").asText(), confirmed.path("obligationHash").asText());
        }
        assertEquals("polynomial-normal-form", polynomial.path("backendId").asText());
        assertEquals("matching deterministic polynomial normal form", polynomial.path("message").asText());
        assertTrue(contains(polynomial.path("usedCapabilities"),
            "DETERMINISTIC_POLYNOMIAL_NORMAL_FORM"));
        assertEquals("UNSUPPORTED", unsupported.path("status").asText());
        assertEquals("REJECTED", unsupported.path("translationStatus").asText());
        assertEquals(assumed.path("contentHash").asText(), unsupported.path("obligationHash").asText());
        assertTrue(contains(unsupported.path("translationIssues"), "ASSUMPTIONS_NOT_SUPPORTED"));
        assertTrue(manifest.path("sameObligationSubmittedToBothBackends").booleanValue());
        assertTrue(manifest.path("unsupportedBeforeExecution").booleanValue());
        assertEquals(obligation.path("contentHash").asText(), manifest.path("obligationHash").asText());
        assertEquals(assumed.path("contentHash").asText(), manifest.path("assumptionObligationHash").asText());
    }

    private static boolean contains(tools.jackson.databind.JsonNode array, String expected) {
        if (!array.isArray()) return false;
        for (var entry : array) if (expected.equals(entry.asText())) return true;
        return false;
    }

    private static ObjectNode read(Path path) throws Exception {
        var node = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
        assertInstanceOf(ObjectNode.class, node, "expected JSON object: " + path);
        return (ObjectNode) node;
    }

    private static com.networknt.schema.Schema schema(SchemaRegistry registry, String filename) throws Exception {
        Path root = Path.of("docs", "schemas");
        if (!Files.isDirectory(root)) root = Path.of("..", "docs", "schemas");
        var schema = read(root.resolve(filename));
        assertEquals(DIALECT, schema.path("$schema").asText(), filename);
        assertTrue(registry.getSchema(SchemaLocation.of(DIALECT)).validate(schema).isEmpty(),
            "invalid Draft 2020-12 schema: " + filename);
        return registry.getSchema(SchemaLocation.of("https://regelsuche.invalid/solver-ir-schemas/"), schema);
    }
}
