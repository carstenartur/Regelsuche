package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.DifficultyTier;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.ExpectedTerminalClass;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.RevealCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvolutionRewriteProgramHeldOutPublicCodecTest {
    @TempDir
    Path tempDir;

    private final EvolutionRewriteProgramHeldOutPublicCodec codec =
        new EvolutionRewriteProgramHeldOutPublicCodec();

    @Test
    void reconstructsValidatedPublicArtifactsFromStringsAndFiles()
            throws Exception {
        EvolutionRewriteProgramHeldOutRevealBundle bundle = bundle();
        EvolutionRewriteProgramHeldOutCommitment commitment =
            bundle.commitment();
        EvolutionRewriteProgramHeldOutSplitReferences references =
            EvolutionRewriteProgramHeldOutSplitReferences.create(bundle);

        assertEquals(
            commitment,
            codec.readCommitment(commitment.toCanonicalJson()));
        assertEquals(
            references,
            codec.readSplitReferences(references.toCanonicalJson()));

        Path commitmentPath = tempDir.resolve("commitment.json");
        Path referencesPath = tempDir.resolve("split-references.json");
        Files.writeString(commitmentPath, commitment.toCanonicalJson());
        Files.writeString(referencesPath, references.toCanonicalJson());

        assertEquals(commitment, codec.readCommitment(commitmentPath));
        assertEquals(references, codec.readSplitReferences(referencesPath));
    }

    @Test
    void rejectsUnknownFieldsTrailingValuesAndHashSubstitution() {
        EvolutionRewriteProgramHeldOutCommitment commitment =
            bundle().commitment();
        String canonical = commitment.toCanonicalJson();
        String unknown = canonical.substring(0, canonical.length() - 1)
            + ",\"unexpected\":true}";
        String substituted = canonical.replace(
            commitment.sealedRevealHash(),
            EvolutionGenome.hash("another-private-reveal"));

        assertThrows(
            IllegalArgumentException.class,
            () -> codec.readCommitment(unknown));
        assertThrows(
            IllegalArgumentException.class,
            () -> codec.readCommitment(canonical + "{}"));
        assertThrows(
            IllegalArgumentException.class,
            () -> codec.readCommitment(substituted));
    }

    @Test
    void rejectsMalformedUtf8AndOversizedPublicArtifacts() throws Exception {
        Path malformed = tempDir.resolve("malformed.json");
        Files.write(malformed, new byte[] {(byte) 0xc3, 0x28});
        Path oversized = tempDir.resolve("oversized.json");
        try (var output = Files.newOutputStream(oversized)) {
            byte[] block = new byte[8_192];
            long remaining =
                EvolutionRewriteProgramHeldOutPublicCodec
                    .MAX_PUBLIC_ARTIFACT_BYTES + 1;
            while (remaining > 0) {
                int length = Math.toIntExact(Math.min(block.length, remaining));
                output.write(block, 0, length);
                remaining -= length;
            }
        }

        assertThrows(
            IllegalArgumentException.class,
            () -> codec.readCommitment(malformed));
        assertThrows(
            IllegalArgumentException.class,
            () -> codec.readCommitment(oversized));
    }

    private static EvolutionRewriteProgramHeldOutRevealBundle bundle() {
        return EvolutionRewriteProgramHeldOutRevealBundle.create(
            "flagship_rewrite_program_v1",
            Split.VALIDATION,
            List.of(RevealCase.create(
                "validation_public_codec_case",
                "public_codec_family",
                "(q^3 - 1) / (q - 1)",
                "q^2 + q + 1",
                List.of("q - 1 != 0"),
                DifficultyTier.HARD,
                ExpectedTerminalClass.CONFIRMED)));
    }
}
