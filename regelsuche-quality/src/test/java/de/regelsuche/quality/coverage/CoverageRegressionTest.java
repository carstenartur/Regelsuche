package de.regelsuche.quality.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CoverageRegressionTest {
    private static final String REPORT = "build/reports/jacoco/test/jacocoTestReport.xml";
    private static final String OUTPUT = "build/reports/quality/coverage-report.json";
    private static final String MARKDOWN = "build/reports/quality/coverage-report.md";
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @Test
    void countsOnlyReportCountersAndWeightsAggregateByTotal() throws Exception {
        policy("""
            {"zeta":{"lineMinimumPercent":75.0,"branchMinimumPercent":100},
             "alpha":{"lineMinimumPercent":80,"branchMinimumPercent":50.0}}
            """, 78, 50);
        report("zeta", counters(1, 3, 0, 0));
        report("alpha", counters(2, 8, 2, 2));

        assertEquals(0, run().code());
        String firstReport = Files.readString(root.resolve(OUTPUT));
        JsonNode result = JSON.readTree(firstReport);
        assertEquals("regelsuche.quality.coverage-report/v1", result.path("schema").asText());
        assertEquals("PASSED", result.path("status").asText());
        assertEquals("alpha", result.at("/modules/0/module").asText());
        assertEquals(100.0, result.at("/modules/1/branch/percent").asDouble());
        assertEquals(3, result.at("/aggregate/line/missed").asInt());
        assertEquals(11, result.at("/aggregate/line/covered").asInt());
        assertEquals(78.5714, result.at("/aggregate/line/percent").asDouble());
        assertTrue(Files.readString(root.resolve(MARKDOWN)).contains("| `alpha` | 80.00% | 80% | 50.00% | 50.0% |"));
        assertEquals(0, run().code());
        assertEquals(firstReport, Files.readString(root.resolve(OUTPUT)));
    }

    @Test
    void retainsFailedReportsForMissingUnexpectedAndBelowFloorModules() throws Exception {
        policy("""
            {"absent":{"lineMinimumPercent":70,"branchMinimumPercent":60},
             "alpha":{"lineMinimumPercent":90,"branchMinimumPercent":60}}
            """, 85, 60);
        report("unknown", counters(0, 2, 0, 0));
        report("alpha", counters(2, 8, 2, 2));

        Outcome outcome = run();
        assertEquals(1, outcome.code());
        JsonNode result = JSON.readTree(Files.readString(root.resolve(OUTPUT)));
        assertEquals("FAILED", result.path("status").asText());
        assertEquals("absent", result.at("/missingModules/0").asText());
        assertEquals("unknown", result.at("/undeclaredModules/0").asText());
        assertTrue(result.at("/modules/1/line/minimumPercent").isNull());
        assertEquals(JSON.readTree("""
            ["missing covered modules: absent", "undeclared covered modules: unknown",
             "alpha line coverage 80.0000% is below 90.0000%",
             "alpha branch coverage 50.0000% is below 60.0000%",
             "aggregate line coverage 83.3333% is below 85.0000%",
             "aggregate branch coverage 50.0000% is below 60.0000%"]
            """), result.path("violations"));
        assertTrue(outcome.error().contains("coverageViolation=missing covered modules: absent"));
        assertTrue(Files.readString(root.resolve(MARKDOWN)).contains("| `unknown` | 100.00% | None% | 100.00% | None% |"));
    }

    @Test
    void comparesUnroundedCoverageWithTheExistingTolerance() throws Exception {
        policy("""
            {"alpha":{"lineMinimumPercent":80.0000000009,"branchMinimumPercent":50.00001}}
            """, 80, 50);
        report("alpha", counters(2, 8, 1, 1));

        assertEquals(1, run().code());
        JsonNode result = JSON.readTree(Files.readString(root.resolve(OUTPUT)));
        assertEquals(JSON.readTree("""
            ["alpha branch coverage 50.0000% is below 50.0000%"]
            """), result.path("violations"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "malformed", "nested-only", "duplicate", "bad-count", "bad-policy",
        "duplicate-module", "wrong-root", "namespaced-root", "missing-missed", "missing-covered"})
    void rejectsIncompleteOrInvalidInputsBeforeWritingAReport(String defect) throws Exception {
        policy("""
            {"alpha":{"lineMinimumPercent":90,"branchMinimumPercent":60}}
            """, 85, 60);
        String expected = switch (defect) {
            case "none" -> "no JaCoCo XML reports found";
            case "malformed" -> { report("alpha", "<broken>"); yield "cannot parse"; }
            case "nested-only" -> {
                report("alpha", "<package>" + counters(1, 9, 1, 3) + "</package>");
                yield "one top-level LINE counter";
            }
            case "duplicate" -> {
                report("alpha", counters(1, 9, 1, 3) + counters(1, 9, 1, 3));
                yield "one top-level LINE counter";
            }
            case "bad-count" -> {
                report("alpha", counters(-1, 9, 1, 3));
                yield "invalid LINE counter";
            }
            case "bad-policy" -> {
                policy("""
                    {"alpha":{"lineMinimumPercent":90}}
                    """, 85, 60);
                yield "branchMinimumPercent";
            }
            case "duplicate-module" -> {
                report("", counters(1, 9, 1, 3));
                report("build", counters(1, 9, 1, 3));
                yield "duplicate JaCoCo report for module build";
            }
            case "wrong-root", "namespaced-root" -> {
                String xml = "wrong-root".equals(defect)
                    ? "<anything>" + counters(1, 9, 1, 3) + "</anything>"
                    : "<report xmlns=\"urn:other\">" + counters(1, 9, 1, 3) + "</report>";
                write(root.resolve("alpha").resolve(REPORT), xml);
                yield "unnamespaced <report> root";
            }
            case "missing-missed", "missing-covered" -> {
                String attribute = defect.substring("missing-".length());
                report("alpha", counters(0, 0, 0, 0).replaceFirst(" " + attribute + "=\"0\"", ""));
                yield "missing " + attribute + " attribute";
            }
            default -> throw new IllegalArgumentException(defect);
        };

        Outcome outcome = run();
        assertEquals(1, outcome.code());
        assertTrue(outcome.error().contains("coverage gate failed: "), outcome.error());
        assertTrue(outcome.error().contains(expected), outcome.error());
        assertFalse(Files.exists(root.resolve(OUTPUT)));
        assertFalse(Files.exists(root.resolve(MARKDOWN)));
    }

    @Test
    void malformedRerunInvalidatesCanonicalReportsWithoutTouchingCustomPaths() throws Exception {
        policy("""
            {"alpha":{"lineMinimumPercent":80,"branchMinimumPercent":60}}
            """, 80, 60);
        report("alpha", counters(0, 0, 0, 0));
        assertEquals(0, run().code());
        assertEquals("PASSED", JSON.readTree(Files.readString(root.resolve(OUTPUT))).path("status").asText());

        Path input = root.resolve("alpha").resolve(REPORT);
        Files.writeString(input, "<report>");
        Path unrelated = root.resolve("do-not-delete.md");
        Files.writeString(unrelated, "keep me");
        assertEquals(1, run("--json-output", input.toString(), "--markdown-output", unrelated.toString()).code());
        assertFalse(Files.exists(root.resolve(OUTPUT)));
        assertFalse(Files.exists(root.resolve(MARKDOWN)));
        assertEquals("<report>", Files.readString(input));
        assertEquals("keep me", Files.readString(unrelated));
    }

    @Test
    void completedFailingRerunReplacesStaleReportsWithTheNewDecision() throws Exception {
        policy("""
            {"alpha":{"lineMinimumPercent":80,"branchMinimumPercent":60}}
            """, 80, 60);
        report("alpha", counters(0, 0, 0, 0));
        assertEquals(0, run().code());

        report("alpha", counters(100, 0, 0, 0));
        assertEquals(1, run().code());
        assertEquals("FAILED", JSON.readTree(Files.readString(root.resolve(OUTPUT))).path("status").asText());
        assertTrue(Files.readString(root.resolve(MARKDOWN)).contains("Status: **FAILED**"));

        report("alpha", counters(0, 0, 0, 0));
        assertEquals(0, run().code());
        report("alpha", counters(100, 0, 0, 0));
        assertEquals(1, run("--json-output", "custom.json", "--markdown-output", "custom.md").code());
        assertFalse(Files.exists(root.resolve(OUTPUT)));
        assertFalse(Files.exists(root.resolve(MARKDOWN)));
        assertEquals("FAILED", JSON.readTree(Files.readString(root.resolve("custom.json"))).path("status").asText());
    }

    @Test
    void policyAliasCannotBeOverwrittenOrRemoved() throws Exception {
        policy("""
            {"alpha":{"lineMinimumPercent":80,"branchMinimumPercent":60}}
            """, 80, 60);
        report("alpha", counters(0, 0, 0, 0));
        Path policyPath = root.resolve("config/quality/coverage-policy.json");
        Path output = root.resolve(OUTPUT);
        Files.createDirectories(output.getParent());
        Files.move(policyPath, output);
        Files.createSymbolicLink(policyPath, output);
        String original = Files.readString(policyPath);

        assertEquals(1, run().code());
        assertEquals(original, Files.readString(policyPath));
        assertFalse(Files.exists(root.resolve(MARKDOWN)));
    }

    @Test
    void publicationFailureCannotLeavePreviousOrPartialCanonicalReports() throws Exception {
        policy("""
            {"alpha":{"lineMinimumPercent":80,"branchMinimumPercent":60}}
            """, 80, 60);
        report("alpha", counters(0, 0, 0, 0));
        assertEquals(0, run().code());
        Files.delete(root.resolve(OUTPUT));
        Files.createDirectory(root.resolve(OUTPUT));
        Path unrelated = root.resolve(OUTPUT).resolve("unrelated");
        Files.writeString(unrelated, "keep me");

        assertEquals(1, run().code());
        assertFalse(Files.exists(root.resolve(MARKDOWN)));
        assertEquals("keep me", Files.readString(unrelated));
        try (var files = Files.list(root.resolve(OUTPUT).getParent())) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().startsWith(".coverage-")));
        }
    }

    private Outcome run(String... extra) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        List<String> args = new ArrayList<>(List.of("--root", root.toString()));
        args.addAll(List.of(extra));
        int code = CoverageRegressionMain.run(args.toArray(String[]::new),
            new PrintStream(out, false, StandardCharsets.UTF_8),
            new PrintStream(err, false, StandardCharsets.UTF_8));
        return new Outcome(code, err.toString(StandardCharsets.UTF_8));
    }

    private void policy(String modules, int line, int branch) throws Exception {
        write(root.resolve("config/quality/coverage-policy.json"), """
            {"schema":"regelsuche.quality.coverage-policy/v1","ratchetPolicy":"Retained grüner Run",
             "aggregate":{"lineMinimumPercent":%d,"branchMinimumPercent":%d},"modules":%s}
            """.formatted(line, branch, modules));
    }

    private void report(String module, String content) throws Exception {
        write(root.resolve(module).resolve(REPORT), """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report><package><counter type="LINE" missed="9000" covered="0"/></package>
            """ + content + "</report>");
    }

    private static String counters(int lineMissed, int lineCovered, int branchMissed, int branchCovered) {
        return """
            <counter type="LINE" missed="%d" covered="%d"/>
            <counter type="BRANCH" missed="%d" covered="%d"/>
            """.formatted(lineMissed, lineCovered, branchMissed, branchCovered);
    }

    private static void write(Path path, String contents) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
    }

    private record Outcome(int code, String error) { }
}
