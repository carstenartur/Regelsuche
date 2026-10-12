package de.regelsuche.quality.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Publisher-boundary controls; the separate renderer test uses actual JaCoCo. */
class CoveragePagesContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODULE = "regelsuche-math-sympy";
    private static final String COUNTERS = """
        <counter type="INSTRUCTION" missed="20" covered="80"/>
        <counter type="LINE" missed="1" covered="9"/>
        <counter type="BRANCH" missed="1" covered="3"/>
        <counter type="COMPLEXITY" missed="1" covered="2"/>
        <counter type="METHOD" missed="0" covered="1"/>
        <counter type="CLASS" missed="0" covered="1"/>
        """;
    @TempDir Path temporary;

    @Test
    void publishesLineBadgeWithoutOverwritingStandardHtmlOrCountingNestedSummaries() throws Exception {
        fixture();
        assertEquals(0, publish(), () -> readLog());
        assertEquals("standard JaCoCo", Files.readString(temporary.resolve("public/coverage/index.html")));
        JsonNode badge = JSON.readTree(temporary.resolve("public/coverage/badge.json").toFile());
        assertEquals("90.00%", badge.path("message").asText());
        assertEquals("coverage (lines)", badge.path("label").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-group", "duplicate-group", "different-counts", "aggregate-drift",
        "failed-gate", "missing-html", "missing-canonical", "duplicate-counter", "duplicate-gate-module",
        "gate-counts", "aggregate-gate-counts", "missing-policy-module"})
    void incompleteOrInconsistentCoverageCannotPublishASuccessBadge(String defect) throws Exception {
        fixture();
        Path aggregate = temporary.resolve("public/coverage/jacoco.xml");
        String xml = Files.readString(aggregate);
        switch (defect) {
            case "missing-group" -> xml = xml.replace(group(), "");
            case "duplicate-group" -> xml = xml.replace(group(), group() + group());
            case "different-counts" -> xml = xml.replaceFirst("covered=\"80\"", "covered=\"79\"");
            case "aggregate-drift" -> xml = xml.replace("</report>", "<counter type=\"LINE\" missed=\"1\" covered=\"9\"/></report>");
            case "failed-gate" -> updateGate("status", "FAILED");
            case "missing-html" -> Files.delete(temporary.resolve("public/coverage/index.html"));
            case "missing-canonical" -> Files.delete(canonical());
            case "duplicate-counter" -> Files.writeString(canonical(), "<report>" + COUNTERS + COUNTERS + "</report>");
            case "duplicate-gate-module" -> {
                ObjectNode gate = gate();
                gate.withArray("modules").add(gate.path("modules").get(0).deepCopy());
                writeGate(gate);
            }
            case "gate-counts" -> {
                ObjectNode gate = gate();
                ((ObjectNode) gate.path("modules").get(0).path("line")).put("covered", 8);
                writeGate(gate);
            }
            case "aggregate-gate-counts" -> {
                ObjectNode gate = gate();
                ((ObjectNode) gate.path("aggregate").path("line")).put("covered", 8);
                writeGate(gate);
            }
            case "missing-policy-module" -> {
                Path policy = temporary.resolve("config/quality/coverage-policy.json");
                ObjectNode value = (ObjectNode) JSON.readTree(policy.toFile());
                ((ObjectNode) value.path("modules")).putObject("missing");
                JSON.writeValue(policy.toFile(), value);
            }
            default -> throw new IllegalArgumentException(defect);
        }
        Files.writeString(aggregate, xml);
        assertTrue(publish() != 0, defect);
        assertFalse(Files.exists(temporary.resolve("public/coverage/badge.json")), defect);
    }

    @Test
    void workflowRetainsTheCompleteSiteWithoutAnExternalUploadGate() throws Exception {
        Path root = Path.of(System.getProperty("regelsuche.repositoryRoot"));
        String workflow = Files.readString(root.resolve(".github/workflows/gradle.yml"));
        int start = workflow.indexOf("\n  verification:");
        int end = workflow.indexOf("\n  publish-pages:", start);
        String verification = workflow.substring(start, end);
        assertTrue(verification.indexOf("Verify complete cross-authority coverage")
            < verification.indexOf("scripts/generate-ci-pages.py --coverage-only"));
        assertTrue(verification.contains("--sympy-inputs"));
        assertTrue(verification.contains("public/coverage/**"));
        assertFalse(workflow.contains("NativeCodeCoverage"));
        assertFalse(workflow.contains("code-quality: write"));
        assertFalse(workflow.contains("nativeCoverageRuntime"));
        String adapter = Files.readString(root.resolve("regelsuche-quality/build.gradle"));
        assertTrue(adapter.contains("reports/quality/coverage-report-runtime"));
        assertTrue(adapter.contains("REGELSUCHE_SEPARATE_SYMPY_RUNTIME_AUTHORITY"));
        assertTrue(adapter.contains("args '--tests-only'"));
        assertTrue(adapter.contains("dependsOn ':verifyCoverageRegression'"));
    }

    private void fixture() throws Exception {
        write("config/quality/coverage-policy.json", """
            {"schema":"regelsuche.quality.coverage-policy/v1","modules":{"regelsuche-math-sympy":{}}}
            """);
        write("build/reports/quality/coverage-report.json", """
            {"schema":"regelsuche.quality.coverage-report/v1","status":"PASSED",
             "missingModules":[],"undeclaredModules":[],"violations":[],
             "modules":[{"module":"regelsuche-math-sympy","line":{"missed":1,"covered":9},
                "branch":{"missed":1,"covered":3}}],
             "aggregate":{"line":{"missed":1,"covered":9},"branch":{"missed":1,"covered":3}}}
            """);
        write("public/coverage/index.html", "standard JaCoCo");
        write("public/coverage/jacoco.xml", "<report>" + group() + COUNTERS + "</report>");
        // Recursive instruction sums must not count package/method summaries.
        write(temporary.relativize(canonical()).toString(), "<report><package><counter type=\"INSTRUCTION\" missed=\"900\" covered=\"0\"/></package>" + COUNTERS + "</report>");
    }

    private int publish() throws Exception {
        Path root = Path.of(System.getProperty("regelsuche.repositoryRoot"));
        // Only the external renderer boundary is replaced here. Output/count
        // validation runs unchanged; JacocoAggregateReportTest tests rendering.
        String program = """
            import runpy, sys
            from pathlib import Path
            from unittest.mock import patch
            module = runpy.run_path(sys.argv[1])
            with patch('subprocess.run'):
                module['generate_coverage'](Path('public'))
            """;
        Process process = new ProcessBuilder(List.of("python3", "-B", "-c", program,
            root.resolve("scripts/generate-ci-pages.py").toString()))
            .directory(temporary.toFile()).redirectErrorStream(true)
            .redirectOutput(temporary.resolve("publisher.log").toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "publisher timed out");
            return process.exitValue();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String group() {
        return "<group name=\"" + MODULE + "\">" + COUNTERS + "</group>";
    }

    private Path canonical() {
        return temporary.resolve(MODULE + "/build/reports/jacoco/test/jacocoTestReport.xml");
    }

    private void write(String relative, String value) throws Exception {
        Path path = temporary.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    private ObjectNode gate() throws Exception {
        return (ObjectNode) JSON.readTree(temporary.resolve("build/reports/quality/coverage-report.json").toFile());
    }

    private void updateGate(String key, String value) throws Exception {
        ObjectNode gate = gate(); gate.put(key, value); writeGate(gate);
    }

    private void writeGate(ObjectNode gate) throws Exception {
        JSON.writeValue(temporary.resolve("build/reports/quality/coverage-report.json").toFile(), gate);
    }

    private String readLog() {
        try { return Files.readString(temporary.resolve("publisher.log")); }
        catch (Exception exception) { return exception.toString(); }
    }
}
