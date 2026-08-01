package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.DifficultyTier;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.ExpectedTerminalClass;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.RevealCase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Local-only sealing command for private held-out case drafts.
 *
 * <p>The input contains concrete case material but no caller-supplied hashes.
 * Runtime records compute and validate all case and bundle identities. The
 * command writes one private sealed bundle and two public hash-only artifacts.
 * It never prints concrete expressions or assumptions.</p>
 */
public final class EvolutionRewriteProgramHeldOutSealTool {
    public static final String DRAFT_SCHEMA =
        "regelsuche.evolution-rewrite-program-held-out-reveal-draft/v1";

    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final EvolutionRewriteProgramHeldOutRevealCodec codec =
        new EvolutionRewriteProgramHeldOutRevealCodec();

    public SealResult seal(
        Path draftInput,
        Path privateBundleOutput,
        Path commitmentOutput,
        Path splitReferencesOutput
    ) {
        Path draft = normalized(draftInput);
        Path privateOutput = normalized(privateBundleOutput);
        Path commitment = normalized(commitmentOutput);
        Path references = normalized(splitReferencesOutput);
        requireDistinct(draft, privateOutput, commitment, references);

        DraftDto dto = readDraft(draft);
        EvolutionRewriteProgramHeldOutRevealBundle bundle = dto.toBundle();
        codec.writePrivate(privateOutput, bundle);
        EvolutionRewriteProgramHeldOutRevealCodec.PublicArtifacts publicArtifacts =
            codec.writePublicArtifacts(commitment, references, bundle);
        return new SealResult(
            privateOutput,
            publicArtifacts.commitmentPath(),
            publicArtifacts.splitReferencesPath(),
            publicArtifacts.commitmentHash(),
            publicArtifacts.splitReferencesHash(),
            bundle.contentHash());
    }

    private static DraftDto readDraft(Path input) {
        try {
            String json = Files.readString(input, StandardCharsets.UTF_8);
            DraftDto dto = JSON.readValue(json, DraftDto.class);
            if (!DRAFT_SCHEMA.equals(dto.schema())) {
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

    private static Path normalized(Path path) {
        return Objects.requireNonNull(path, "path")
            .toAbsolutePath()
            .normalize();
    }

    private static void requireDistinct(Path... paths) {
        for (int left = 0; left < paths.length; left++) {
            for (int right = left + 1; right < paths.length; right++) {
                if (paths[left].equals(paths[right])) {
                    throw new IllegalArgumentException(
                        "held-out draft and output paths must be distinct");
                }
            }
        }
    }

    public static void main(String[] args) {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                "usage: <private-draft.json> <sealed-private.json> "
                    + "<public-commitment.json> <public-split-references.json>");
        }
        new EvolutionRewriteProgramHeldOutSealTool().seal(
            Path.of(args[0]),
            Path.of(args[1]),
            Path.of(args[2]),
            Path.of(args[3]));
    }

    public record SealResult(
        Path privateBundlePath,
        Path commitmentPath,
        Path splitReferencesPath,
        String commitmentHash,
        String splitReferencesHash,
        String revealBundleHash
    ) {
        public SealResult {
            privateBundlePath = normalized(privateBundlePath);
            commitmentPath = normalized(commitmentPath);
            splitReferencesPath = normalized(splitReferencesPath);
            EvolutionGenome.requireSha256(commitmentHash, "commitmentHash");
            EvolutionGenome.requireSha256(
                splitReferencesHash, "splitReferencesHash");
            EvolutionGenome.requireSha256(
                revealBundleHash, "revealBundleHash");
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

        private EvolutionRewriteProgramHeldOutRevealBundle toBundle() {
            return EvolutionRewriteProgramHeldOutRevealBundle.create(
                studyId,
                split,
                cases.stream().map(CaseDto::toRuntime).toList());
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

        private RevealCase toRuntime() {
            return RevealCase.create(
                caseId,
                familyId,
                inputExpression,
                targetExpression,
                assumptions,
                difficultyTier,
                expectedTerminalClass);
        }
    }
}
