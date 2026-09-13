package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** JDK signature failures must still fail CI after setup-java changed its default. */
class MavenJavaDistributionSignatureContractTest {
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    // Retained study environments use v6.0.0, whose omitted input already fails closed.
    private static final String STRICT_DEFAULT_ACTION =
        "actions/setup-java@dd06d9cba3e5552c54d9f8ea23572deb30010f7c";

    @Test
    void everyJavaSetupRejectsSignatureFailures() throws IOException {
        List<JavaSetup> setups = repositorySetups();
        assertTrue(setups.size() >= 6, "ordinary CI and release JDK setups must be covered");
        for (JavaSetup setup : setups) {
            assertTrue(enforcesSignatures(setup.step()), setup.location());
        }
    }

    @Test
    void missingFalseAndDynamicInputsCannotSilentlyWeakenUpdatedActions() throws IOException {
        int updatedSetups = 0;
        for (JavaSetup setup : repositorySetups()) {
            if (STRICT_DEFAULT_ACTION.equals(setup.step().path("uses").asText())) continue;
            updatedSetups++;
            ObjectNode step = setup.step().deepCopy();
            ObjectNode inputs = step.withObject("with");
            inputs.remove("verify-signature");
            assertFalse(enforcesSignatures(step), setup.location() + " omitted input");
            inputs.put("verify-signature", false);
            assertFalse(enforcesSignatures(step), setup.location() + " false input");
            inputs.put("verify-signature", "${{ inputs.verify_signature }}");
            assertFalse(enforcesSignatures(step), setup.location() + " dynamic input");
            inputs.put("verify-signature", true);
            assertTrue(enforcesSignatures(step), setup.location() + " explicit strict input");
        }
        assertTrue(updatedSetups >= 6, "updated CI and release actions must be exercised");
    }

    @Test
    void onlyTheRetainedStrictVersionMayOmitTheInput() {
        ObjectNode step = YAML.createObjectNode().put("uses", STRICT_DEFAULT_ACTION);
        assertTrue(enforcesSignatures(step));
        step.putObject("with").put("verify-signature", false);
        assertFalse(enforcesSignatures(step));
        step.withObject("with").remove("verify-signature");
        step.put("uses", "actions/setup-java@unreviewed-successor");
        assertFalse(enforcesSignatures(step));
        step.withObject("with").put("verify-signature", "true");
        assertTrue(enforcesSignatures(step));
    }

    private static boolean enforcesSignatures(JsonNode step) {
        JsonNode input = step.path("with").path("verify-signature");
        return input.isMissingNode()
            ? STRICT_DEFAULT_ACTION.equals(step.path("uses").asText())
            : input.isBoolean() && input.booleanValue()
                || input.isTextual() && "true".equals(input.textValue());
    }

    private static List<JavaSetup> repositorySetups() throws IOException {
        List<JavaSetup> result = new ArrayList<>();
        try (var paths = Files.list(MavenPomTestSupport.repositoryRoot().resolve(".github/workflows"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".yml")
                    || p.toString().endsWith(".yaml")).sorted().toList()) {
                JsonNode workflow = YAML.readTree(path.toFile());
                for (var job : workflow.path("jobs").properties()) {
                    int index = 0;
                    for (JsonNode step : job.getValue().path("steps")) {
                        if (step.path("uses").asText().startsWith("actions/setup-java@")) {
                            assertEquals("temurin", step.path("with").path("distribution").asText());
                            result.add(new JavaSetup(path.getFileName() + "/" + job.getKey()
                                + "/steps/" + index, (ObjectNode) step));
                        }
                        index++;
                    }
                }
            }
        }
        return result;
    }

    private record JavaSetup(String location, ObjectNode step) { }
}
