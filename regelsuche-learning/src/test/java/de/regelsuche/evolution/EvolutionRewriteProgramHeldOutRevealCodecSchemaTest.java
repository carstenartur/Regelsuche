package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramHeldOutRevealCodecSchemaTest {
    @Test
    void publicSplitReferenceSchemaIsHashOnlyAndFailClosed()
            throws Exception {
        String schema = Files.readString(repositoryRoot()
            .resolve("docs/schemas/")
            .resolve("regelsuche-evolution-rewrite-program-held-out-split-references-v1.schema.json"));

        assertTrue(schema.contains(
            "regelsuche.evolution-rewrite-program-held-out-split-references/v1"));
        assertTrue(schema.contains("\"revealBundleHash\""));
        assertTrue(schema.contains("\"hiddenTargetHash\""));
        assertTrue(schema.contains("\"additionalProperties\": false"));
        assertTrue(!schema.contains("inputExpression"));
        assertTrue(!schema.contains("targetExpression"));
        assertTrue(!schema.contains("assumptions"));
        for (EvolutionRewriteProgramHeldOutCommitment.Split split :
                EvolutionRewriteProgramHeldOutCommitment.Split.values()) {
            assertTrue(schema.contains("\"" + split.name() + "\""),
                split.name());
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
