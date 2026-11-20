package de.regelsuche.quality.aggregate;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

class MavenPythonClientDistributionIT {
    @TempDir Path extracted;

    @Test
    @Timeout(840)
    void installedWheelAndJavaConsumerUseTheDistributedProduct() throws Exception {
        Path root = Path.of(System.getProperty("regelsuche.repositoryRoot"));
        String product = "regelsuche-" + System.getProperty("regelsuche.distributionVersion");
        Path packageDir = root.resolve("app/target");
        try (var files = Files.list(packageDir)) {
            assertEquals(List.of(packageDir.resolve(product + ".zip")), files.filter(p ->
                p.getFileName().toString().matches("regelsuche-.*\\.zip")).toList(), "Expected the current Maven distribution");
        }
        try (var archive = new ZipFile(packageDir.resolve(product + ".zip").toFile())) {
            for (var entry : archive.stream().toList()) {
                Path dest = extracted.resolve(entry.getName()).normalize();
                assertTrue(dest.startsWith(extracted.resolve(product)), () -> "Invalid distribution entry " + entry.getName());
                if (entry.isDirectory()) Files.createDirectories(dest);
                else {
                    Files.createDirectories(dest.getParent());
                    try (var data = archive.getInputStream(entry)) { Files.copy(data, dest); }
                }
            }
        }

        Path reports = root.resolve("build/reports/python-client");
        Files.createDirectories(reports);
        var image = new ImageFromDockerfile()
            .withFileFromPath("Dockerfile", root.resolve("Dockerfile.external-python"))
            .withFileFromPath("requirements.txt", root.resolve("regelsuche-quality-aggregate/src/test/resources/python-wheel-builder.txt"));
        try (var worker = new GenericContainer<>(image)
                .withStartupTimeout(Duration.ofMinutes(5))
                .withEnv("REGELSUCHE_TEST_LAUNCHER", "/product/bin/regelsuche")
                .withCopyFileToContainer(MountableFile.forHostPath(extracted.resolve(product)), "/product")
                .withCopyFileToContainer(MountableFile.forHostPath(root.resolve("docs/examples/linear-solution.json")), "/input/proof.json")) {
            for (String file : List.of("LICENSE", "README.md", "pyproject.toml", "src", "tests")) {
                worker.withCopyFileToContainer(MountableFile.forHostPath(root.resolve("python").resolve(file)), "/client/" + file);
            }
            worker.start();
            command(worker, reports, "launcher.log", "chmod", "0755", "/product/bin/regelsuche");
            command(worker, reports, "build.log", "python", "-m", "pip", "wheel", "--disable-pip-version-check",
                "--no-index", "--no-build-isolation", "--no-deps", "/client", "--wheel-dir", "/wheels");
            var wheels = command(worker, reports, "wheel-inventory.log", "ls", "-1", "/wheels").lines().toList();
            assertEquals(1, wheels.size(), "Expected one freshly built client wheel");
            String wheel = wheels.getFirst();
            assertTrue(wheel.matches("regelsuche_client-[A-Za-z0-9_.+-]+\\.whl"), () -> "Unexpected wheel " + wheel);
            Files.createDirectories(reports.resolve("wheels"));
            worker.copyFileFromContainer("/wheels/" + wheel, reports.resolve("wheels").resolve(wheel).toString());
            command(worker, reports, "venv.log", "python", "-m", "venv", "/consumer");
            String python = "/consumer/bin/python";
            command(worker, reports, "install.log", python, "-m", "pip", "install", "--no-index", "--no-deps", "/wheels/" + wheel);
            command(worker, reports, "unit.log", python, "-m", "unittest", "discover", "-s", "/client/tests", "-v");
            command(worker, reports, "offline.json", python, "-m", "regelsuche", "verify", "/input/proof.json");
            command(worker, reports, "live.json", python, "/client/tests/live_client.py");
            command(worker, reports, "java-example.json", "java", "--class-path", "/product/lib/*", "/product/examples/LinearSolve.java");
            worker.copyFileToContainer(MountableFile.forHostPath(reports.resolve("java-example.json")), "/input/java-example.json");
            command(worker, reports, "java-example-verification.json", python, "-m", "regelsuche", "verify", "/input/java-example.json");
        }
    }

    private static String command(GenericContainer<?> worker, Path reports, String file, String... args)
            throws IOException, InterruptedException {
        var result = worker.execInContainer(args);
        Files.writeString(reports.resolve(file), result.getStdout());
        Files.writeString(reports.resolve(file + ".stderr.log"), result.getStderr());
        assertEquals(0, result.getExitCode(), () -> file + ": " + result.getStderr());
        return result.getStdout();
    }
}
