package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.ReferenceIndependentValidationFixtures.FREEZE;
import static de.regelsuche.discovery.representation.ReferenceIndependentValidationFixtures.FREEZE_HASH;
import static de.regelsuche.discovery.representation.ReferenceIndependentValidationFixtures.PLAN;
import static de.regelsuche.discovery.representation.ReferenceIndependentValidationFixtures.REVISION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Additive Maven/JUnit authority for the public reference-independent validation
 * companion exchange schema. The original historical qualification is never opened.
 * The legacy Python/Gradle gate remains until raw-artifact parity is established.
 */
class ReferenceIndependentValidationSchemaMavenTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

    @Test
    void completePublicFreezeHasAValidCompanionWithoutCallingAnOracle() throws Exception {
        var artifact = publicNoOracleCompanion();
        assertEquals(144, artifact.content().summary().rows());
        assertEquals(4316, artifact.content().summary().candidates());
        assertEquals(0, artifact.content().summary().oracleCalls());

        String canonical = artifact.toCanonicalJson();
        assertEquals(artifact,
            ReferenceIndependentCandidateValidation.Artifact.fromCanonicalJson(canonical));
        assertTrue(schema().validate(JSON.readTree(canonical)).isEmpty(),
            "complete public-frozen companion violates Draft 2020-12");
    }

    @Test
    void schemaSeparatelyRejectsReferenceClaimsProofPromotionAndInvalidBudgets() throws Exception {
        ObjectNode source = parse(publicNoOracleCompanion().toCanonicalJson());
        Schema contract = schema();

        var leakedLabel = source.deepCopy();
        firstOutcome(leakedLabel).put("referenceMatched", true);
        assertFalse(contract.validate(leakedLabel).isEmpty(),
            "reference labels must never enter the independent companion schema");

        var fakeProof = source.deepCopy();
        firstOutcome(fakeProof).put("formalProofStatus", "PROVED");
        assertFalse(contract.validate(fakeProof).isEmpty(),
            "schema must not accept a promotion from observation to formal proof");

        var zeroTimeout = source.deepCopy();
        ((ObjectNode) zeroTimeout.path("content").path("budget")).put("timeoutMillis", 0);
        assertFalse(contract.validate(zeroTimeout).isEmpty(),
            "zero oracle timeout is outside the contract");

        var falseFreshness = source.deepCopy();
        ((ObjectNode) falseFreshness.path("content")).put("evidenceScope", "FRESH_HELD_OUT");
        assertFalse(contract.validate(falseFreshness).isEmpty(),
            "archived public data cannot be relabelled as fresh held-out evidence");

        // The schema is a separate exchange-shape gate; it does not authorize
        // content-root or row/work semantic claims.
        var forgedHash = source.deepCopy();
        forgedHash.put("contentHash", "sha256:" + "0".repeat(64));
        assertTrue(contract.validate(forgedHash).isEmpty(),
            "shape validation is not a replacement for content-hash verification");
        assertThrows(IllegalArgumentException.class, () ->
            ReferenceIndependentCandidateValidation.Artifact.fromCanonicalJson(
                forgedHash.toString()), "the Java owner must reject a forged content root");
    }

    @Test
    void strictJavaOwnerRejectsDuplicateAndNonCanonicalInputBytes() {
        String original = publicNoOracleCompanion().toCanonicalJson();
        assertThrows(IllegalArgumentException.class, () ->
            ReferenceIndependentCandidateValidation.Artifact.fromCanonicalJson(" " + original));
        String duplicate = original.replace(
            "\"contentHash\":", "\"contentHash\":\"sha256:" + "0".repeat(64) + "\",\"contentHash\":");
        assertFalse(duplicate.equals(original), "duplicate-member negative must mutate the source");
        assertThrows(IllegalArgumentException.class, () ->
            ReferenceIndependentCandidateValidation.Artifact.fromCanonicalJson(duplicate));
    }

    private static ReferenceIndependentCandidateValidation.Artifact publicNoOracleCompanion() {
        return ReferenceIndependentCandidateValidationRunner.run(
            PLAN, FREEZE, FREEZE_HASH, REVISION,
            new ReferenceIndependentCandidateValidation.Budget(0, 8192, 5000),
            (left, right) -> {
                throw new AssertionError("zero-budget schema checks must not invoke an oracle");
            });
    }

    private static ObjectNode firstOutcome(ObjectNode document) {
        for (JsonNode row : document.path("content").path("rows")) {
            var outcomes = row.path("outcomes");
            if (outcomes.size() > 0) {
                return (ObjectNode) outcomes.get(0);
            }
        }
        throw new AssertionError("the archived public matrix has no retained candidate");
    }

    private static ObjectNode parse(String canonical) throws Exception {
        return (ObjectNode) JSON.readTree(canonical);
    }

    private static Schema schema() throws Exception {
        Path path = Path.of("docs", "schemas",
            "regelsuche-reference-independent-candidate-validation-v1.schema.json");
        if (!Files.isRegularFile(path)) {
            path = Path.of("..", "docs", "schemas",
                "regelsuche-reference-independent-candidate-validation-v1.schema.json");
        }
        var source = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
        assertEquals(DIALECT, source.path("$schema").asText());
        var registry = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12, builder -> builder
                .schemaLoader(loader -> loader.fetchRemoteResources(false))
                .schemaRegistryConfig(SchemaRegistryConfig.builder()
                    .typeLoose(false).formatAssertionsEnabled(false).build()));
        assertTrue(registry.getSchema(SchemaLocation.of(DIALECT)).validate(source).isEmpty(),
            "exchange schema is not a valid Draft 2020-12 schema");
        return registry.getSchema(
            SchemaLocation.of("https://regelsuche.invalid/reference-independent-v1/"), source);
    }
}
