package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExecutionProtocolBoundCheckpointDocumentationContractTest {

    @Test
    void executionProtocolSchemasAndDurableCheckpointRemainDiscoverable()
            throws Exception {
        Path root = repositoryRoot();
        String catalog = Files.readString(
            root.resolve("docs/schema-catalog.md"), StandardCharsets.UTF_8);
        String durableDocumentation = Files.readString(
            root.resolve(
                "docs/evolution-rewrite-program-durable-execution-checkpoints.md"),
            StandardCharsets.UTF_8);

        for (String schema : List.of(
                "regelsuche-evolution-rewrite-program-population-execution-protocol-v1.schema.json",
                "regelsuche-evolution-rewrite-program-population-execution-plan-v1.schema.json",
                "regelsuche-evolution-rewrite-program-execution-protocol-bound-retained-run-v1.schema.json",
                "regelsuche-evolution-rewrite-program-execution-protocol-bound-checkpoint-v1.schema.json",
                "regelsuche-evolution-rewrite-program-execution-protocol-bound-checkpoint-artifact-v1.schema.json")) {
            assertTrue(catalog.contains(schema), () -> "schema missing from catalog: " + schema);
        }
        assertTrue(durableDocumentation.contains(
            ExecutionProtocolBoundEvolutionRewriteProgramCheckpointArtifact.SCHEMA));
        assertTrue(durableDocumentation.contains(
            ExecutionProtocolBoundEvolutionRewriteProgramCheckpointArtifact.COMMIT_PROTOCOL));
    }

    private static Path repositoryRoot() {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("repository root not found");
        }
        return root;
    }
}
