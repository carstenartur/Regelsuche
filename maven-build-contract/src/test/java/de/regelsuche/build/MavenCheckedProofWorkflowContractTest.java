package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Real prover checks must be an enforced part of the existing CI, not a third workflow. */
class MavenCheckedProofWorkflowContractTest {
    @Test void checkedProofAuthorityIsRequiredAndExecutesRealTools() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(root.resolve(".github/workflows/gradle.yml"))) {
            root = root.getParent();
            assertNotNull(root, "repository workflow not found");
        }
        assertFalse(Files.exists(root.resolve(".github/workflows/checked-lean-proof.yml")));
        var yaml = new ObjectMapper(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
        var jobs = yaml.readTree(Files.readString(root.resolve(".github/workflows/gradle.yml"))).path("jobs");
        JsonNode proof = jobs.path("checked-proofs");
        assertTrue(proof.isObject(), "missing checked-proofs authority");
        assertEquals("github.event_name != 'create'", proof.path("if").asText());
        assertFalse(proof.has("continue-on-error"));
        assertEquals("true", proof.path("env").path("REGELSUCHE_REAL_LEAN").asText());
        assertTrue(proof.path("env").path("REGELSUCHE_LEAN_PROJECT").asText().endsWith("/.ci/lean-proof"));
        List<String> commands = new ArrayList<>();
        boolean retained = false;
        for (JsonNode step : proof.path("steps")) {
            assertFalse(step.has("continue-on-error"));
            if (step.has("run")) commands.add(step.path("run").asText());
            if (step.path("uses").asText().startsWith("actions/setup-java@"))
                assertTrue(step.path("with").path("verify-signature").asBoolean());
            if (step.path("uses").asText().startsWith("actions/upload-artifact@")) {
                assertTrue(step.path("if").asText().contains("always()"));
                assertTrue(step.path("with").path("path").asText().contains("app/target/checked-proof/"));
                assertTrue(step.path("with").path("include-hidden-files").asBoolean());
                retained = true;
            }
        }
        assertTrue(retained, "full evidence must survive failed runs");
        String commandsText = String.join("\n", commands);
        assertTrue(commandsText.contains("lake env lean --version"));
        assertTrue(commandsText.contains("lake exe cache get"));
        for (String test : List.of("CheckedLeanProofTest", "CheckedLeanRealTest", "CheckedProofBridgeRealTest",
                "ProofCacheAdmissionTest", "ProofJobSchedulerTest", "Z3SmtSolverBackendTest"))
            assertTrue(commandsText.contains(test), test);
        assertTrue(commandsText.contains("mvn -B -pl app -am"));
        assertFalse(commandsText.contains("-DskipTests"));
        assertFalse(commandsText.contains("|| true"));
        List<String> needs = new ArrayList<>();
        jobs.path("verification").path("needs").forEach(n -> needs.add(n.asText()));
        assertTrue(needs.contains("checked-proofs"));
        assertTrue(jobs.path("verification").path("steps").get(0).path("if").asText()
            .contains("needs.checked-proofs.result != 'success'"));
    }
}
