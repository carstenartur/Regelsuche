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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Strict codec for private held-out reveal files and their public derivatives.
 *
 * <p>Parsing always reconstructs the runtime records so per-case and bundle
 * hashes are recomputed. Public export writes only the commitment and hash-only
 * split-reference artifacts. This class never logs private expressions.</p>
 */
public final class EvolutionRewriteProgramHeldOutRevealCodec {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS =
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    public EvolutionRewriteProgramHeldOutRevealBundle readPrivate(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(
                "held-out reveal JSON must not be blank");
        }
        try {
            BundleDto dto = JSON.readValue(json, BundleDto.class);
            if (!EvolutionRewriteProgramHeldOutRevealBundle.SCHEMA.equals(
                    dto.schema())) {
                throw new IllegalArgumentException(
                    "unsupported held-out reveal JSON schema");
            }
            List<RevealCase> cases = dto.cases().stream()
                .map(CaseDto::toRuntime)
                .toList();
            EvolutionRewriteProgramHeldOutRevealBundle bundle =
                EvolutionRewriteProgramHeldOutRevealBundle.create(
                    dto.studyId(),
                    dto.split(),
                    cases);
            if (!bundle.contentHash().equals(dto.contentHash())) {
                throw new IllegalArgumentException(
                    "held-out reveal JSON contentHash mismatch");
            }
            return bundle;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "invalid held-out reveal JSON", exception);
        }
    }

    public EvolutionRewriteProgramHeldOutRevealBundle readPrivate(Path input) {
        Objects.requireNonNull(input, "input");
        try {
            return readPrivate(Files.readString(
                input.toAbsolutePath().normalize(),
                StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                "unable to read held-out reveal file", exception);
        }
    }

    /** Trusted writer for externally stored private reveal material. */
    public Path writePrivate(
        Path output,
        EvolutionRewriteProgramHeldOutRevealBundle bundle
    ) {
        Objects.requireNonNull(bundle, "bundle");
        return writeAtomic(output, bundle.privateCanonicalJson(), true);
    }

    /**
     * Writes only public, hash-based artifacts. Output paths must be distinct.
     */
    public PublicArtifacts writePublicArtifacts(
        Path commitmentOutput,
        Path splitReferencesOutput,
        EvolutionRewriteProgramHeldOutRevealBundle bundle
    ) {
        Objects.requireNonNull(bundle, "bundle");
        Path commitmentPath = normalized(commitmentOutput);
        Path referencesPath = normalized(splitReferencesOutput);
        if (commitmentPath.equals(referencesPath)) {
            throw new IllegalArgumentException(
                "public commitment and split-reference outputs must differ");
        }
        EvolutionRewriteProgramHeldOutCommitment commitment =
            bundle.commitment();
        EvolutionRewriteProgramHeldOutSplitReferences references =
            EvolutionRewriteProgramHeldOutSplitReferences.create(bundle);
        writeAtomic(commitmentPath, commitment.toCanonicalJson());
        writeAtomic(referencesPath, references.toCanonicalJson());
        return new PublicArtifacts(
            commitmentPath,
            referencesPath,
            commitment.contentHash(),
            references.contentHash(),
            bundle.contentHash());
    }

    private static Path writeAtomic(Path output, String content) {
        return writeAtomic(output, content, false);
    }

    private static Path writeAtomic(
        Path output,
        String content,
        boolean privateArtifact
    ) {
        Path absolute = normalized(output);
        try {
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = Files.createTempFile(
                parent == null ? Path.of(".") : parent,
                ".regelsuche-held-out-",
                ".tmp");
            try {
                Files.writeString(
                    temporary,
                    content,
                    StandardCharsets.UTF_8);
                if (privateArtifact) {
                    restrictPrivatePermissions(temporary);
                }
                try {
                    Files.move(
                        temporary,
                        absolute,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(
                        temporary,
                        absolute,
                        StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return absolute;
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to write held-out artifact", exception);
        }
    }

    private static Path normalized(Path path) {
        return Objects.requireNonNull(path, "path")
            .toAbsolutePath()
            .normalize();
    }

    private static void restrictPrivatePermissions(Path path) {
        try {
            if (Files.getFileStore(path)
                    .supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, PRIVATE_PERMISSIONS);
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to restrict private reveal permissions", exception);
        }
    }

    public record PublicArtifacts(
        Path commitmentPath,
        Path splitReferencesPath,
        String commitmentHash,
        String splitReferencesHash,
        String revealBundleHash
    ) {
        public PublicArtifacts {
            commitmentPath = normalized(commitmentPath);
            splitReferencesPath = normalized(splitReferencesPath);
            EvolutionGenome.requireSha256(
                commitmentHash, "commitmentHash");
            EvolutionGenome.requireSha256(
                splitReferencesHash, "splitReferencesHash");
            EvolutionGenome.requireSha256(
                revealBundleHash, "revealBundleHash");
        }
    }

    private record BundleDto(
        String schema,
        String studyId,
        Split split,
        List<CaseDto> cases,
        String contentHash
    ) {
        private BundleDto {
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(studyId, "studyId");
            Objects.requireNonNull(split, "split");
            cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
            Objects.requireNonNull(contentHash, "contentHash");
        }
    }

    private record CaseDto(
        String caseId,
        String familyId,
        String inputExpression,
        String targetExpression,
        List<String> assumptions,
        DifficultyTier difficultyTier,
        ExpectedTerminalClass expectedTerminalClass,
        String contentHash
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
            Objects.requireNonNull(contentHash, "contentHash");
        }

        private RevealCase toRuntime() {
            return new RevealCase(
                caseId,
                familyId,
                inputExpression,
                targetExpression,
                assumptions,
                difficultyTier,
                expectedTerminalClass,
                contentHash);
        }
    }
}
