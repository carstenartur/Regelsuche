package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.EvolutionPopulationEngine.TerminalOutcome;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramHeldOutRevealAuthorizationSchemaTest {
    @Test
    void schemaContainsEveryRuntimeStageSplitAndPopulationOutcome()
            throws Exception {
        String schema = Files.readString(repositoryRoot()
            .resolve("docs/schemas/")
            .resolve("regelsuche-evolution-rewrite-program-held-out-reveal-authorization-v1.schema.json"));

        assertTrue(schema.contains(
            EvolutionRewriteProgramHeldOutRevealAuthorization.SCHEMA));
        assertTrue(schema.contains("\"additionalProperties\": false"));
        for (Split value : Split.values()) {
            assertTrue(schema.contains("\"" + value.name() + "\""),
                value.name());
        }
        for (EvolutionRewriteProgramHeldOutRevealAuthorization.Stage value :
                EvolutionRewriteProgramHeldOutRevealAuthorization.Stage.values()) {
            assertTrue(schema.contains("\"" + value.name() + "\""),
                value.name());
        }
        for (TerminalOutcome value : TerminalOutcome.values()) {
            assertTrue(schema.contains("\"" + value.name() + "\""),
                value.name());
        }
        assertTrue(schema.contains("\"RESERVED\""));
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
