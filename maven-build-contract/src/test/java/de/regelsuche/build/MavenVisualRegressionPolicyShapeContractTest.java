package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class MavenVisualRegressionPolicyShapeContractTest {
    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
    );
    private static final String SCHEMA =
        "regelsuche.visual-regression-policy/v1";
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
        "schema",
        "environment",
        "comparison",
        "baselines"
    );
    private static final Set<String> ENVIRONMENT_FIELDS = Set.of(
        "containerImage",
        "playwrightVersion",
        "browser",
        "viewportWidth",
        "viewportHeight",
        "deviceScaleFactor",
        "locale",
        "timezoneId"
    );

    @Test
    void repositoryVisualPolicyHasTheExactVersionedShape()
            throws IOException {
        verify(parseStrict(repositoryRoot().resolve(
            "app/src/e2eTest/resources/screenshots/visual-regression-policy.json"
        )));
    }

    @Test
    void topLevelAndEnvironmentDriftFailClosed() {
        ObjectNode unknownTopLevel = validPolicy();
        unknownTopLevel.put("unexpected", true);
        expectFailure(unknownTopLevel, "visual regression policy unknown=");

        ObjectNode missingTopLevel = validPolicy();
        missingTopLevel.remove("comparison");
        expectFailure(missingTopLevel, "missing=[comparison]");

        ObjectNode unknownEnvironment = validPolicy();
        ((ObjectNode) unknownEnvironment.get("environment"))
            .put("unexpected", true);
        expectFailure(unknownEnvironment, "visual regression environment unknown=");

        ObjectNode missingEnvironment = validPolicy();
        ((ObjectNode) missingEnvironment.get("environment"))
            .remove("timezoneId");
        expectFailure(missingEnvironment, "missing=[timezoneId]");

        ObjectNode wrongSchema = validPolicy();
        wrongSchema.put("schema", "unsupported");
        expectFailure(wrongSchema, "unsupported visual regression policy schema");
    }

    private static void verify(JsonNode value) {
        ObjectNode policy = requireObject(value, "visual regression policy");
        requireExactFields(policy, TOP_LEVEL_FIELDS, "visual regression policy");
        JsonNode schema = policy.get("schema");
        if (schema == null || !schema.isTextual()
                || !SCHEMA.equals(schema.asText())) {
            throw invalid("unsupported visual regression policy schema");
        }
        ObjectNode environment = requireObject(
            policy.get("environment"),
            "visual regression environment"
        );
        requireExactFields(
            environment,
            ENVIRONMENT_FIELDS,
            "visual regression environment"
        );
        requireObject(policy.get("comparison"), "visual regression comparison");
        requireObject(policy.get("baselines"), "visual regression baselines");
    }

    private static JsonNode parseStrict(Path path) throws IOException {
        try (JsonParser parser = JSON.getFactory().createParser(path.toFile())) {
            JsonNode document = JSON.readTree(parser);
            if (document == null) {
                throw invalid("empty JSON document: " + path);
            }
            if (parser.nextToken() != null) {
                throw invalid("trailing JSON content: " + path);
            }
            return document;
        } catch (JsonProcessingException exception) {
            throw invalid(
                "invalid JSON " + path + ": "
                    + exception.getOriginalMessage(),
                exception
            );
        }
    }

    private static ObjectNode requireObject(
        JsonNode value,
        String context
    ) {
        if (!(value instanceof ObjectNode object)) {
            throw invalid(context + " must be a JSON object");
        }
        return object;
    }

    private static void requireExactFields(
        ObjectNode value,
        Set<String> expected,
        String context
    ) {
        Set<String> actual = new TreeSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        Set<String> unknown = new TreeSet<>(actual);
        unknown.removeAll(expected);
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        if (!unknown.isEmpty() || !missing.isEmpty()) {
            throw invalid(
                context + " unknown=" + unknown + " missing=" + missing
            );
        }
    }

    private static ObjectNode validPolicy() {
        ObjectNode policy = JSON.createObjectNode();
        policy.put("schema", SCHEMA);
        ObjectNode environment = policy.putObject("environment");
        environment.put("containerImage", "example/image:v1.2.3");
        environment.put("playwrightVersion", "1.2.3");
        environment.put("browser", "chromium");
        environment.put("viewportWidth", 1400);
        environment.put("viewportHeight", 900);
        environment.put("deviceScaleFactor", 1.0);
        environment.put("locale", "en-US");
        environment.put("timezoneId", "UTC");
        policy.putObject("comparison");
        policy.putObject("baselines");
        return policy;
    }

    private static void expectFailure(JsonNode value, String fragment) {
        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> verify(value)
        );
        assertTrue(
            failure.getMessage().contains(fragment),
            () -> "expected <" + fragment + "> in <"
                + failure.getMessage() + ">"
        );
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty("regelsuche.repositoryRoot");
        if (configured == null || configured.isBlank()) {
            throw new AssertionError(
                "Maven must expose maven.multiModuleProjectDirectory to tests"
            );
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(
            "visual regression policy invalid: " + message
        );
    }

    private static IllegalArgumentException invalid(
        String message,
        Throwable cause
    ) {
        return new IllegalArgumentException(
            "visual regression policy invalid: " + message,
            cause
        );
    }
}
