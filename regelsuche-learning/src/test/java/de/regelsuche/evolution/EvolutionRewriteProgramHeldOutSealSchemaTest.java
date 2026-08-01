package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.DifficultyTier;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.ExpectedTerminalClass;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramHeldOutSealSchemaTest {
    @Test
    void draftSchemaContainsTheCompleteRuntimeVocabulary() throws Exception {
        String schema = Files.readString(
            Path.of("..", "docs", "schemas",
                "regelsuche-evolution-rewrite-program-held-out-reveal-draft-v1.schema.json"),
            StandardCharsets.UTF_8);

        assertTrue(schema.contains(
            EvolutionRewriteProgramHeldOutSealTool.DRAFT_SCHEMA));
        for (Split split : Split.values()) {
            assertTrue(schema.contains("\"" + split.name() + "\""));
        }
        for (DifficultyTier tier : DifficultyTier.values()) {
            assertTrue(schema.contains("\"" + tier.name() + "\""));
        }
        for (ExpectedTerminalClass terminal
                : ExpectedTerminalClass.values()) {
            assertTrue(schema.contains("\"" + terminal.name() + "\""));
        }
    }
}
