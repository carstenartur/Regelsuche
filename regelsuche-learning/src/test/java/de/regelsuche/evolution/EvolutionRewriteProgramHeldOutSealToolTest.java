package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvolutionRewriteProgramHeldOutSealToolTest {
    @TempDir
    Path tempDir;

    @Test
    void sealsPrivateDraftAndEmitsHashOnlyPublicArtifacts() throws Exception {
        Path draft = tempDir.resolve("validation-draft.json");
        Path privateBundle = tempDir.resolve("validation-private.json");
        Path commitment = tempDir.resolve("validation-commitment.json");
        Path references = tempDir.resolve("validation-references.json");
        Files.writeString(draft, validationDraft(), StandardCharsets.UTF_8);

        var result = new EvolutionRewriteProgramHeldOutSealTool().seal(
            draft,
            privateBundle,
            commitment,
            references);

        var bundle = new EvolutionRewriteProgramHeldOutRevealCodec()
            .readPrivate(privateBundle);
        assertEquals(Split.VALIDATION, bundle.split());
        assertEquals(bundle.contentHash(), result.revealBundleHash());
        assertEquals(bundle.commitment().contentHash(), result.commitmentHash());

        String publicCommitment = Files.readString(
            commitment, StandardCharsets.UTF_8);
        String publicReferences = Files.readString(
            references, StandardCharsets.UTF_8);
        assertTrue(publicCommitment.contains(result.revealBundleHash()));
        assertTrue(publicReferences.contains(result.revealBundleHash()));
        assertFalse(publicCommitment.contains("(x + 1)"));
        assertFalse(publicCommitment.contains("a / b"));
        assertFalse(publicCommitment.contains("x + 1 != 0"));
        assertFalse(publicReferences.contains("(x + 1)"));
        assertFalse(publicReferences.contains("a / b"));
    }

    @Test
    void rejectsUnknownDraftFieldsAndOutputAliasing() throws Exception {
        Path invalid = tempDir.resolve("invalid.json");
        Files.writeString(
            invalid,
            validationDraft().replace(
                "\"studyId\":",
                "\"unexpected\":true,\"studyId\":"),
            StandardCharsets.UTF_8);
        Path output = tempDir.resolve("same.json");

        assertThrows(
            IllegalArgumentException.class,
            () -> new EvolutionRewriteProgramHeldOutSealTool().seal(
                invalid,
                tempDir.resolve("private.json"),
                tempDir.resolve("commitment.json"),
                tempDir.resolve("references.json")));
        assertThrows(
            IllegalArgumentException.class,
            () -> new EvolutionRewriteProgramHeldOutSealTool().seal(
                tempDir.resolve("draft.json"),
                output,
                output,
                tempDir.resolve("references-2.json")));
    }

    private static String validationDraft() {
        return """
            {
              "schema":"regelsuche.evolution-rewrite-program-held-out-reveal-draft/v1",
              "studyId":"flagship_rewrite_program_v1",
              "split":"VALIDATION",
              "cases":[{
                "caseId":"validation_shifted_factor_case",
                "familyId":"shifted_factor_family",
                "inputExpression":"((x + 1) * a) / ((x + 1) * b)",
                "targetExpression":"a / b",
                "assumptions":["x + 1 != 0","b != 0"],
                "difficultyTier":"STANDARD",
                "expectedTerminalClass":"CONFIRMED"
              }]
            }
            """;
    }
}
