package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.DifficultyTier;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.ExpectedTerminalClass;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.RevealCase;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.EvaluatorProfile;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.TrainCase;
import de.regelsuche.search.SearchHeuristic;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramTrainCaseReferencesTest {
    @Test
    void usesExactlyTheSharedCaseIdentityAlgorithm() {
        EvolutionRewriteProgramTrainSuite suite = suite();

        var first = EvolutionRewriteProgramTrainCaseReferences.create(suite);
        var second = EvolutionRewriteProgramTrainCaseReferences.create(suite);
        var shared = EvolutionRewriteProgramHeldOutRevealBundle.create(
            "identity_comparison_study",
            Split.VALIDATION,
            suite.cases().stream()
                .map(item -> RevealCase.create(
                    item.caseId(),
                    item.familyId(),
                    item.inputExpression(),
                    item.targetExpression(),
                    item.assumptions(),
                    DifficultyTier.STANDARD,
                    ExpectedTerminalClass.CONFIRMED))
                .toList())
            .splitReferences();

        assertEquals(first, second);
        assertEquals(shared, first);
        assertEquals(
            List.of("train_case_a", "train_case_b"),
            first.stream().map(
                EvolutionSplitManifest.CaseReference::caseId).toList());
    }

    private static EvolutionRewriteProgramTrainSuite suite() {
        return EvolutionRewriteProgramTrainSuite.create(
            "reference_derivation_suite",
            EvaluatorProfile
                .EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
            List.of(
                new TrainCase(
                    "train_case_b",
                    "train_family_b",
                    "(x^2 - 1) / (x - 1)",
                    "x + 1",
                    List.of("x - 1 != 0")),
                new TrainCase(
                    "train_case_a",
                    "train_family_a",
                    "a / y + b / y",
                    "(a + b) / y",
                    List.of("y != 0"))),
            new SearchHeuristic(4, 128, 1, 3, 24, 6),
            new EvolutionRewriteProgramTrainSuite.PrimitiveWorkBudget(
                4, 128, 24, 3, 4_000));
    }
}
