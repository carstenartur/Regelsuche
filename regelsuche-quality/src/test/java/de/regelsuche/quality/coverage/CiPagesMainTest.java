package de.regelsuche.quality.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CiPagesMainTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @Test
    void rendersSameBadgeShapeAndSafeLinkedTestSummaryAsHistoricalPages() throws Exception {
        write("app/build/test-results/test/TEST-A.xml",
            "<testsuite name=\"A &amp; &lt;A&gt;\" tests=\"2\" failures=\"1\" errors=\"0\" skipped=\"0\"/>");
        write("regelsuche-core/build/test-results/test/TEST-B.xml",
            "<testsuite name=\"B\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"1\"/>");
        write("app/build/reports/tests/test/index.html", "<html>Gradle test report</html>");
        CiPagesMain.TestCounts counts = CiPagesMain.publishTests(root, root.resolve("public/tests"));
        assertEquals(3, counts.tests());
        assertEquals(1, counts.failed());
        assertEquals(1, counts.skipped());
        JsonNode badge = JSON.readTree(root.resolve("public/tests/badge.json").toFile());
        assertEquals(1, badge.path("schemaVersion").intValue());
        assertEquals("tests", badge.path("label").asText());
        assertEquals("1/3 failed", badge.path("message").asText());
        assertEquals("red", badge.path("color").asText());
        String html = Files.readString(root.resolve("public/tests/index.html"));
        assertTrue(html.contains("A &amp; &lt;A&gt;"));
        assertFalse(html.contains("A & <A>"));
        assertTrue(html.contains("reports/app-test/index.html"));
        assertTrue(html.contains("<strong>1</strong> passed"));
        assertEquals("<html>Gradle test report</html>",
            Files.readString(root.resolve("public/tests/reports/app-test/index.html")));
    }

    @Test
    void noReportsKeepsTheHistoricalZeroTestShape() throws Exception {
        CiPagesMain.publishTests(root, root.resolve("public/tests"));
        JsonNode badge = JSON.readTree(root.resolve("public/tests/badge.json").toFile());
        assertEquals("0 passed", badge.path("message").asText());
        assertTrue(Files.readString(root.resolve("public/tests/index.html"))
            .contains("No JUnit XML reports were generated."));
    }

    @Test
    void invalidCountsAndHostileXmlFailClosedRatherThanPublishingSuccess() throws Exception {
        Path report = write("app/build/test-results/test/TEST-A.xml",
            "<testsuite tests=\"1\" failures=\"2\" errors=\"0\" skipped=\"0\"/>");
        assertThrows(IOException.class,
            () -> CiPagesMain.publishTests(root, root.resolve("public/tests")));
        assertFalse(Files.exists(root.resolve("public/tests/badge.json")));
        Files.writeString(report, "<!DOCTYPE x [ <!ENTITY x SYSTEM \"file:///etc/passwd\"> ]>"
            + "<testsuite name=\"&x;\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"/>");
        assertThrows(Exception.class,
            () -> CiPagesMain.publishTests(root, root.resolve("public/tests")));
        assertFalse(Files.exists(root.resolve("public/tests/badge.json")));
    }

    private Path write(String path, String value) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, value);
        return file;
    }
}
