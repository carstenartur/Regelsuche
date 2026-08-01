package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationCatalog;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.RepeatBounds;
import de.regelsuche.evolution.EvolutionGenome.FeatureWeight;
import de.regelsuche.evolution.EvolutionGenome.FitnessSignal;
import de.regelsuche.evolution.EvolutionGenome.GuardPolicy;
import de.regelsuche.evolution.EvolutionGenome.Objective;
import de.regelsuche.evolution.EvolutionGenome.ResourceBudget;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.CaseCommitment;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Priority;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Requirement;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.EvaluatorProfile;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.TrainCase;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessWeight;
import de.regelsuche.evolution.EvolutionStudyPlan.PopulationPolicy;
import de.regelsuche.evolution.EvolutionStudyPlan.StudyBudget;
import de.regelsuche.search.SearchHeuristic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramFreezeContractsTest {
    @Test
    void repeatedConstructionIsByteIdenticalAndPreExecution() {
        Fixture fixture = fixture();
        EvolutionRewriteProgramFreezeReceipt first = receipt(fixture);
        EvolutionRewriteProgramFreezeReceipt second = receipt(fixture);

        assertEquals(first, second);
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertTrue(first.toCanonicalJson().contains(
            "\"status\":\"FROZEN_NOT_RUN\""));
        assertTrue(fixture.performance().toCanonicalJson().contains(
            "\"wallClockRole\":\"ENVIRONMENT_QUALIFIED_ENGINEERING_DIAGNOSTIC\""));
        first.requireInputs(
            fixture.manifest(),
            fixture.suite(),
            fixture.validation(),
            fixture.finalTest(),
            fixture.protocol(),
            fixture.study(),
            fixture.thresholds(),
            hash("primitive-inventory"),
            hash("program-grammar"),
            hash("baseline-ablation-plan"),
            fixture.performance(),
            hash("schema-bundle"));
    }

    @Test
    void heldOutCommitmentRejectsTargetSubstitution() {
        Fixture fixture = fixture();
        List<CaseCommitment> changed = new ArrayList<>(
            fixture.validation().cases());
        CaseCommitment original = changed.getFirst();
        changed.set(0, new CaseCommitment(
            original.caseId(),
            original.familyCommitmentHash(),
            original.inputHash(),
            hash("substituted-target"),
            original.assumptionsHash(),
            original.exactSignatureHash(),
            original.alphaSignatureHash(),
            original.difficultyTierHash(),
            original.expectedTerminalClassHash(),
            hash("substituted-reveal-entry")));
        EvolutionRewriteProgramHeldOutCommitment substituted =
            EvolutionRewriteProgramHeldOutCommitment.create(
                fixture.manifest().studyId(),
                Split.VALIDATION,
                changed,
                hash("substituted-validation-sealed-reveal"));

        assertThrows(IllegalArgumentException.class,
            () -> substituted.requireMatches(fixture.manifest()));
    }

    @Test
    void thresholdAndPerformancePolicyMutationsChangeFreezeIdentity() {
        Fixture fixture = fixture();
        EvolutionRewriteProgramAcceptanceThresholds changedThresholds =
            EvolutionRewriteProgramAcceptanceThresholds.create(
                2, 2, 1, true, 150, 3, 0, 12, 8, 2);
        EvolutionRewriteProgramPerformancePlan changedPerformance =
            EvolutionRewriteProgramPerformancePlan.create(
                hash("benchmark-revision"),
                hash("benchmark-suite"),
                hash("runtime-environment"),
                2, 3, 1, 1000, 120);

        assertNotEquals(
            fixture.thresholds().contentHash(),
            changedThresholds.contentHash());
        assertNotEquals(
            fixture.performance().contentHash(),
            changedPerformance.contentHash());

        EvolutionRewriteProgramFreezeReceipt base = receipt(fixture);
        EvolutionRewriteProgramFreezeReceipt changed =
            EvolutionRewriteProgramFreezeReceipt.create(
                "flagship_freeze_v1",
                "0123456789abcdef0123456789abcdef01234567",
                fixture.manifest(),
                fixture.suite(),
                fixture.validation(),
                fixture.finalTest(),
                fixture.protocol(),
                fixture.study(),
                changedThresholds,
                hash("primitive-inventory"),
                hash("program-grammar"),
                hash("baseline-ablation-plan"),
                changedPerformance,
                hash("schema-bundle"));
        assertNotEquals(base.contentHash(), changed.contentHash());
        assertThrows(IllegalArgumentException.class, () -> base.requireInputs(
            fixture.manifest(),
            fixture.suite(),
            fixture.validation(),
            fixture.finalTest(),
            fixture.protocol(),
            fixture.study(),
            fixture.thresholds(),
            hash("primitive-inventory"),
            hash("program-grammar"),
            hash("baseline-ablation-plan"),
            changedPerformance,
            hash("schema-bundle")));
    }

    @Test
    void positiveThresholdsCannotPermitCorrectnessOrTechnicalFailures() {
        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionRewriteProgramAcceptanceThresholds(
                EvolutionRewriteProgramAcceptanceThresholds.SCHEMA,
                2,
                2,
                1,
                true,
                100,
                3,
                true,
                true,
                1,
                0,
                0,
                0,
                12,
                8,
                2,
                true,
                EvolutionRewriteProgramAcceptanceThresholds.SuccessRoute
                    .NEWLY_REACHED_OR_MATERIAL_WORK_REDUCTION,
                EvolutionRewriteProgramAcceptanceThresholds.NullResultPolicy
                    .TRANSPARENT_COMPLETE_NULL_RESULT,
                hash("invalid-threshold-payload")));
    }

    private static EvolutionRewriteProgramFreezeReceipt receipt(
        Fixture fixture
    ) {
        return EvolutionRewriteProgramFreezeReceipt.create(
            "flagship_freeze_v1",
            "0123456789abcdef0123456789abcdef01234567",
            fixture.manifest(),
            fixture.suite(),
            fixture.validation(),
            fixture.finalTest(),
            fixture.protocol(),
            fixture.study(),
            fixture.thresholds(),
            hash("primitive-inventory"),
            hash("program-grammar"),
            hash("baseline-ablation-plan"),
            fixture.performance(),
            hash("schema-bundle"));
    }

    private static Fixture fixture() {
        EvolutionSplitManifest manifest = EvolutionSplitManifest.create(
            "flagship_freeze_study_v1",
            hash("flagship-freeze-corpus"),
            hash("flagship-freeze-feature-schema"),
            List.of(caseRef(
                "train_freeze_case",
                "train_freeze_family",
                "train-freeze")),
            List.of(caseRef(
                "validation_freeze_case",
                "validation_freeze_family",
                "validation-freeze")),
            List.of(
                caseRef(
                    "final_freeze_case_a",
                    "final_freeze_family_a",
                    "final-freeze-a"),
                caseRef(
                    "final_freeze_case_b",
                    "final_freeze_family_b",
                    "final-freeze-b"),
                caseRef(
                    "final_freeze_case_c",
                    "final_freeze_family_c",
                    "final-freeze-c")));
        EvolutionGenome genome = EvolutionGenome.create(
            Objective.OPEN_TARGET_OPERATOR,
            manifest.trainingScope(),
            List.of(
                EvolutionGenomeTestFixtures.addZero("add_zero", "A"),
                EvolutionGenomeTestFixtures.gene(
                    "mul_one", "?A*1", "?A")),
            List.of(
                new FeatureWeight(
                    FitnessSignal.UNSEEN_TRAIN_CASES_SOLVED, 500),
                new FeatureWeight(FitnessSignal.COUNTEREXAMPLE_RISK, -500)),
            GuardPolicy.strictDefault(),
            new ResourceBudget(16, 128, 12, 32, 80),
            List.of("core.ast-rewrite"),
            List.of());
        EvolutionRewriteProgramPlan seedPlan =
            EvolutionRewriteProgramPlan.create(
                genome,
                new Source("seed_add_zero", List.of("add_zero")),
                12,
                12);
        List<EvolutionRewriteProgramCandidate> seeds = List.of(
            EvolutionRewriteProgramCandidate.create(genome, seedPlan));
        EvolutionRewriteProgramTrainSuite suite =
            EvolutionRewriteProgramTrainSuite.create(
                "flagship_freeze_train_suite",
                EvaluatorProfile
                    .EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
                List.of(new TrainCase(
                    "train_freeze_case",
                    "train_freeze_family",
                    "x+0",
                    "x",
                    List.of())),
                new SearchHeuristic(4, 256, 1, 4, 80, 16),
                new EvolutionRewriteProgramTrainSuite.PrimitiveWorkBudget(
                    4, 256, 80, 4, 100_000));
        MutationCatalog catalog = new MutationCatalog(
            List.of(new RepeatBounds(1, 2)),
            List.of(Requirement.maxPrimitiveSteps(3)),
            List.of(Priority.estimatedCostThenRule()),
            List.of(4),
            List.of("mul_one"));
        EvolutionRewriteProgramEvaluationProtocol protocol =
            EvolutionRewriteProgramEvaluationProtocol
                .informationParityExactRationalV1();
        EvolutionRewriteProgramStudyPlan study =
            EvolutionRewriteProgramStudyPlan.create(
                manifest.studyId(),
                manifest,
                suite,
                protocol,
                catalog,
                seeds,
                Arrays.asList(EvolutionRewriteProgramMutationKind.values()),
                new PopulationPolicy(4, 3, 1, 2, 2, 2, 20260801L),
                List.of(
                    new FitnessWeight(
                        FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, 700),
                    new FitnessWeight(
                        FitnessComponent.CANDIDATE_COMPLEXITY, 300)),
                new StudyBudget(1000, 1000, 1, 1, 2));
        EvolutionRewriteProgramHeldOutCommitment validation =
            heldOut(manifest, Split.VALIDATION, "validation");
        EvolutionRewriteProgramHeldOutCommitment finalTest =
            heldOut(manifest, Split.FINAL_TEST, "final-test");
        EvolutionRewriteProgramAcceptanceThresholds thresholds =
            EvolutionRewriteProgramAcceptanceThresholds.create(
                2, 2, 1, true, 100, 3, 0, 12, 8, 2);
        EvolutionRewriteProgramPerformancePlan performance =
            EvolutionRewriteProgramPerformancePlan.create(
                hash("benchmark-revision"),
                hash("benchmark-suite"),
                hash("runtime-environment"),
                2, 3, 1, 1000, 100);
        return new Fixture(
            manifest,
            suite,
            protocol,
            study,
            validation,
            finalTest,
            thresholds,
            performance);
    }

    private static EvolutionRewriteProgramHeldOutCommitment heldOut(
        EvolutionSplitManifest manifest,
        Split split,
        String material
    ) {
        List<EvolutionSplitManifest.CaseReference> references = switch (split) {
            case VALIDATION -> manifest.validationCases();
            case FINAL_TEST -> manifest.finalTestCases();
        };
        List<CaseCommitment> cases = references.stream()
            .map(reference -> new CaseCommitment(
                reference.caseId(),
                EvolutionRewriteProgramHeldOutCommitment.familyCommitment(
                    reference.familyId()),
                reference.inputHash(),
                reference.hiddenTargetHash(),
                hash(material + "-assumptions-" + reference.caseId()),
                reference.exactSignatureHash(),
                reference.alphaSignatureHash(),
                hash(material + "-difficulty-" + reference.caseId()),
                hash(material + "-terminal-" + reference.caseId()),
                hash(material + "-reveal-entry-" + reference.caseId())))
            .toList();
        return EvolutionRewriteProgramHeldOutCommitment.create(
            manifest.studyId(),
            split,
            cases,
            hash(material + "-sealed-reveal"));
    }

    private static EvolutionSplitManifest.CaseReference caseRef(
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

    private static String hash(String material) {
        return EvolutionGenome.hash(material);
    }

    private record Fixture(
        EvolutionSplitManifest manifest,
        EvolutionRewriteProgramTrainSuite suite,
        EvolutionRewriteProgramEvaluationProtocol protocol,
        EvolutionRewriteProgramStudyPlan study,
        EvolutionRewriteProgramHeldOutCommitment validation,
        EvolutionRewriteProgramHeldOutCommitment finalTest,
        EvolutionRewriteProgramAcceptanceThresholds thresholds,
        EvolutionRewriteProgramPerformancePlan performance
    ) {
    }
}
