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
import java.util.Set;
import java.util.stream.Collectors;
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
        List<String> commands = new ArrayList<>();
        boolean retained = false;
        for (JsonNode step : proof.path("steps")) {
            assertFalse(step.has("continue-on-error"));
            if (step.has("run")) commands.add(step.path("run").asText());
            if (step.path("uses").asText().startsWith("actions/setup-java@")) {
                assertTrue(step.path("with").path("verify-signature").asBoolean());
                assertEquals("maven", step.path("with").path("cache").asText(),
                    "checked proof authority must reuse the Maven dependency cache");
            }
            if (step.path("uses").asText().startsWith("actions/upload-artifact@")) {
                assertTrue(step.path("if").asText().contains("always()"));
                JsonNode artifact = step.required("with");
                assertEquals("checked-proof-evidence-${{ github.run_id }}-${{ github.run_attempt }}", artifact.required("name").asText());
                for (String path : List.of("/tmp/checked-proof-source.zip",
                        "regelsuche-proof-tests/target/reports/checked-proof/**", "regelsuche-proof-tests/target/failsafe-reports/**"))
                    assertTrue(artifact.required("path").asText().contains(path), path);
                assertEquals("error", artifact.required("if-no-files-found").asText());
                assertTrue(artifact.required("include-hidden-files").asBoolean());
                retained = true;
            }
        }
        assertTrue(retained, "full evidence must survive failed runs");
        assertEquals(List.of("git archive --format=zip --output=/tmp/checked-proof-source.zip HEAD",
            "mvn --batch-mode --no-transfer-progress -Pchecked-proofs -pl regelsuche-proof-tests -am -DskipTests verify"), commands);
        assertFalse(jobs.has("maven-proof-qualification"));
        Path module = root.resolve("regelsuche-proof-tests");
        var registered = Files.readAllLines(module.resolve("src/test/resources/checked-proof-suites.txt")).stream()
            .map(line -> line.substring(line.lastIndexOf('.') + 1)).collect(Collectors.toSet());
        for (String name : Set.of("CheckedLeanProofTest", "CheckedLeanRealTest", "CheckedProofBridgeTest",
                "CheckedProofBridgeRealTest", "ProofCacheAdmissionTest", "ProofJobSchedulerTest", "LeanProofBridgeTest",
                "SmtProofBridgeTest", "ProverExecutorAndBridgeTest", "ProofBridgeServiceTest", "ProofBridgeFormallyProvedTest",
                "ProofWorkerTest", "ProofScriptValidatorTest", "UnifiedMathDomainWorkbenchTest", "WebWorkbenchServerTest",
                "Z3SmtSolverBackendTest")) assertTrue(registered.contains(name), name);
        String pom = Files.readString(module.resolve("pom.xml"));
        assertTrue(pom.contains("<skipTests>false</skipTests><failIfNoTests>true</failIfNoTests>"));
        List<String> needs = new ArrayList<>();
        jobs.path("verification").path("needs").forEach(n -> needs.add(n.asText()));
        assertTrue(needs.contains("checked-proofs"));
        assertTrue(jobs.path("verification").path("steps").get(0).path("if").asText()
            .contains("needs.checked-proofs.result != 'success'"));
    }
}
