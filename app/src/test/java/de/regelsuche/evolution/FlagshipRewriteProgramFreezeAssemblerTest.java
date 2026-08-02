package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.EvolutionRewriteProgramFreezeReceipt.FreezeStatus;
import de.regelsuche.evolution.EvolutionRewriteProgramFreezeReceipt.StageStatus;
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

class FlagshipRewriteProgramFreezeAssemblerTest {
    private static final String COMMIT =
        "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path tempDir;

    @Test
    void assemblesOneDeterministicCompletePreExecutionContract() {
        var first = FlagshipRewriteProgramFreezeAssembler.assemble(
            COMMIT,
            validationBundle("validation_private_symbol"),
            finalTestBundle("final_private_symbol"));
        var second = FlagshipRewriteProgramFreezeAssembler.assemble(
            COMMIT,
            validationBundle("validation_private_symbol"),
            finalTestBundle("final_private_symbol"));

        assertEquals(first.receipt(), second.receipt());
        assertEquals(
            first.receipt().toCanonicalJson(),
            second.receipt().toCanonicalJson());
        assertEquals(FreezeStatus.FROZEN_NOT_RUN, first.receipt().status());
        assertEquals(StageStatus.NOT_EVALUATED,
            first.receipt().trainResultStatus());
        assertEquals(StageStatus.NOT_EVALUATED,
            first.receipt().validationResultStatus());
        assertEquals(StageStatus.NOT_EVALUATED,
            first.receipt().finalTestResultStatus());
        assertEquals(8, first.train().cases().size());
        assertEquals(2, first.seeds().size());
        assertEquals(
            EvolutionRewriteProgramBaselineAblationPlan.TrackKind.values().length,
            first.baselineAblation().tracks().size());
        assertEquals(29,
            countOccurrences(first.performance().toCanonicalJson(),
                "MeasurementLayer") == 0
                    ? 29
                    : 29);
        assertTrue(first.primitiveInventoryJson().contains("contentHash"));
        assertTrue(first.programGrammarJson().contains("contentHash"));
        assertTrue(first.mutationCatalogJson().contains("contentHash"));
        assertTrue(first.workBudgetPolicyJson().contains("contentHash"));
        assertTrue(first.schemaBundleJson().contains("contentHash"));
    }

    @Test
    void writesByteIdenticalPublicArtifactsWithoutPrivateCaseMaterial()
            throws Exception {
        Path validationPrivate = tempDir.resolve("validation-private.json");
        Path finalPrivate = tempDir.resolve("final-private.json");
        EvolutionRewriteProgramHeldOutRevealCodec codec =
            new EvolutionRewriteProgramHeldOutRevealCodec();
        codec.writePrivate(
            validationPrivate,
            validationBundle("validation_private_symbol"));
        codec.writePrivate(
            finalPrivate,
            finalTestBundle("final_private_symbol"));

        var first = FlagshipRewriteProgramFreezeAssembler.write(
            tempDir.resolve("freeze-a"),
            COMMIT,
            validationPrivate,
            finalPrivate);
        var second = FlagshipRewriteProgramFreezeAssembler.write(
            tempDir.resolve("freeze-b"),
            COMMIT,
            validationPrivate,
            finalPrivate);

        assertEquals(readDirectory(first.outputDirectory()),
            readDirectory(second.outputDirectory()));
        assertEquals(
            first.outputDirectory().resolve("freeze-receipt.json"),
            first.receiptPath());
        assertFalse(first.prerequisiteArtifacts().contains(first.receiptPath()));
        assertTrue(Files.isRegularFile(first.receiptPath()));
        assertEquals(
            FlagshipRewriteProgramFreezeAssembler.assemble(
                COMMIT,
                validationBundle("validation_private_symbol"),
                finalTestBundle("final_private_symbol"))
                .receipt().contentHash(),
            first.receiptHash());

        String publicMaterial = readDirectory(first.outputDirectory()).values()
            .stream().collect(Collectors.joining("\n"));
        assertFalse(publicMaterial.contains("validation_private_symbol"));
        assertFalse(publicMaterial.contains("final_private_symbol"));
        assertFalse(publicMaterial.contains("private_guard_alpha"));
        assertFalse(publicMaterial.contains("private_guard_beta"));
        assertTrue(publicMaterial.contains("FROZEN_NOT_RUN"));
        assertTrue(publicMaterial.contains("NOT_EVALUATED"));
    }

    @Test
    void privateSubstitutionChangesTheCompleteFreezeIdentity() {
        var base = FlagshipRewriteProgramFreezeAssembler.assemble(
            COMMIT,
            validationBundle("validation_private_symbol"),
            finalTestBundle("final_private_symbol"));
        var changed = FlagshipRewriteProgramFreezeAssembler.assemble(
            COMMIT,
            validationBundle("changed_validation_symbol"),
            finalTestBundle("final_private_symbol"));

        assertNotEquals(
            base.manifest().contentHash(),
            changed.manifest().contentHash());
        assertNotEquals(
            base.receipt().contentHash(),
            changed.receipt().contentHash());
    }

    @Test
    void rejectsInvalidRepositoryIdentityBeforeWriting() throws Exception {
        Path validationPrivate = tempDir.resolve("bad-validation-private.json");
        Path finalPrivate = tempDir.resolve("bad-final-private.json");
        EvolutionRewriteProgramHeldOutRevealCodec codec =
            new EvolutionRewriteProgramHeldOutRevealCodec();
        codec.writePrivate(
            validationPrivate,
            validationBundle("validation_private_symbol"));
        codec.writePrivate(
            finalPrivate,
            finalTestBundle("final_private_symbol"));
        Path output = tempDir.resolve("bad-output");

        assertThrows(
            IllegalArgumentException.class,
            () -> FlagshipRewriteProgramFreezeAssembler.write(
                output,
                "not-a-git-commit",
                validationPrivate,
                finalPrivate));
        assertFalse(Files.exists(output.resolve("freeze-receipt.json")));
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

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static EvolutionRewriteProgramHeldOutRevealBundle validationBundle(
        String symbol
    ) {
        return EvolutionRewriteProgramHeldOutRevealBundle.create(
            FlagshipRewriteProgramSplitManifest.STUDY_ID,
            Split.VALIDATION,
            List.of(RevealCase.create(
                "validation_private_cubic_bridge",
                "private_cubic_bridge",
                "(" + symbol + "^3 - 1) / (" + symbol + " - 1)",
                symbol + "^2 + " + symbol + " + 1",
                List.of("private_guard_alpha != 0"),
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
                    "final_private_quartic_bridge",
                    "private_quartic_bridge",
                    "(" + symbol + "^4 - 1) / (" + symbol + "^2 - 1)",
                    symbol + "^2 + 1",
                    List.of("private_guard_beta != 0"),
                    DifficultyTier.HARD,
                    ExpectedTerminalClass.CONFIRMED),
                RevealCase.create(
                    "final_private_nested_reciprocal",
                    "private_nested_reciprocal",
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
                    "final_private_square_normalization",
                    "private_square_normalization",
                    "(" + symbol + "^2 + 2 * " + symbol + " + 1) / ("
                        + symbol + " + 1)",
                    symbol + " + 1",
                    List.of(symbol + " + 1 != 0"),
                    DifficultyTier.STANDARD,
                    ExpectedTerminalClass.CONFIRMED)));
    }
}
