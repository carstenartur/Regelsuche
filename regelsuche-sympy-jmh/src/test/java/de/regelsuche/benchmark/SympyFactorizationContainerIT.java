package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.quality.jmh.SympyFactorizationReport;
import de.regelsuche.quality.jmh.JmhPublishedFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

/** Both the embedded GraalPy forks and one-shot CPython use this digest-pinned runtime. */
class SympyFactorizationContainerIT {
    @Test
    void measureAndVerifyAllSpecialistAndGeneralTracks() throws Exception {
        Path root = Path.of(System.getProperty("regelsuche.repositoryRoot"));
        Path target = Path.of(System.getProperty("regelsuche.jmh.target"));
        Path reports = target.resolve("reports/jmh");
        Files.createDirectories(reports);
        var image = new ImageFromDockerfile()
            .withFileFromPath("Dockerfile", root.resolve("Dockerfile.external-python"))
            .withFileFromPath("requirements.txt", root.resolve("regelsuche-sympy-jmh/requirements.txt"));
        try (var worker = new GenericContainer<>(image)
                .withCopyFileToContainer(MountableFile.forHostPath(target.resolve(
                    System.getProperty("regelsuche.jmh.jar"))), "/benchmark/benchmarks.jar")
                .withCopyFileToContainer(MountableFile.forHostPath(target.resolve("benchmark-lib")), "/benchmark/lib")) {
            worker.start();
            var run = worker.execInContainer("java", "-cp", "/benchmark/benchmarks.jar:/benchmark/lib/*",
                "org.openjdk.jmh.Main", "de\\.regelsuche\\.math\\.sympy\\..*FactorizationBenchmarks\\..*",
                "-jvmArgs", "--enable-native-access=ALL-UNNAMED -Xms512m -Xmx2g -Dregelsuche.sympy.python=/usr/local/bin/python",
                "-foe", "true", "-rf", "json", "-rff", "/benchmark/result.json");
            Files.writeString(reports.resolve("stdout.log"), run.getStdout());
            Files.writeString(reports.resolve("stderr.log"), run.getStderr());
            Path raw = reports.resolve("sympy-factorization.json");
            if (worker.execInContainer("test", "-f", "/benchmark/result.json").getExitCode() == 0) {
                worker.copyFileFromContainer("/benchmark/result.json", raw.toString());
            }
            assertEquals(0, run.getExitCode(), () -> "Factorization worker failed. Evidence: " + reports);
            SympyFactorizationReport.main(new String[]{raw.toString(), reports.resolve("summary.json").toString(),
                reports.resolve("index.html").toString()});
            JmhPublishedFiles.FACTORIZATION.publish(root, reports);
        }
    }
}
