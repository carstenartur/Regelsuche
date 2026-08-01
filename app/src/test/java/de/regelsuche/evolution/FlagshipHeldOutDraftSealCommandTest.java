package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.ExpectedTerminalClass;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlagshipHeldOutDraftSealCommandTest {
    @TempDir
    Path tempDir;

    @Test
    void validatesExpectedExactStatusesBeforeWritingArtifacts() throws Exception {
        Path draft = tempDir.resolve("draft.json");
        Path privateBundle = tempDir.resolve("private.json");
        Path commitment = tempDir.resolve("commitment.json");
        Path references = tempDir.resolve("references.json");
        Files.writeString(draft, validDraft(), StandardCharsets.UTF_8);

        var result = new FlagshipHeldOutDraftSealCommand().validateAndSeal(
            draft,
            privateBundle,
            commitment,
            references);

        assertEquals(Split.VALIDATION, result.split());
        assertEquals(2, result.cases().size());
        assertEquals(
            ExpectedTerminalClass.CONFIRMED,
            result.cases().getFirst().terminalClass());
        assertEquals(
            ExpectedTerminalClass.MISSING_ASSUMPTION,
            result.cases().getLast().terminalClass());
        assertTrue(Files.isRegularFile(privateBundle));
        assertTrue(Files.isRegularFile(commitment));
        assertTrue(Files.isRegularFile(references));
    }

    @Test
    void terminalMismatchFailsBeforeAnyOutputIsWritten() throws Exception {
        Path draft = tempDir.resolve("mismatch.json");
        Path privateBundle = tempDir.resolve("private-mismatch.json");
        Path commitment = tempDir.resolve("commitment-mismatch.json");
        Path references = tempDir.resolve("references-mismatch.json");
        Files.writeString(
            draft,
            validDraft().replaceFirst(
                "\"expectedTerminalClass\":\"CONFIRMED\"",
                "\"expectedTerminalClass\":\"REFUTED\""),
            StandardCharsets.UTF_8);

        assertThrows(
            IllegalArgumentException.class,
            () -> new FlagshipHeldOutDraftSealCommand().validateAndSeal(
                draft,
                privateBundle,
                commitment,
                references));
        assertFalse(Files.exists(privateBundle));
        assertFalse(Files.exists(commitment));
        assertFalse(Files.exists(references));
    }

    private static String validDraft() {
        return """
            {
              "schema":"regelsuche.evolution-rewrite-program-held-out-reveal-draft/v1",
              "studyId":"flagship_rewrite_program_v1",
              "split":"VALIDATION",
              "cases":[
                {
                  "caseId":"validation_exact_case",
                  "familyId":"shifted_factor_family",
                  "inputExpression":"((u + 3) * a) / ((u + 3) * b)",
                  "targetExpression":"a / b",
                  "assumptions":["u + 3 != 0","b != 0"],
                  "difficultyTier":"STANDARD",
                  "expectedTerminalClass":"CONFIRMED"
                },
                {
                  "caseId":"validation_missing_assumption_case",
                  "familyId":"missing_factor_family",
                  "inputExpression":"(x * p) / (x * q)",
                  "targetExpression":"p / q",
                  "assumptions":["q != 0"],
                  "difficultyTier":"CONTROL",
                  "expectedTerminalClass":"MISSING_ASSUMPTION"
                }
              ]
            }
            """;
    }
}
