package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.DeterministicGenomeMutator.MutationCatalog;
import de.regelsuche.evolution.EvolutionGenome.FeatureWeight;
import de.regelsuche.evolution.EvolutionGenome.FitnessSignal;
import de.regelsuche.evolution.EvolutionGenome.GuardPolicy;
import de.regelsuche.evolution.EvolutionGenome.Objective;
import de.regelsuche.evolution.EvolutionGenome.ResourceBudget;
import de.regelsuche.evolution.EvolutionPopulationEngine.PopulationRun;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.DifficultyTier;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.ExpectedTerminalClass;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.OpenedReveal;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.RevealCase;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessWeight;
import de.regelsuche.evolution.EvolutionStudyPlan.PopulationPolicy;
import de.regelsuche.evolution.EvolutionStudyPlan.StudyBudget;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramHeldOutRevealAuthorizationTest {
    @Test
    void validationRevealRequiresTheRealTerminalPopulationRun() {
        Fixture fixture = fixture();
        PopulationRun run = populationRun(fixture);
        var authorization =
            EvolutionRewriteProgramHeldOutRevealAuthorization.validation(
                fixture.plan(),
                fixture.manifest(),
                run,
                fixture.validationBundle().commitment());

        OpenedReveal opened = fixture.validationBundle().open(
            authorization,
            fixture.validationBundle().commitment());

        assertEquals(Split.VALIDATION, opened.split());
        assertEquals(fixture.validationBundle().contentHash(), opened.contentHash());
        assertEquals("validation_case", opened.cases().getFirst().caseId());
        assertFalse(authorization.toCanonicalJson().contains("(x * a)"));
        assertFalse(authorization.toCanonicalJson().contains("x != 0"));

        assertThrows(IllegalArgumentException.class, () ->
            fixture.validationBundle().open(
                authorization,
                differentValidationBundle().commitment()));
    }

    @Test
    void finalRevealRequiresSelectionSuiteAndTheExactReservation() {
        Fixture fixture = fixture();
        PopulationRun run = populationRun(fixture);
        EvolutionValidationSelection selection = selection(fixture, run);
        EvolutionFinalTestSuite suite = finalSuite(fixture);
        EvolutionFinalTestReservation reservation =
            EvolutionFinalTestReservation.create(selection, suite);
        var authorization =
            EvolutionRewriteProgramHeldOutRevealAuthorization.finalTest(
                fixture.plan(),
                fixture.manifest(),
                selection,
                suite,
                reservation,
                fixture.finalBundle().commitment());

        OpenedReveal opened = fixture.finalBundle().open(
            authorization,
            fixture.finalBundle().commitment());

        assertEquals(Split.FINAL_TEST, opened.split());
        assertEquals(selection.contentHash(),
            authorization.validationSelectionHash());
        assertEquals(suite.contentHash(), authorization.heldOutSuiteHash());
        assertEquals(reservation.contentHash(),
            authorization.prerequisiteArtifactHash());
        assertEquals(EvolutionFinalTestReservation.RESERVED,
            authorization.terminalOutcome());

        EvolutionFinalTestSuite substituted = EvolutionFinalTestSuite.create(
            fixture.plan().contentHash(),
            fixture.manifest().contentHash(),
            hash("baseline"),
            List.of(new EvolutionFinalTestSuite.CaseDefinition(
                "final_case",
                "final_family",
                hash("substituted-material"))));
        EvolutionFinalTestReservation substitutedReservation =
            EvolutionFinalTestReservation.create(selection, substituted);
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionRewriteProgramHeldOutRevealAuthorization.finalTest(
                fixture.plan(),
                fixture.manifest(),
                selection,
                substituted,
                substitutedReservation,
                fixture.finalBundle().commitment()));
    }

    @Test
    void openedRevealCannotBeConstructedByOrdinaryCallers() {
        assertTrue(List.of(OpenedReveal.class.getDeclaredConstructors()).stream()
            .noneMatch(constructor -> Modifier.isPublic(
                constructor.getModifiers())));
    }

    private static PopulationRun populationRun(Fixture fixture) {
        return new EvolutionPopulationEngine().run(
            fixture.plan(),
            List.of(fixture.seed()),
            MutationCatalog.empty(),
            genome -> EvolutionPopulationEngine.TrainFitness.scored(Map.of(
                FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, 500,
                FitnessComponent.STRUCTURAL_DIVERSITY, 500)));
    }

    private static EvolutionValidationSelection selection(
        Fixture fixture,
        PopulationRun run
    ) {
        EvolutionValidationCaseEvidence evidence =
            new EvolutionValidationCaseEvidence(
                "validation_case",
                "validation_family",
                false,
                true,
                EvolutionCorrectnessStatus.NOT_EVALUATED,
                EvolutionCorrectnessStatus.CONFIRMED,
                "FRONTIER_EXHAUSTED",
                "TARGET_REACHED",
                -1,
                3,
                12,
                8,
                20,
                11,
                true,
                false,
                false,
                false);
        EvolutionValidationCandidate candidate =
            EvolutionValidationCandidate.create(
                fixture.seed().contentHash(),
                fixture.seed().alphaStructuralHash(),
                new EvolutionValidationSearchConfiguration(6, 512, 40),
                List.of(evidence),
                List.of());
        return EvolutionValidationSelection.create(
            fixture.plan().contentHash(),
            fixture.manifest().contentHash(),
            run.contentHash(),
            fixture.validationBundle().contentHash(),
            List.of("validation_case"),
            List.of(candidate));
    }

    private static EvolutionFinalTestSuite finalSuite(Fixture fixture) {
        return EvolutionFinalTestSuite.create(
            fixture.plan().contentHash(),
            fixture.manifest().contentHash(),
            hash("baseline"),
            List.of(new EvolutionFinalTestSuite.CaseDefinition(
                "final_case",
                "final_family",
                fixture.finalBundle().commitment().cases().getFirst()
                    .revealEntryHash())));
    }

    private static Fixture fixture() {
        String studyId = "held_out_authorization_study_v1";
        var validation = EvolutionRewriteProgramHeldOutRevealBundle.create(
            studyId,
            Split.VALIDATION,
            List.of(RevealCase.create(
                "validation_case",
                "validation_family",
                "(x * a) / (x * b)",
                "a / b",
                List.of("x != 0", "b != 0"),
                DifficultyTier.STANDARD,
                ExpectedTerminalClass.CONFIRMED)));
        var finalTest = EvolutionRewriteProgramHeldOutRevealBundle.create(
            studyId,
            Split.FINAL_TEST,
            List.of(RevealCase.create(
                "final_case",
                "final_family",
                "(u^2 - v^2) / (u - v)",
                "u + v",
                List.of("u - v != 0"),
                DifficultyTier.HARD,
                ExpectedTerminalClass.CONFIRMED)));
        EvolutionSplitManifest manifest = EvolutionSplitManifest.create(
            studyId,
            hash("corpus"),
            hash("features"),
            List.of(caseReference(
                "train_case", "train_family", "train")),
            validation.splitReferences(),
            finalTest.splitReferences());
        EvolutionGenome seed = EvolutionGenome.create(
            Objective.OPEN_TARGET_OPERATOR,
            manifest.trainingScope(),
            List.of(EvolutionGenomeTestFixtures.gene(
                "remove_additive_zero", "?A+0", "?A")),
            List.of(
                new FeatureWeight(
                    FitnessSignal.UNSEEN_TRAIN_CASES_SOLVED, 500),
                new FeatureWeight(
                    FitnessSignal.COUNTEREXAMPLE_RISK, -500)),
            GuardPolicy.strictDefault(),
            new ResourceBudget(16, 128, 12, 32, 80),
            List.of("core.ast-rewrite"),
            List.of());
        EvolutionStudyPlan plan = EvolutionStudyPlan.create(
            studyId,
            Objective.OPEN_TARGET_OPERATOR,
            manifest,
            List.of(seed),
            List.of(EvolutionMutationKind.GENERALIZE_PLACEHOLDER),
            new PopulationPolicy(2, 2, 1, 2, 1, 2, 20260801L),
            List.of(
                new FitnessWeight(
                    FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, 700),
                new FitnessWeight(
                    FitnessComponent.STRUCTURAL_DIVERSITY, 300)),
            new StudyBudget(64, 16, 1, 1, 1));
        return new Fixture(manifest, plan, seed, validation, finalTest);
    }

    private static EvolutionRewriteProgramHeldOutRevealBundle
            differentValidationBundle() {
        return EvolutionRewriteProgramHeldOutRevealBundle.create(
            "held_out_authorization_study_v1",
            Split.VALIDATION,
            List.of(RevealCase.create(
                "other_validation_case",
                "other_validation_family",
                "(m * p) / (m * q)",
                "p / q",
                List.of("m != 0", "q != 0"),
                DifficultyTier.STANDARD,
                ExpectedTerminalClass.CONFIRMED)));
    }

    private static EvolutionSplitManifest.CaseReference caseReference(
        String caseId,
        String familyId,
        String material
    ) {
        return new EvolutionSplitManifest.CaseReference(
            caseId,
            familyId,
            hash(material + "-exact"),
            hash(material + "-alpha"),
            hash(material + "-input"),
            hash(material + "-target"));
    }

    private static String hash(String value) {
        return EvolutionGenome.hash(value);
    }

    private record Fixture(
        EvolutionSplitManifest manifest,
        EvolutionStudyPlan plan,
        EvolutionGenome seed,
        EvolutionRewriteProgramHeldOutRevealBundle validationBundle,
        EvolutionRewriteProgramHeldOutRevealBundle finalBundle
    ) {
    }
}
