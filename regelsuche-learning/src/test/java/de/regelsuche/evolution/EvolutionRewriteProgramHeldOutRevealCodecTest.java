package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.DifficultyTier;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.ExpectedTerminalClass;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.RevealCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvolutionRewriteProgramHeldOutRevealCodecTest {
    private final EvolutionRewriteProgramHeldOutRevealCodec codec =
        new EvolutionRewriteProgramHeldOutRevealCodec();

    @Test
    void privateRoundTripRecomputesAllHashes(@TempDir Path directory)
            throws Exception {
        EvolutionRewriteProgramHeldOutRevealBundle bundle = bundle();
        Path privateFile = directory.resolve("validation-private.json");

        codec.writePrivate(privateFile, bundle);
        EvolutionRewriteProgramHeldOutRevealBundle restored =
            codec.readPrivate(privateFile);

        assertEquals(bundle.contentHash(), restored.contentHash());
        assertEquals(bundle.commitment(), restored.commitment());
        assertEquals(bundle.privateCanonicalJson(),
            Files.readString(privateFile, StandardCharsets.UTF_8));
        if (Files.getFileStore(privateFile)
                .supportsFileAttributeView("posix")) {
            assertEquals(
                Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(privateFile));
        }
    }

    @Test
    void publicExportContainsNoPrivateExpressionOrAssumption(
            @TempDir Path directory) throws Exception {
        EvolutionRewriteProgramHeldOutRevealBundle bundle = bundle();
        Path commitment = directory.resolve("commitment.json");
        Path references = directory.resolve("references.json");

        var artifacts = codec.writePublicArtifacts(
            commitment,
            references,
            bundle);
        String commitmentJson = Files.readString(
            commitment, StandardCharsets.UTF_8);
        String referencesJson = Files.readString(
            references, StandardCharsets.UTF_8);

        for (String privateText : List.of(
            "((u + 2) * p) / ((u + 2) * q)",
            "p / q",
            "q != 0",
            "u+2 != 0")) {
            assertFalse(commitmentJson.contains(privateText), privateText);
            assertFalse(referencesJson.contains(privateText), privateText);
        }
        assertTrue(commitmentJson.contains("\"targetHash\""));
        assertTrue(referencesJson.contains("\"hiddenTargetHash\""));
        assertEquals(bundle.contentHash(), artifacts.revealBundleHash());
        assertEquals(
            bundle.commitment().contentHash(), artifacts.commitmentHash());
        assertEquals(
            EvolutionRewriteProgramHeldOutSplitReferences.create(bundle)
                .contentHash(),
            artifacts.splitReferencesHash());
    }

    @Test
    void unknownFieldsAndTamperedPrivateValuesFailClosed() {
        EvolutionRewriteProgramHeldOutRevealBundle bundle = bundle();
        String canonical = bundle.privateCanonicalJson();
        String unknown = canonical.replaceFirst(
            "\\{",
            "{\"unexpected\":true,");
        assertThrows(IllegalArgumentException.class,
            () -> codec.readPrivate(unknown));

        String tamperedTarget = canonical.replace(
            "p / q",
            "(p + 1) / q");
        assertNotEquals(canonical, tamperedTarget);
        assertThrows(IllegalArgumentException.class,
            () -> codec.readPrivate(tamperedTarget));

        String tamperedRoot = canonical.replace(
            bundle.contentHash(),
            EvolutionGenome.hash("another-bundle"));
        assertThrows(IllegalArgumentException.class,
            () -> codec.readPrivate(tamperedRoot));
    }

    @Test
    void publicOutputsMustBeDistinct(@TempDir Path directory) {
        Path output = directory.resolve("public.json");
        assertThrows(IllegalArgumentException.class, () ->
            codec.writePublicArtifacts(output, output, bundle()));
    }

    private static EvolutionRewriteProgramHeldOutRevealBundle bundle() {
        return EvolutionRewriteProgramHeldOutRevealBundle.create(
            "held_out_codec_study_v1",
            Split.VALIDATION,
            List.of(RevealCase.create(
                "validation_affine_cancel",
                "validation_affine_shift",
                "((u+2)*p)/((u+2)*q)",
                "p/q",
                List.of("q != 0", "u+2 != 0"),
                DifficultyTier.STANDARD,
                ExpectedTerminalClass.CONFIRMED)));
    }
}
