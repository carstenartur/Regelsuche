package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.equivalence.AssumptionAwareEquivalenceService;
import de.regelsuche.equivalence.AssumptionAwareEquivalenceService.Evaluation;
import de.regelsuche.equivalence.AssumptionAwareEquivalenceService.Status;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.DifficultyTier;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.ExpectedTerminalClass;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.RevealCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
        assertEquals(
            List.of("studyId", "split", "cases"),
            Arrays.stream(
                    FlagshipHeldOutDraftSealCommand.ValidatedSealResult.class
                        .getRecordComponents())
                .map(component -> component.getName())
                .toList());
    }

    @Test
    void validatesTheExactNormalizedValuesThatAreSealed() throws Exception {
        Path draft = tempDir.resolve("canonical-draft.json");
        Path privateBundle = tempDir.resolve("canonical-private.json");
        Path commitment = tempDir.resolve("canonical-commitment.json");
        Path references = tempDir.resolve("canonical-references.json");
        Files.writeString(
            draft,
            singleConfirmedDraft(
                " ((u+3)*a)/((u+3)*b) ",
                " a/b ",
                List.of(" b != 0 ", "u+3!=0")),
            StandardCharsets.UTF_8);

        RevealCase expected = RevealCase.create(
            "validation_exact_case",
            "shifted_factor_family",
            "((u + 3) * a) / ((u + 3) * b)",
            "a / b",
            List.of(" b != 0 ", "u+3!=0"),
            DifficultyTier.STANDARD,
            ExpectedTerminalClass.CONFIRMED);
        AtomicReference<String> actualLeft = new AtomicReference<>();
        AtomicReference<String> actualRight = new AtomicReference<>();
        AtomicReference<List<String>> actualAssumptions = new AtomicReference<>();
        AssumptionAwareEquivalenceService evaluator = (left, right, assumptions) -> {
            actualLeft.set(left);
            actualRight.set(right);
            actualAssumptions.set(List.copyOf(assumptions));
            return new Evaluation(
                Status.CONFIRMED,
                true,
                "normal-form",
                "normal-form",
                List.of(),
                assumptions,
                List.of(),
                List.of(),
                "confirmed");
        };

        new FlagshipHeldOutDraftSealCommand(
            evaluator,
            new EvolutionRewriteProgramHeldOutSealTool())
            .validateAndSeal(
                draft,
                privateBundle,
                commitment,
                references);

        assertEquals(expected.inputExpression(), actualLeft.get());
        assertEquals(expected.targetExpression(), actualRight.get());
        assertEquals(expected.assumptions(), actualAssumptions.get());
        EvolutionRewriteProgramHeldOutRevealBundle sealed =
            new EvolutionRewriteProgramHeldOutRevealCodec()
                .readPrivate(privateBundle);
        EvolutionRewriteProgramHeldOutRevealBundle expectedBundle =
            EvolutionRewriteProgramHeldOutRevealBundle.create(
                "flagship_rewrite_program_v1",
                Split.VALIDATION,
                List.of(expected));
        assertEquals(expectedBundle.contentHash(), sealed.contentHash());
        assertEquals(expectedBundle.splitReferences(), sealed.splitReferences());
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
        assertNoOutputs(privateBundle, commitment, references);
    }

    @Test
    void confirmedCaseWithUnresolvedAssumptionsFailsBeforeWriting() throws Exception {
        Path draft = tempDir.resolve("unresolved.json");
        Path privateBundle = tempDir.resolve("unresolved-private.json");
        Path commitment = tempDir.resolve("unresolved-commitment.json");
        Path references = tempDir.resolve("unresolved-references.json");
        Files.writeString(
            draft,
            singleConfirmedDraft(
                "((u + 3) * a) / ((u + 3) * b)",
                "a / b",
                List.of("b != 0")),
            StandardCharsets.UTF_8);
        AssumptionAwareEquivalenceService evaluator = (left, right, assumptions) ->
            new Evaluation(
                Status.CONFIRMED,
                true,
                "normal-form",
                "normal-form",
                List.of("u + 3 != 0", "b != 0"),
                assumptions,
                List.of("u + 3 != 0"),
                List.of(),
                "confirmed with unresolved assumption");

        assertThrows(
            IllegalArgumentException.class,
            () -> new FlagshipHeldOutDraftSealCommand(
                evaluator,
                new EvolutionRewriteProgramHeldOutSealTool())
                .validateAndSeal(
                    draft,
                    privateBundle,
                    commitment,
                    references));
        assertNoOutputs(privateBundle, commitment, references);
    }

    @Test
    void wrongSchemaReportsExpectedAndActualWithoutCaseMaterial() throws Exception {
        Path draft = tempDir.resolve("wrong-schema.json");
        Files.writeString(
            draft,
            validDraft().replace(
                "regelsuche.evolution-rewrite-program-held-out-reveal-draft/v1",
                "regelsuche.evolution-rewrite-program-held-out-reveal-draft/v0"),
            StandardCharsets.UTF_8);

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> new FlagshipHeldOutDraftSealCommand().validateAndSeal(
                draft,
                tempDir.resolve("wrong-schema-private.json"),
                tempDir.resolve("wrong-schema-commitment.json"),
                tempDir.resolve("wrong-schema-references.json")));
        assertTrue(error.getMessage().contains("expected="));
        assertTrue(error.getMessage().contains("actual="));
        assertFalse(error.getMessage().contains("validation_exact_case"));
        assertFalse(error.getMessage().contains("u + 3"));
    }

    private static void assertNoOutputs(Path... outputs) {
        for (Path output : outputs) {
            assertFalse(Files.exists(output), () -> "unexpected output: " + output);
        }
    }

    private static String singleConfirmedDraft(
        String input,
        String target,
        List<String> assumptions
    ) {
        String assumptionJson = assumptions.stream()
            .map(value -> "\"" + value + "\"")
            .collect(java.util.stream.Collectors.joining(","));
        return """
            {
              "schema":"regelsuche.evolution-rewrite-program-held-out-reveal-draft/v1",
              "studyId":"flagship_rewrite_program_v1",
              "split":"VALIDATION",
              "cases":[{
                "caseId":"validation_exact_case",
                "familyId":"shifted_factor_family",
                "inputExpression":"%s",
                "targetExpression":"%s",
                "assumptions":[%s],
                "difficultyTier":"STANDARD",
                "expectedTerminalClass":"CONFIRMED"
              }]
            }
            """.formatted(input, target, assumptionJson);
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
