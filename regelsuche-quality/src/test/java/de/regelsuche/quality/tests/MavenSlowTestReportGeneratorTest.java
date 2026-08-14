package de.regelsuche.quality.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenSlowTestReportGeneratorTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void readsOnlyDeclaredSurefireAndFailsafeReports(
        @TempDir Path root
    ) throws Exception {
        writeModule(root, "module-a");
        writeModule(root, "module-b");
        writeModule(root, "unlisted-module");
        writeModule(root, "regelsuche-quality-aggregate");

        writeSuite(
            root.resolve(
                "module-a/target/surefire-reports/TEST-module-a.xml"
            ),
            """
            <testsuite>
              <testcase classname="example.Fast" name="unit" time="2.0"/>
            </testsuite>
            """
        );
        writeSuite(
            root.resolve(
                "module-b/target/failsafe-reports/TEST-module-b.xml"
            ),
            """
            <testsuite>
              <testcase classname="example.Slow" name="integration" time="6.0"/>
            </testsuite>
            """
        );
        writeSuite(
            root.resolve(
                "module-a/target/failsafe-reports/failsafe-summary.xml"
            ),
            ignoredSuite()
        );
        writeSuite(
            root.resolve(
                "module-a/target/surefire-reports/result.xml"
            ),
            ignoredSuite()
        );
        writeSuite(
            root.resolve(
                "module-a/target/surefire-reports/nested/TEST-copy.xml"
            ),
            ignoredSuite()
        );
        writeSuite(
            root.resolve(
                "module-a/build/test-results/test/TEST-gradle.xml"
            ),
            ignoredSuite()
        );
        writeSuite(
            root.resolve(
                "unlisted-module/target/surefire-reports/TEST-stale.xml"
            ),
            ignoredSuite()
        );
        writeSuite(
            root.resolve(
                "regelsuche-quality-aggregate/target/surefire-reports/"
                    + "TEST-previous-run.xml"
            ),
            ignoredSuite()
        );

        SlowTestReportGenerator generator = new SlowTestReportGenerator();
        Path firstJson = root.resolve("first/slow-tests.json");
        Path firstMarkdown = root.resolve("first/slow-tests.md");
        Path secondJson = root.resolve("second/slow-tests.json");
        Path secondMarkdown = root.resolve("second/slow-tests.md");
        Set<String> modules = Set.of("module-a", "module-b");

        SlowTestReport report = generator.writeMaven(
            root,
            100,
            5.0d,
            modules,
            firstJson,
            firstMarkdown
        );
        SlowTestReport repeated = generator.writeMaven(
            root,
            100,
            5.0d,
            modules,
            secondJson,
            secondMarkdown
        );

        assertEquals(report, repeated);
        assertEquals(2, report.suiteCount());
        assertEquals(2, report.testCount());
        assertEquals(8.0d, report.totalTestSeconds());
        assertEquals(1, report.slowTestCount());
        assertEquals(
            "module-b",
            report.slowestTests().getFirst().module()
        );
        assertEquals(
            "integration",
            report.slowestTests().getFirst().testName()
        );
        assertEquals(-1L, Files.mismatch(firstJson, secondJson));
        assertEquals(-1L, Files.mismatch(
            firstMarkdown,
            secondMarkdown
        ));

        JsonNode json = JSON.readTree(firstJson.toFile());
        assertEquals(2, json.path("suiteCount").asInt());
        assertEquals(2, json.path("testCount").asInt());
        assertEquals(
            "module-b",
            json.path("slowestTests").get(0).path("module").asText()
        );
    }

    @Test
    void rejectsInvalidOrMissingMavenModuleSets(@TempDir Path root) {
        SlowTestReportGenerator generator = new SlowTestReportGenerator();

        expectInvalid(
            () -> generator.generateMaven(
                root,
                100,
                5.0d,
                Set.of()
            ),
            "Maven module set is required"
        );
        expectInvalid(
            () -> generator.generateMaven(
                root,
                100,
                5.0d,
                Set.of("../outside")
            ),
            "invalid Maven module path"
        );
        expectInvalid(
            () -> generator.generateMaven(
                root,
                100,
                5.0d,
                Set.of("missing-module")
            ),
            "configured Maven module is not a directory"
        );
    }

    private static void writeModule(Path root, String module)
            throws Exception {
        Path pom = root.resolve(module).resolve("pom.xml");
        Files.createDirectories(pom.getParent());
        Files.writeString(
            pom,
            "<project/>",
            StandardCharsets.UTF_8
        );
    }

    private static String ignoredSuite() {
        return """
            <testsuite>
              <testcase classname="ignored.Stale"
                        name="ignored"
                        time="99.0"/>
            </testsuite>
            """;
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
