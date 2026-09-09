package de.regelsuche.quality.aggregate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit full-profile external-consumer test, after the real application package.
 * The default host-minimal Maven lifecycle does not execute this IT.
 */
class MavenPythonClientDistributionIT {
    @Test
    @Timeout(360)
    void installedWheelAndJavaConsumerUseTheDistributedProduct() throws Exception {
        Path root = Path.of(System.getProperty("regelsuche.repositoryRoot"));
        Path reports = root.resolve("build/reports/python-client");
        Files.createDirectories(reports);
        Path log = reports.resolve("maven-integration.log");
        Process process = new ProcessBuilder("bash", root.resolve("scripts/verify-python-client.sh").toString())
            .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(300, TimeUnit.SECONDS), () -> "External-client test timed out: " + log);
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), () -> output.substring(Math.max(0, output.length() - 16000)));
            assertTrue(output.contains("Python wheel, offline proof, live Java server and external Java example verified."), output);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }
}
