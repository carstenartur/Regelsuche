package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class MavenJmhPrecisionStudyWorkflowContractTest {
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DIRECTORY = "build/reports/jmh-precision-study-v1";
    private static final String RUN = "python3 -B scripts/run-jmh-precision-study-v1.py "
        + "--replicate \"$REGELSUCHE_STUDY_REPLICATE\" "
        + "--job-start-epoch \"$REGELSUCHE_STUDY_JOB_START\" "
        + "--output \"" + DIRECTORY + "/replicate-$REGELSUCHE_STUDY_REPLICATE\"";
    private static final String REPORT = "python3 -B scripts/report-jmh-precision-study-v1.py "
        + "--replicate retained/jmh-precision-study-v1-replicate-1 "
        + "--replicate retained/jmh-precision-study-v1-replicate-2 "
        + "--replicate retained/jmh-precision-study-v1-replicate-3 --output " + DIRECTORY + "/analysis";

    @Test
    void parsedWorkflowBindsExactlyThreeOneTimeReplicasAndRetainsFailures() throws IOException {
        verify(workflow(), policy());
    }

    @Test
    void launchBroadeningMissingReplicasAndDiscardedErrorsFailClosed() throws IOException {
        ObjectNode original = workflow();
        JsonNode policy = policy();
        verify(original, policy);
        List<Consumer<ObjectNode>> mutations = List.of(
            tree -> study(tree).put("if", study(tree).path("if").asText() + " || true"),
            tree -> study(tree).put("if", study(tree).path("if").asText()
                .replace("github.run_attempt == 1 && ", "")),
            tree -> ((ObjectNode) study(tree).path("strategy")).put("fail-fast", true),
            tree -> ((ObjectNode) study(tree).path("strategy")).remove("fail-fast"),
            tree -> ((ObjectNode) study(tree).path("strategy").path("matrix"))
                .putArray("replicate").add(1).add(2),
            tree -> study(tree).put("timeout-minutes", 150),
            tree -> ((ObjectNode) study(tree).path("steps").get(1).path("with"))
                .put("ref", "${{ github.event.pull_request.head.sha }}"),
            tree -> ((ObjectNode) study(tree).path("steps").get(0)).put("run", "echo late timestamp"),
            tree -> ((ObjectNode) step(study(tree), "uses", "actions/upload-artifact@"))
                .put("if", "${{ success() }}"),
            tree -> ((ObjectNode) step(study(tree), "uses", "actions/upload-artifact@").path("with"))
                .put("path", DIRECTORY + "/replicate-${{ matrix.replicate }}/raw/*.json"),
            tree -> ((ObjectNode) step(study(tree), "uses", "actions/upload-artifact@").path("with"))
                .put("if-no-files-found", "warn"),
            tree -> report(tree).remove("needs"),
            tree -> ((ObjectNode) step(report(tree), "run", "scripts/report-jmh-precision-study-v1.py"))
                .put("run", REPORT.replace("--replicate retained/jmh-precision-study-v1-replicate-3 ", "")),
            tree -> ((ObjectNode) step(report(tree), "run", "scripts/report-jmh-precision-study-v1.py"))
                .remove("if")
        );
        for (int index = 0; index < mutations.size(); index++) {
            ObjectNode changed = original.deepCopy();
            mutations.get(index).accept(changed);
            assertThrows(AssertionError.class, () -> verify(changed, policy), "mutation " + index);
        }
    }

    private static void verify(ObjectNode tree, JsonNode policy) {
        JsonNode launch = policy.path("sharedRunnerLaunch");
        String gate = "github.event_name == 'pull_request' && github.event.action == 'opened' && "
            + "github.run_attempt == 1 && github.head_ref == '" + launch.path("headBranch").asText()
            + "' && github.repository == '" + launch.path("repository").asText()
            + "' && github.event.pull_request.head.repo.full_name == github.repository";
        JsonNode study = tree.path("jobs").path(launch.path("job").asText());
        assertTrue(study.isObject(), "missing preregistered study job");
        assertEquals(gate, expression(study.path("if")));
        assertEquals("ubuntu-22.04", study.path("runs-on").asText());
        assertEquals(policy.path("budgets").path("replicateTimeoutSeconds").asInt() / 60 + 15,
            study.path("timeout-minutes").asInt());
        assertFalse(study.has("needs"), "replicas must start independently of the normal ratchet");
        assertFalse(study.has("continue-on-error"), "errors must remain visible");
        assertEquals(JSON.getNodeFactory().booleanNode(false), study.path("strategy").path("fail-fast"));
        assertEquals(3, study.path("strategy").path("max-parallel").asInt());
        assertEquals(1, study.path("strategy").path("matrix").size());
        assertEquals(JSON.createArrayNode().add(1).add(2).add(3),
            study.path("strategy").path("matrix").path("replicate"));
        assertEquals("${{ matrix.replicate }}", study.path("env").path("REGELSUCHE_STUDY_REPLICATE").asText());
        assertEquals("echo \"REGELSUCHE_STUDY_JOB_START=$(date +%s)\" >> \"$GITHUB_ENV\"",
            compact(study.path("steps").get(0).path("run").asText()));
        assertEquals("${{ github.sha }}", study.path("steps").get(1).path("with").path("ref").asText());
        for (String action : List.of("actions/checkout@", "actions/setup-java@",
                "gradle/actions/setup-gradle@", "actions/upload-artifact@")) {
            assertTrue(step(study, "uses", action).path("uses").asText()
                .matches(java.util.regex.Pattern.quote(action) + "[0-9a-f]{40}"));
        }
        JsonNode java = step(study, "uses", "actions/setup-java@").path("with");
        assertEquals("25", java.path("java-version").asText());
        assertEquals("temurin", java.path("distribution").asText());
        JsonNode run = step(study, "run", "scripts/run-jmh-precision-study-v1.py");
        assertEquals(RUN, compact(run.path("run").asText()));
        assertEquals(60, run.path("timeout-minutes").asInt());
        retained(study, DIRECTORY + "/replicate-${{ matrix.replicate }}",
            "jmh-precision-study-v1-replicate-${{ matrix.replicate }}");

        JsonNode report = tree.path("jobs").path("jmh-precision-study-report");
        assertEquals("jmh-precision-study", report.path("needs").asText());
        assertEquals("always() && !cancelled() && " + gate, expression(report.path("if")));
        assertEquals(5, report.path("timeout-minutes").asInt());
        assertEquals("${{ github.sha }}", step(report, "uses", "actions/checkout@")
            .path("with").path("ref").asText());
        JsonNode download = step(report, "uses", "actions/download-artifact@").path("with");
        assertEquals("jmh-precision-study-v1-replicate-*", download.path("pattern").asText());
        assertEquals("retained", download.path("path").asText());
        assertEquals(JSON.getNodeFactory().booleanNode(false), download.path("merge-multiple"));
        JsonNode analysis = step(report, "run", "scripts/report-jmh-precision-study-v1.py");
        assertEquals(REPORT, compact(analysis.path("run").asText()));
        assertEquals("always() && !cancelled()", expression(analysis.path("if")));
        retained(report, DIRECTORY + "/analysis", "jmh-precision-study-v1-analysis");
        for (JsonNode need : tree.path("jobs").path("verification").path("needs")) {
            assertFalse(need.asText().startsWith("jmh-precision-study"));
        }
    }

    private static void retained(JsonNode job, String path, String name) {
        JsonNode upload = step(job, "uses", "actions/upload-artifact@");
        assertEquals("always()", expression(upload.path("if")));
        assertEquals(path, upload.path("with").path("path").asText());
        assertEquals(name, upload.path("with").path("name").asText());
        assertEquals("error", upload.path("with").path("if-no-files-found").asText());
    }

    private static JsonNode step(JsonNode job, String key, String fragment) {
        JsonNode found = null;
        for (JsonNode step : job.path("steps")) {
            if (step.path(key).asText().contains(fragment)) {
                assertTrue(found == null, "duplicate step for " + fragment);
                found = step;
            }
        }
        assertTrue(found != null, "missing step for " + fragment);
        return found;
    }

    private static ObjectNode study(ObjectNode tree) {
        return (ObjectNode) tree.path("jobs").path("jmh-precision-study");
    }

    private static ObjectNode report(ObjectNode tree) {
        return (ObjectNode) tree.path("jobs").path("jmh-precision-study-report");
    }

    private static String expression(JsonNode node) {
        return compact(node.asText()).replace("${{", "").replace("}}", "").trim();
    }

    private static String compact(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static ObjectNode workflow() throws IOException {
        return (ObjectNode) YAML.readTree(Files.readString(root().resolve(".github/workflows/gradle.yml")));
    }

    private static JsonNode policy() throws IOException {
        return JSON.readTree(Files.readString(root().resolve("config/quality/jmh-precision-study-policy-v1.json")));
    }

    private static Path root() {
        return Path.of(System.getProperty("regelsuche.repositoryRoot"));
    }
}
