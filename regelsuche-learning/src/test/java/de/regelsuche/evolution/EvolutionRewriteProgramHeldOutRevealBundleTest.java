package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.DifficultyTier;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.ExpectedTerminalClass;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.RevealCase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramHeldOutRevealBundleTest {
    @Test
    void publicCommitmentContainsOnlyHashesAndMatchesSplitManifest() {
        EvolutionRewriteProgramHeldOutRevealBundle validation = validation();
        EvolutionRewriteProgramHeldOutRevealBundle finalTest = finalTest();
        EvolutionSplitManifest manifest = manifest(validation, finalTest);

        EvolutionRewriteProgramHeldOutCommitment commitment =
            validation.commitment();
        commitment.requireMatches(manifest);
        finalTest.commitment().requireMatches(manifest);

        String publicJson = commitment.toCanonicalJson();
        assertFalse(publicJson.contains("(u+2)*p"));
        assertFalse(publicJson.contains("p/q"));
        assertFalse(publicJson.contains("u+2 != 0"));
        assertTrue(publicJson.contains("\"targetHash\""));
        assertTrue(publicJson.contains("\"revealEntryHash\""));
        assertEquals(validation.contentHash(), commitment.sealedRevealHash());
    }

    @Test
    void canonicalIdentityIsIndependentOfCaseAndAssumptionOrder() {
        List<RevealCase> cases = new ArrayList<>(finalCases());
        EvolutionRewriteProgramHeldOutRevealBundle first =
            EvolutionRewriteProgramHeldOutRevealBundle.create(
                "held_out_reveal_study_v1",
                Split.FINAL_TEST,
                cases);
        java.util.Collections.reverse(cases);
        List<RevealCase> reordered = cases.stream()
            .map(item -> RevealCase.create(
                item.caseId(),
                item.familyId(),
                item.inputExpression(),
                item.targetExpression(),
                reversed(item.assumptions()),
                item.difficultyTier(),
                item.expectedTerminalClass()))
            .toList();
        EvolutionRewriteProgramHeldOutRevealBundle second =
            EvolutionRewriteProgramHeldOutRevealBundle.create(
                "held_out_reveal_study_v1",
                Split.FINAL_TEST,
                reordered);

        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.privateCanonicalJson(), second.privateCanonicalJson());
        assertEquals(first.commitment(), second.commitment());
    }

    @Test
    void targetMutationChangesRevealAndCommitmentIdentity() {
        EvolutionRewriteProgramHeldOutRevealBundle original = validation();
        EvolutionRewriteProgramHeldOutRevealBundle changed =
            EvolutionRewriteProgramHeldOutRevealBundle.create(
                original.studyId(),
                Split.VALIDATION,
                List.of(revealCase(
                    "validation_affine_cancel",
                    "validation_affine_shift",
                    "((u+2)*p)/((u+2)*q)",
                    "p/(q+1)",
                    List.of("u+2 != 0", "q+1 != 0"),
                    DifficultyTier.STANDARD,
                    ExpectedTerminalClass.CONFIRMED)));

        assertNotEquals(original.contentHash(), changed.contentHash());
        assertNotEquals(
            original.commitment().contentHash(),
            changed.commitment().contentHash());
    }

    private static EvolutionRewriteProgramHeldOutRevealBundle validation() {
        return EvolutionRewriteProgramHeldOutRevealBundle.create(
            "held_out_reveal_study_v1",
            Split.VALIDATION,
            List.of(revealCase(
                "validation_affine_cancel",
                "validation_affine_shift",
                "((u+2)*p)/((u+2)*q)",
                "p/q",
                List.of("q != 0", "u+2 != 0"),
                DifficultyTier.STANDARD,
                ExpectedTerminalClass.CONFIRMED)));
    }

    private static EvolutionRewriteProgramHeldOutRevealBundle finalTest() {
        return EvolutionRewriteProgramHeldOutRevealBundle.create(
            "held_out_reveal_study_v1",
            Split.FINAL_TEST,
            finalCases());
    }

    private static List<RevealCase> finalCases() {
        return List.of(
            revealCase(
                "final_nested_fraction",
                "final_nested_rational",
                "(p/(q+r))/(s/(q+r))",
                "p/s",
                List.of("q+r != 0", "s != 0"),
                DifficultyTier.HARD,
                ExpectedTerminalClass.CONFIRMED),
            revealCase(
                "final_square_bridge",
                "final_scaled_square_bridge",
                "(4*u^2-v^2)/(2*u-v)",
                "2*u+v",
                List.of("2*u-v != 0"),
                DifficultyTier.HARD,
                ExpectedTerminalClass.CONFIRMED),
            revealCase(
                "final_missing_pole",
                "final_negative_missing_pole",
                "(x*(a+b))/(x*c)",
                "(a+b)/c",
                List.of("c != 0"),
                DifficultyTier.BOUNDARY,
                ExpectedTerminalClass.MISSING_ASSUMPTION));
    }

    private static EvolutionSplitManifest manifest(
        EvolutionRewriteProgramHeldOutRevealBundle validation,
        EvolutionRewriteProgramHeldOutRevealBundle finalTest
    ) {
        return EvolutionSplitManifest.create(
            validation.studyId(),
            hash("corpus"),
            hash("feature-schema"),
            List.of(new EvolutionSplitManifest.CaseReference(
                "train_open_case",
                "train_open_family",
                hash("train-exact"),
                hash("train-alpha"),
                hash("train-input"),
                hash("train-target"))),
            validation.splitReferences(),
            finalTest.splitReferences());
    }

    private static RevealCase revealCase(
        String caseId,
        String familyId,
        String input,
        String target,
        List<String> assumptions,
        DifficultyTier difficulty,
        ExpectedTerminalClass terminal
    ) {
        return RevealCase.create(
            caseId,
            familyId,
            input,
            target,
            assumptions,
            difficulty,
            terminal);
    }

    private static List<String> reversed(List<String> values) {
        List<String> result = new ArrayList<>(values);
        java.util.Collections.reverse(result);
        return result;
    }

    private static String hash(String material) {
        return EvolutionGenome.hash(material);
    }
}
