package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramHeldOutRevealSchemaContractTest {
    @Test
    void privateSchemaContainsEveryRuntimeSplitAndClassification()
            throws Exception {
        String schema = Files.readString(repositoryRoot()
            .resolve("docs/schemas/")
            .resolve("regelsuche-evolution-rewrite-program-held-out-reveal-bundle-v1.schema.json"));

        assertTrue(schema.contains(
            "regelsuche.evolution-rewrite-program-held-out-reveal-bundle/v1"));
        assertTrue(schema.contains("\"additionalProperties\": false"));
        for (EvolutionRewriteProgramHeldOutCommitment.Split value :
                EvolutionRewriteProgramHeldOutCommitment.Split.values()) {
            assertTrue(schema.contains("\"" + value.name() + "\""),
                value.name());
        }
        for (EvolutionRewriteProgramHeldOutRevealBundle.DifficultyTier value :
                EvolutionRewriteProgramHeldOutRevealBundle.DifficultyTier.values()) {
            assertTrue(schema.contains("\"" + value.name() + "\""),
                value.name());
        }
        for (EvolutionRewriteProgramHeldOutRevealBundle.ExpectedTerminalClass value :
                EvolutionRewriteProgramHeldOutRevealBundle.ExpectedTerminalClass.values()) {
            assertTrue(schema.contains("\"" + value.name() + "\""),
                value.name());
        }
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
