package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.DifficultyTier;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.ExpectedTerminalClass;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.RevealCase;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlagshipRewriteProgramSplitManifestTest {
    @Test
    void bindsTheOpenTrainCorpusAndBothPrivateRevealSurfaces() {
        var validation = validationBundle("a + b + c");
        var finalTest = finalTestBundle();

        EvolutionSplitManifest first =
            FlagshipRewriteProgramSplitManifest.create(validation, finalTest);
        EvolutionSplitManifest second =
            FlagshipRewriteProgramSplitManifest.create(validation, finalTest);

        assertEquals(first, second);
        assertEquals(
            FlagshipRewriteProgramSplitManifest.STUDY_ID,
            first.studyId());
        assertEquals(8, first.trainCases().size());
        assertEquals(1, first.validationCases().size());
        assertEquals(3, first.finalTestCases().size());
        validation.commitment().requireMatches(first);
        finalTest.commitment().requireMatches(first);
    }

    @Test
    void privateMaterialOrSplitSubstitutionChangesOrRejectsTheManifest() {
        EvolutionSplitManifest base = FlagshipRewriteProgramSplitManifest.create(
            validationBundle("a + b + c"),
            finalTestBundle());
        EvolutionSplitManifest changed = FlagshipRewriteProgramSplitManifest.create(
            validationBundle("a * a + a * b + b * b"),
            finalTestBundle());

        assertNotEquals(base.contentHash(), changed.contentHash());
        assertThrows(
            IllegalArgumentException.class,
            () -> FlagshipRewriteProgramSplitManifest.create(
                finalTestBundle(),
                finalTestBundle()));
        assertThrows(
            IllegalArgumentException.class,
            () -> FlagshipRewriteProgramSplitManifest.create(
                EvolutionRewriteProgramHeldOutRevealBundle.create(
                    "another_study",
                    Split.VALIDATION,
                    validationCases("a + b + c")),
                finalTestBundle()));
    }

    private static EvolutionRewriteProgramHeldOutRevealBundle validationBundle(
        String target
    ) {
        return EvolutionRewriteProgramHeldOutRevealBundle.create(
            FlagshipRewriteProgramSplitManifest.STUDY_ID,
            Split.VALIDATION,
            validationCases(target));
    }

    private static List<RevealCase> validationCases(String target) {
        return List.of(RevealCase.create(
            "validation_cubic_factor_bridge",
            "cubic_factor_bridge",
            "(a^3 - b^3) / (a - b)",
            target,
            List.of("a - b != 0"),
            DifficultyTier.HARD,
            ExpectedTerminalClass.CONFIRMED));
    }

    private static EvolutionRewriteProgramHeldOutRevealBundle finalTestBundle() {
        return EvolutionRewriteProgramHeldOutRevealBundle.create(
            FlagshipRewriteProgramSplitManifest.STUDY_ID,
            Split.FINAL_TEST,
            List.of(
                RevealCase.create(
                    "final_mixed_denominator_sum",
                    "mixed_denominator_sum",
                    "m / n + p / q",
                    "(m * q + p * n) / (n * q)",
                    List.of("n != 0", "q != 0"),
                    DifficultyTier.HARD,
                    ExpectedTerminalClass.CONFIRMED),
                RevealCase.create(
                    "final_perfect_square_cancellation",
                    "perfect_square_cancellation",
                    "(x^2 + 2 * x + 1) / (x + 1)",
                    "x + 1",
                    List.of("x + 1 != 0"),
                    DifficultyTier.STANDARD,
                    ExpectedTerminalClass.CONFIRMED),
                RevealCase.create(
                    "final_reciprocal_denominator_composition",
                    "reciprocal_denominator_composition",
                    "(1 / u + 1 / v) / (1 / (u * v))",
                    "u + v",
                    List.of("u != 0", "v != 0"),
                    DifficultyTier.HARD,
                    ExpectedTerminalClass.CONFIRMED)));
    }
}
