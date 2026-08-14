package de.regelsuche.quality.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlowTestReportGeneratorTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void writesDeterministicSortedEvidence(@TempDir Path root)
            throws Exception {
        writeSuite(
            root.resolve(
                "module-b/build/test-results/test/TEST-module-b.xml"
            ),
            """
            <testsuite>
              <testcase classname="z.Class" name="slow-case" time="7.1234567">
                <failure message="boom"/>
              </testcase>
              <testcase classname="a.Class" name="invalid-time" time="not-a-number"/>
            </testsuite>
            """
        );
        writeSuite(
            root.resolve(
                "module-a/build/test-results/test/TEST-module-a.xml"
            ),
            """
            <testsuite>
              <testcase classname="a.Class" name="threshold" time="5.0">
                <error message="failed"/>
              </testcase>
              <testcase classname="a.Class" name="small" time="1.25"/>
            </testsuite>
            """
        );
        writeSuite(
            root.resolve(
                "module-c/build/test-results/test/TEST-broken.xml"
            ),
            "<testsuite><testcase"
        );
        writeSuite(
            root.resolve(
                "module-c/build/test-results/test/TEST-external.xml"
            ),
            """
            <!DOCTYPE testsuite [
              <!ENTITY external SYSTEM "file:///definitely-not-a-report">
            ]>
            <testsuite>
              <testcase classname="ignored.Class" name="&external;" time="99"/>
            </testsuite>
            """
        );
        writeSuite(
            root.resolve(
                "module-d/build/test-results/binary/results.xml"
            ),
            """
            <testsuite>
              <testcase classname="ignored.Class" name="ignored" time="99"/>
            </testsuite>
            """
        );
        writeSuite(
            root.resolve("module-e/build/other/ignored.xml"),
            """
            <testsuite>
              <testcase classname="ignored.Class" name="ignored" time="99"/>
            </testsuite>
            """
        );

        SlowTestReportGenerator generator = new SlowTestReportGenerator();
        Path firstJson = root.resolve("first/slow-tests.json");
        Path firstMarkdown = root.resolve("first/slow-tests.md");
        Path secondJson = root.resolve("second/slow-tests.json");
        Path secondMarkdown = root.resolve("second/slow-tests.md");
        SlowTestReport report = generator.write(
            root,
            3,
            5.0d,
            firstJson,
            firstMarkdown
        );
        SlowTestReport repeated = generator.write(
            root,
            3,
            5.0d,
            secondJson,
            secondMarkdown
        );

        assertEquals(report, repeated);
        assertEquals(2, report.suiteCount());
        assertEquals(4, report.testCount());
        assertEquals(13.373457d, report.totalTestSeconds());
        assertEquals(2, report.slowTestCount());
        assertEquals(3, report.slowestTests().size());
        assertEquals(
            List.of("slow-case", "threshold", "small"),
            report.slowestTests().stream()
                .map(SlowTestReport.TestCaseEntry::testName)
                .toList()
        );
        assertTrue(report.slowestTests().get(0).failed());
        assertTrue(report.slowestTests().get(1).failed());
        assertFalse(report.slowestTests().get(2).failed());
        assertEquals(7.123457d, report.slowestTests().get(0).seconds());

        assertEquals(3, report.slowestClasses().size());
        assertEquals("module-b", report.slowestClasses().get(0).module());
        assertEquals("z.Class", report.slowestClasses().get(0).className());
        assertEquals(7.123457d, report.slowestClasses().get(0).seconds());
        assertEquals("module-a", report.slowestClasses().get(1).module());
        assertEquals(6.25d, report.slowestClasses().get(1).seconds());
        assertEquals(2, report.slowestClasses().get(1).testCount());

        assertEquals(
            -1L,
            Files.mismatch(firstJson, secondJson),
            "repeated JSON rendering must be byte-identical"
        );
        assertEquals(
            -1L,
            Files.mismatch(firstMarkdown, secondMarkdown),
            "repeated Markdown rendering must be byte-identical"
        );

        JsonNode json = JSON.readTree(firstJson.toFile());
        assertEquals(
            SlowTestReportGenerator.SCHEMA,
            json.path("schema").asText()
        );
        assertEquals(2, json.path("suiteCount").asInt());
        assertEquals(4, json.path("testCount").asInt());
        assertEquals(2, json.path("slowTestCount").asInt());
        assertTrue(
            json.path("slowestTests").get(0).path("failed").asBoolean()
        );
        assertEquals(
            "slow-case",
            json.path("slowestTests").get(0).path("testName").asText()
        );

        String markdown = Files.readString(
            firstMarkdown,
            StandardCharsets.UTF_8
        );
        assertTrue(markdown.contains(
            "Parsed **4** tests from **2** JUnit suites."
        ));
        assertTrue(markdown.contains(
            "Tests at or above **5.0s**: **2**."
        ));
        assertTrue(markdown.contains(
            "| 7.123 | `module-b` | `z.Class.slow-case` |"
        ));
        assertTrue(markdown.contains(
            "| 6.250 | 2 | `module-a` | `a.Class` |"
        ));
    }

    @Test
    void invalidLimitsAndEmptyCorporaFailClosed(@TempDir Path root) {
        SlowTestReportGenerator generator = new SlowTestReportGenerator();

        expectInvalid(
            () -> generator.generate(root, 0, 5.0d),
            "invalid reporting limits"
        );
        expectInvalid(
            () -> generator.generate(root, 100, -0.1d),
            "invalid reporting limits"
        );
        expectInvalid(
            () -> generator.generate(root, 100, Double.NaN),
            "invalid reporting limits"
        );
        expectInvalid(
            () -> generator.generate(
                root,
                100,
                Double.POSITIVE_INFINITY
            ),
            "invalid reporting limits"
        );
        expectInvalid(
            () -> generator.generate(root, 100, 5.0d),
            "no JUnit test cases found"
        );
    }

    @Test
    void commandLineHonorsConfiguredOutputs(@TempDir Path root)
            throws Exception {
        writeSuite(
            root.resolve("module/build/test-results/test/TEST-one.xml"),
            """
            <testsuite>
              <testcase classname="example.One" name="works" time="0.5"/>
            </testsuite>
            """
        );
        Path json = root.resolve("custom/report.json");
        Path markdown = root.resolve("custom/report.md");

        SlowTestReportMain.main(new String[] {
            "--root", root.toString(),
            "--limit", "1",
            "--slow-seconds", "0.5",
            "--json-output", json.toString(),
            "--markdown-output", markdown.toString()
        });

        assertTrue(Files.isRegularFile(json));
        assertTrue(Files.isRegularFile(markdown));
        assertEquals(
            1,
            JSON.readTree(json.toFile()).path("slowTestCount").asInt()
        );
        expectInvalid(
            () -> SlowTestReportMain.main(new String[] {
                "--unknown", "value"
            }),
            "unknown option"
        );
    }

    private static void writeSuite(Path path, String content)
            throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static void expectInvalid(
        ThrowingOperation operation,
        String fragment
    ) {
        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            operation::run
        );
        assertTrue(
            failure.getMessage().contains(fragment),
            () -> "expected <"
                + fragment
                + "> in <"
                + failure.getMessage()
                + ">"
        );
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }
}
