package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.DifficultyTier;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.ExpectedTerminalClass;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.RevealCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlagshipRewriteProgramPublicFreezeTest {
    private static final String COMMIT =
        "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path tempDir;

    @Test
    void publicInputsReconstructTheExactPrivateAssembly() {
        EvolutionRewriteProgramHeldOutRevealBundle validation =
            validationBundle("validation_private_symbol");
        EvolutionRewriteProgramHeldOutRevealBundle finalTest =
            finalTestBundle("final_private_symbol");

        var privateAssembly = FlagshipRewriteProgramFreezeAssembler.assemble(
            COMMIT,
            validation,
            finalTest);
        var publicAssembly = FlagshipRewriteProgramFreezeAssembler.assemble(
            COMMIT,
            validation.commitment(),
            EvolutionRewriteProgramHeldOutSplitReferences.create(validation),
            finalTest.commitment(),
            EvolutionRewriteProgramHeldOutSplitReferences.create(finalTest));

        assertEquals(privateAssembly, publicAssembly);
        assertEquals(
            privateAssembly.receipt().toCanonicalJson(),
            publicAssembly.receipt().toCanonicalJson());
    }

    @Test
    void publicAndPrivateFilePathsWriteByteIdenticalFreezeDirectories()
            throws Exception {
        EvolutionRewriteProgramHeldOutRevealBundle validation =
            validationBundle("validation_private_symbol");
        EvolutionRewriteProgramHeldOutRevealBundle finalTest =
            finalTestBundle("final_private_symbol");
        EvolutionRewriteProgramHeldOutRevealCodec privateCodec =
            new EvolutionRewriteProgramHeldOutRevealCodec();
        Path validationPrivate = tempDir.resolve("validation-private.json");
        Path finalTestPrivate = tempDir.resolve("final-test-private.json");
        privateCodec.writePrivate(validationPrivate, validation);
        privateCodec.writePrivate(finalTestPrivate, finalTest);

        Path validationCommitment = tempDir.resolve("validation-commitment.json");
        Path validationReferences =
            tempDir.resolve("validation-split-references.json");
        Path finalTestCommitment = tempDir.resolve("final-test-commitment.json");
        Path finalTestReferences =
            tempDir.resolve("final-test-split-references.json");
        Files.writeString(
            validationCommitment,
            validation.commitment().toCanonicalJson());
        Files.writeString(
            validationReferences,
            EvolutionRewriteProgramHeldOutSplitReferences.create(validation)
                .toCanonicalJson());
        Files.writeString(
            finalTestCommitment,
            finalTest.commitment().toCanonicalJson());
        Files.writeString(
            finalTestReferences,
            EvolutionRewriteProgramHeldOutSplitReferences.create(finalTest)
                .toCanonicalJson());

        var privateFreeze = FlagshipRewriteProgramFreezeAssembler.write(
            tempDir.resolve("private-path-freeze"),
            COMMIT,
            validationPrivate,
            finalTestPrivate);
        var publicFreeze = FlagshipRewriteProgramFreezeAssembler.writePublic(
            tempDir.resolve("public-path-freeze"),
            COMMIT,
            validationCommitment,
            validationReferences,
            finalTestCommitment,
            finalTestReferences);

        assertEquals(privateFreeze.receiptHash(), publicFreeze.receiptHash());
        assertEquals(
            readDirectory(privateFreeze.outputDirectory()),
            readDirectory(publicFreeze.outputDirectory()));
        String publicMaterial = readDirectory(publicFreeze.outputDirectory())
            .values().stream().collect(Collectors.joining("\n"));
        assertFalse(publicMaterial.contains("validation_private_symbol"));
        assertFalse(publicMaterial.contains("final_private_symbol"));
    }

    @Test
    void rejectsCommitmentAndReferenceSubstitutionBeforeReceiptCreation() {
        EvolutionRewriteProgramHeldOutRevealBundle original =
            validationBundle("original_private_symbol");
        EvolutionRewriteProgramHeldOutRevealBundle replacement =
            validationBundle("replacement_private_symbol");
        EvolutionRewriteProgramHeldOutRevealBundle finalTest =
            finalTestBundle("final_private_symbol");

        assertThrows(
            IllegalArgumentException.class,
            () -> FlagshipRewriteProgramFreezeAssembler.assemble(
                COMMIT,
                original.commitment(),
                EvolutionRewriteProgramHeldOutSplitReferences.create(
                    replacement),
                finalTest.commitment(),
                EvolutionRewriteProgramHeldOutSplitReferences.create(
                    finalTest)));
    }

    private static Map<String, String> readDirectory(Path directory)
            throws Exception {
        try (var files = Files.list(directory)) {
            Map<String, String> result = new TreeMap<>();
            for (Path file : files.sorted().toList()) {
                result.put(
                    file.getFileName().toString(),
                    Files.readString(file, StandardCharsets.UTF_8));
            }
            return result;
        }
    }

    private static EvolutionRewriteProgramHeldOutRevealBundle validationBundle(
        String symbol
    ) {
        return EvolutionRewriteProgramHeldOutRevealBundle.create(
            FlagshipRewriteProgramSplitManifest.STUDY_ID,
            Split.VALIDATION,
            List.of(RevealCase.create(
                "validation_public_reproduction_case",
                "public_reproduction_validation",
                "(" + symbol + "^3 - 1) / (" + symbol + " - 1)",
                symbol + "^2 + " + symbol + " + 1",
                List.of(symbol + " - 1 != 0"),
                DifficultyTier.HARD,
                ExpectedTerminalClass.CONFIRMED)));
    }

    private static EvolutionRewriteProgramHeldOutRevealBundle finalTestBundle(
        String symbol
    ) {
        return EvolutionRewriteProgramHeldOutRevealBundle.create(
            FlagshipRewriteProgramSplitManifest.STUDY_ID,
            Split.FINAL_TEST,
            List.of(
                RevealCase.create(
                    "final_public_quartic_bridge",
                    "public_quartic_bridge",
                    "(" + symbol + "^4 - 1) / (" + symbol + "^2 - 1)",
                    symbol + "^2 + 1",
                    List.of(symbol + "^2 - 1 != 0"),
                    DifficultyTier.HARD,
                    ExpectedTerminalClass.CONFIRMED),
                RevealCase.create(
                    "final_public_nested_reciprocal",
                    "public_nested_reciprocal",
                    "(1 / (" + symbol + " + 1) + 1 / (" + symbol
                        + " + 2)) / (1 / ((" + symbol + " + 1) * ("
                        + symbol + " + 2)))",
                    "2 * " + symbol + " + 3",
                    List.of(
                        symbol + " + 1 != 0",
                        symbol + " + 2 != 0"),
                    DifficultyTier.HARD,
                    ExpectedTerminalClass.CONFIRMED),
                RevealCase.create(
                    "final_public_square_normalization",
                    "public_square_normalization",
                    "(" + symbol + "^2 + 2 * " + symbol + " + 1) / ("
                        + symbol + " + 1)",
                    symbol + " + 1",
                    List.of(symbol + " + 1 != 0"),
                    DifficultyTier.STANDARD,
                    ExpectedTerminalClass.CONFIRMED)));
    }
}
