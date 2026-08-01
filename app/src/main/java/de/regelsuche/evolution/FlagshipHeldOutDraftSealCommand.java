package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.equivalence.AssumptionAwareEquivalenceService;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.DifficultyTier;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.ExpectedTerminalClass;
import de.regelsuche.math.algorithms.equivalence.RationalFunctionNormalFormEquivalencePortAdapter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Trusted local command that validates private held-out material with the exact
 * rational evaluator before any seal artifact is written.
 */
public final class FlagshipHeldOutDraftSealCommand {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final AssumptionAwareEquivalenceService equivalence;
    private final EvolutionRewriteProgramHeldOutSealTool sealTool;

    public FlagshipHeldOutDraftSealCommand() {
        this(
            new RationalFunctionNormalFormEquivalencePortAdapter(),
            new EvolutionRewriteProgramHeldOutSealTool());
    }

    FlagshipHeldOutDraftSealCommand(
        AssumptionAwareEquivalenceService equivalence,
        EvolutionRewriteProgramHeldOutSealTool sealTool
    ) {
        this.equivalence = Objects.requireNonNull(equivalence, "equivalence");
        this.sealTool = Objects.requireNonNull(sealTool, "sealTool");
    }

    public ValidatedSealResult validateAndSeal(
        Path draftInput,
        Path privateBundleOutput,
        Path commitmentOutput,
        Path splitReferencesOutput
    ) {
        DraftDto draft = readDraft(draftInput);
        List<CaseValidation> validations = draft.cases().stream()
            .map(this::validateCase)
            .toList();
        EvolutionRewriteProgramHeldOutSealTool.SealResult sealed =
            sealTool.seal(
                draftInput,
                privateBundleOutput,
                commitmentOutput,
                splitReferencesOutput);
        return new ValidatedSealResult(
            draft.studyId(),
            draft.split(),
            validations,
            sealed);
    }

    private CaseValidation validateCase(CaseDto heldOutCase) {
        AssumptionAwareEquivalenceService.Evaluation evaluation =
            equivalence.evaluate(
                heldOutCase.inputExpression(),
                heldOutCase.targetExpression(),
                heldOutCase.assumptions());
        ExpectedTerminalClass actual = ExpectedTerminalClass.valueOf(
            evaluation.status().name());
        if (actual != heldOutCase.expectedTerminalClass()) {
            throw new IllegalArgumentException(
                "held-out case terminal-class mismatch for "
                    + heldOutCase.caseId()
                    + ": expected=" + heldOutCase.expectedTerminalClass()
                    + ", actual=" + actual);
        }
        if (actual == ExpectedTerminalClass.CONFIRMED
                && (!evaluation.missingAssumptions().isEmpty()
                    || !evaluation.unsupportedAssumptions().isEmpty())) {
            throw new IllegalArgumentException(
                "confirmed held-out case retains unresolved assumptions: "
                    + heldOutCase.caseId());
        }
        return new CaseValidation(
            heldOutCase.caseId(),
            actual,
            EvolutionGenome.hash(evaluation.leftNormalForm()),
            EvolutionGenome.hash(evaluation.rightNormalForm()));
    }

    private static DraftDto readDraft(Path input) {
        Path normalized = Objects.requireNonNull(input, "input")
            .toAbsolutePath()
            .normalize();
        try {
            DraftDto dto = JSON.readValue(
                Files.readString(normalized, StandardCharsets.UTF_8),
                DraftDto.class);
            if (!EvolutionRewriteProgramHeldOutSealTool.DRAFT_SCHEMA.equals(
                    dto.schema())) {
                throw new IllegalArgumentException(
                    "unsupported held-out reveal draft schema");
            }
            return dto;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "invalid held-out reveal draft JSON", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                "unable to read held-out reveal draft", exception);
        }
    }

    public static void main(String[] args) {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                "usage: <private-draft.json> <sealed-private.json> "
                    + "<public-commitment.json> <public-split-references.json>");
        }
        new FlagshipHeldOutDraftSealCommand().validateAndSeal(
            Path.of(args[0]),
            Path.of(args[1]),
            Path.of(args[2]),
            Path.of(args[3]));
    }

    public record CaseValidation(
        String caseId,
        ExpectedTerminalClass terminalClass,
        String leftNormalFormHash,
        String rightNormalFormHash
    ) {
        public CaseValidation {
            Objects.requireNonNull(caseId, "caseId");
            Objects.requireNonNull(terminalClass, "terminalClass");
            EvolutionGenome.requireSha256(
                leftNormalFormHash, "leftNormalFormHash");
            EvolutionGenome.requireSha256(
                rightNormalFormHash, "rightNormalFormHash");
        }
    }

    public record ValidatedSealResult(
        String studyId,
        Split split,
        List<CaseValidation> cases,
        EvolutionRewriteProgramHeldOutSealTool.SealResult seal
    ) {
        public ValidatedSealResult {
            Objects.requireNonNull(studyId, "studyId");
            Objects.requireNonNull(split, "split");
            cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
            Objects.requireNonNull(seal, "seal");
        }
    }

    private record DraftDto(
        String schema,
        String studyId,
        Split split,
        List<CaseDto> cases
    ) {
        private DraftDto {
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(studyId, "studyId");
            Objects.requireNonNull(split, "split");
            cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        }
    }

    private record CaseDto(
        String caseId,
        String familyId,
        String inputExpression,
        String targetExpression,
        List<String> assumptions,
        DifficultyTier difficultyTier,
        ExpectedTerminalClass expectedTerminalClass
    ) {
        private CaseDto {
            Objects.requireNonNull(caseId, "caseId");
            Objects.requireNonNull(familyId, "familyId");
            Objects.requireNonNull(inputExpression, "inputExpression");
            Objects.requireNonNull(targetExpression, "targetExpression");
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            Objects.requireNonNull(difficultyTier, "difficultyTier");
            Objects.requireNonNull(
                expectedTerminalClass, "expectedTerminalClass");
        }
    }
}
