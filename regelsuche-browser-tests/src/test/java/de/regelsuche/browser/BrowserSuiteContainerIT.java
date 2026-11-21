package de.regelsuche.browser;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.w3c.dom.Element;

/** Run the actual Workbench, its complete Java browser suite and the pixel baselines in one network namespace. */
class BrowserSuiteContainerIT {
    private static final String SCREENSHOTS = "app/src/e2eTest/resources/screenshots";
    private static final List<String> INPUTS = List.of("README.md", "settings.gradle", "app/build.gradle",
        "Dockerfile.visual-regression", SCREENSHOTS, "docs/assets/ast-rule-radar.svg",
        "docs/generated/autonomous-discovery-walkthrough-exact-v2/representative-search.svg");

    @Test
    void allBrowserTestsAndEveryVisualBaselineExecuteOnThePinnedRuntime() throws Exception {
        Path root = Path.of(System.getProperty("regelsuche.repositoryRoot"));
        Path target = Path.of(System.getProperty("regelsuche.browser.target"));
        Path reports = target.resolve("reports/browser");
        Files.createDirectories(reports);
        JsonNode policy = new ObjectMapper().readTree(root.resolve(SCREENSHOTS + "/visual-regression-policy.json").toFile());
        String pinned = policy.required("environment").required("containerImage").textValue();
        assertTrue(pinned.matches("mcr\\.microsoft\\.com/playwright/java:v[0-9.]+-noble@sha256:[0-9a-f]{64}"));
        try (var worker = new GenericContainer<>(DockerImageName.parse(pinned))
                .withCommand("sleep", "infinity")
                .withWorkingDirectory("/workspace")
                .withEnv("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
                .withEnv("REGELSUCHE_VISUAL_REPORT_DIR", "/workspace/results/visual")
                .withCopyFileToContainer(MountableFile.forHostPath(target.resolve("browser-lib")), "/worker/lib")
                .withCopyFileToContainer(MountableFile.forHostPath(target.resolve("test-classes")), "/worker/tests")) {
            for (String path : INPUTS) {
                worker.withCopyFileToContainer(MountableFile.forHostPath(root.resolve(path)), "/workspace/" + path);
            }
            worker.start();
            assertEquals(0, worker.execInContainer("mkdir", "-p", "/workspace/results/visual", "/workspace/build/reports",
                "/workspace/app/build/reports").getExitCode());
            var run = worker.execInContainer("java", "--enable-native-access=ALL-UNNAMED", "-Dregelsuche.runVisualTests=true",
                "-Dregelsuche.repositoryRoot=/workspace", "-cp", "/worker/tests:/worker/lib/*", "org.junit.platform.console.ConsoleLauncher",
                "execute", "--select-package=de.regelsuche.e2e",
                // The Git-fixture tests run on the Maven host, not in the pinned offline image.
                "--exclude-classname=.*\\.DocumentationScreenshotsTest",
                "--fail-if-no-tests", "--disable-ansi-colors",
                "--reports-dir=/workspace/results/junit");
            Files.writeString(reports.resolve("stdout.log"), run.getStdout());
            Files.writeString(reports.resolve("stderr.log"), run.getStderr());
            var archived = worker.execInContainer("tar", "-czf", "/evidence.tgz", "-C", "/workspace", "results", "build/reports", "app/build/reports");
            assertEquals(0, archived.getExitCode(), archived.getStderr());
            worker.copyFileFromContainer("/evidence.tgz", reports.resolve("evidence.tgz").toString());
            assertEquals(0, run.getExitCode(), () -> "Browser suite failed. Captured output and screenshots: " + reports);
            Path xml = reports.resolve("jupiter.xml");
            worker.copyFileFromContainer("/workspace/results/junit/TEST-junit-jupiter.xml", xml.toString());
            requireCompleteJUnitReport(xml);
            var baselines = policy.required("baselines").properties();
            assertFalse(baselines.isEmpty());
            for (var entry : baselines) {
                String baseline = entry.getKey();
                assertTrue(baseline.matches("[a-z0-9-]+\\.png"));
                String actual = baseline.substring(0, baseline.length() - 4) + ".actual.png";
                worker.copyFileFromContainer("/workspace/results/visual/" + actual, reports.resolve(actual).toString());
                assertTrue(Files.size(reports.resolve(actual)) > 0, actual);
            }
        }
    }

    private static void requireCompleteJUnitReport(Path xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Element suite = factory.newDocumentBuilder().parse(xml.toFile()).getDocumentElement();
        assertEquals("testsuite", suite.getTagName());
        assertTrue(Integer.parseInt(suite.getAttribute("tests")) > 0);
        for (String count : List.of("skipped", "errors", "failures")) assertEquals("0", suite.getAttribute(count), count);
    }
}
