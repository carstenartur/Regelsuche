package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

/** The unchanged frozen matrix, Java worker and independent SymPy source-only judge execute in one pinned image. */
class PolynomialComparisonContainerIT {
    private static final String RESULTS = "build/reports/external-polynomial-comparison";
    private static final String PILOT = "external_polynomial_comparison.run";

    @Test void reproduceTheRegisteredExperimentAndRecheckTheRetainedRows() throws Exception {
        Path root = Path.of("..").toRealPath();
        Path reports = Files.createDirectories(Path.of("target/reports/polynomial-comparison").toAbsolutePath());
        Path stage = Files.createDirectory(Path.of("target/polynomial-worker-input").toAbsolutePath());
        var identity = QualityCollectionCheckout.identity(root);
        var proof = PolynomialSourceProof.prepare(root);
        var verified = PolynomialSourceProof.verify(root, proof, identity);
        Path source = stage.resolve("source.json");
        if (Files.exists(root.resolve(".git"))) QualityArchiveRegistration.write(root, source);
        else Files.copy(root.resolve(QualityArchiveRegistration.RECEIPT), source);
        Path receipt = stage.resolve("polynomial-source.json");
        Files.writeString(receipt, proof.toString() + "\n");
        Files.writeString(reports.resolve("verified-source.json"), verified.toString() + "\n");
        var image = new ImageFromDockerfile().withFileFromPath("Dockerfile", root.resolve("Dockerfile.external-python"))
            .withFileFromPath("requirements.txt", root.resolve("regelsuche-sympy-jmh/requirements.txt"));
        try (var worker = new GenericContainer<>(image).withWorkingDirectory("/pilot")
                .withEnv("PYTHONPATH", "/pilot/scripts").withEnv("PYTHONDONTWRITEBYTECODE", "1")) {
            for (String input : List.of("scripts/external_polynomial_comparison", "scripts/sympy_rule_reference",
                    "config/benchmarks/external-polynomial-v1.json", PolynomialSourceProof.ORIGINAL.toString()))
                copy(worker, root.resolve(input), "/pilot/" + input);
            copy(worker, source, "/pilot/" + QualityArchiveRegistration.RECEIPT);
            copy(worker, receipt, "/pilot/" + PolynomialSourceProof.RECEIPT);
            var classpath = new ArrayList<String>();
            for (var member : runtime(root)) {
                String target = "/pilot/runtime/" + classpath.size() + "/" + member.getFileName();
                copy(worker, member, target);
                classpath.add(target);
            }
            Path launch = stage.resolve("classpath.txt");
            Files.writeString(launch, String.join(":", classpath));
            copy(worker, launch, "/pilot/classpath.txt");
            worker.start();
            run(worker, reports, "runtime", "python", "-c",
                "import sys,sympy,mpmath; assert sys.version_info[:3]==(3,12,12) and sympy.__version__=='1.14.0' and mpmath.__version__=='1.3.0'; print(sys.version,sympy.__version__,mpmath.__version__)");
            run(worker, reports, "reference-controls", "python", "-m", "unittest", "discover", "-s", "scripts/sympy_rule_reference", "-p", "test_*.py", "-v");
            run(worker, reports, "pilot-controls", "python", "-m", "unittest", "external_polynomial_comparison.test_comparison", "external_polynomial_comparison.test_registered_source", "-v");
            try {
                run(worker, reports, "execute", "python", "-m", PILOT, "--classpath", "/pilot/classpath.txt",
                    "--source-proof", PolynomialSourceProof.RECEIPT.toString(), "--output", RESULTS);
                run(worker, reports, "verify", "python", "-m", PILOT, "--verify", "--output", RESULTS);
            } finally {
                var archived = worker.execInContainer("tar", "-czf", "/pilot/evidence.tgz", "-C", "/pilot", RESULTS);
                if (archived.getExitCode() == 0) worker.copyFileFromContainer("/pilot/evidence.tgz", reports.resolve("evidence.tgz").toString());
            }
            for (String file : List.of("protocol.json", "metadata.json", "rows.json", "canonical-rows.json",
                    "paired-comparisons.json", "summary.json", "manifest.json", "report.md", "java.stderr.txt", "sympy.stderr.txt"))
                worker.copyFileFromContainer("/pilot/" + RESULTS + "/" + file, reports.resolve(file).toString());
            var metadata = QualityAcceptanceCollection.JSON.readTree(reports.resolve("metadata.json").toFile());
            assertEquals(identity.required("revision"), metadata.required("revision"));
            assertEquals(PolynomialSourceProof.PILOT, metadata.required("freezeCommit").textValue());
            for (var name : List.of("java", "sympy"))
                assertEquals("READY", metadata.required("setup").required(name).required("ready").required("status").textValue(), name);
        }
    }

    static List<Path> runtime(Path root) throws Exception {
        Path target = root.resolve("regelsuche-learning/target");
        var members = new ArrayList<Path>();
        members.add(target.resolve("classes").toRealPath());
        var paths = Files.readString(target.resolve("polynomial-runtime-classpath.txt")).strip();
        assertFalse(paths.isEmpty(), "Maven runtime resolution returned no dependencies");
        for (String name : paths.split(Pattern.quote(File.pathSeparator), -1)) {
            Path file = Path.of(name);
            assertTrue(file.isAbsolute() && (Files.isRegularFile(file) || Files.isDirectory(file)), name);
            members.add(file.toRealPath());
        }
        assertEquals(members.size(), new HashSet<>(members).size(), "duplicate resolved runtime member");
        return members;
    }

    private static void copy(GenericContainer<?> worker, Path source, String target) {
        worker.withCopyFileToContainer(MountableFile.forHostPath(source), target);
    }
    private static void run(GenericContainer<?> worker, Path reports, String name, String... command) throws Exception {
        var result = worker.execInContainer(command);
        Files.writeString(reports.resolve(name + ".stdout"), result.getStdout());
        Files.writeString(reports.resolve(name + ".stderr"), result.getStderr());
        assertEquals(0, result.getExitCode(), name + " failed; evidence in " + reports);
    }
}
