package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Offline comparison of the already retained, public polynomial evidence. */
@EnabledIfSystemProperty(named = "regelsuche.polynomialParity.enabled", matches = "true")
class PolynomialParityQualificationTest {
    private static final String PYTHON_IMAGE =
        "python:3.12.12-slim-bookworm@sha256:593bd06efe90efa80dc4eee3948be7c0fde4134606dd40d8dd8dbcade98e669c";

    @Test
    void qualifiesBothArchivePairsWithoutExecutingSolvers() throws Exception {
        Path root = Path.of("..").toRealPath();
        Path evidence = root.resolve("build/polynomial-parity");
        Path report = evidence.resolve("qualification.json");
        Files.deleteIfExists(report);
        Path source = root.resolve("build/polynomial-parity-source");
        var identity = QualityCollectionCheckout.identity(source);
        String revision = System.getenv("EVIDENCE_REVISION");
        assertNotNull(revision, "the producing revision is mandatory");
        assertEquals(revision, identity.required("revision").textValue());
        assertFalse(identity.required("trackedChangesPresent").booleanValue());
        String tree = identity.required("tree").textValue();

        try (var worker = new GenericContainer<>(DockerImageName.parse(PYTHON_IMAGE))
                .withCommand("sleep", "infinity")
                .withNetworkMode("none")
                .withEnv("PYTHONPATH", "/source")
                .withEnv("PYTHONDONTWRITEBYTECODE", "1")
                .withFileSystemBind(source.resolve("scripts").toString(), "/source", BindMode.READ_ONLY)
                .withFileSystemBind(evidence.toString(), "/evidence", BindMode.READ_ONLY)) {
            worker.start();
            var controls = worker.execInContainer("python", "-m", "unittest", "-v",
                "external_polynomial_comparison.test_native_parity",
                "external_polynomial_comparison.test_typed_parity");
            assertEquals(0, controls.getExitCode(), controls::getStderr);
            assertTrue(controls.getStderr().contains("Ran 8 tests"), controls::getStderr);
            assertTrue(controls.getStderr().stripTrailing().endsWith("\nOK"), controls::getStderr);

            var result = worker.execInContainer("python", "-m",
                "external_polynomial_comparison.qualify_parity",
                "/evidence/legacy/build/reports/typed-external-polynomial-comparison",
                "/evidence/typed/regelsuche-release/target/reports/typed-external-polynomial-comparison/extracted/typed-external-polynomial-comparison",
                "/evidence/legacy/build/reports/native-polynomial-comparison",
                "/evidence/native/regelsuche-release/target/reports/native-polynomial-comparison/extracted/native-polynomial-comparison",
                "--revision", revision, "--tree", tree);
            assertEquals(0, result.getExitCode(), result::getStderr);
            var receipt = QualityAcceptanceCollection.JSON.readTree(result.getStdout());
            assertEquals("regelsuche.polynomial-parity-qualification/v1", receipt.required("schema").textValue());
            assertEquals(revision, receipt.required("sourceRevision").textValue());
            assertEquals(tree, receipt.required("sourceTree").textValue());
            assertEquals(729, receipt.required("typed").required("rows").intValue());
            assertEquals(675, receipt.required("native").required("rows").intValue());
            assertEquals(9, receipt.required("native").required("sequences").intValue());
            assertEquals(4, receipt.required("rejectedControls").size());
            Files.writeString(report, result.getStdout());
        }
    }
}
