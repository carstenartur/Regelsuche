package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.EvolutionGenome.EvidenceObligation;
import de.regelsuche.evolution.EvolutionGenome.FeatureWeight;
import de.regelsuche.evolution.EvolutionGenome.FitnessSignal;
import de.regelsuche.evolution.EvolutionGenome.GuardPolicy;
import de.regelsuche.evolution.EvolutionGenome.Objective;
import de.regelsuche.evolution.EvolutionGenome.ResourceBudget;
import de.regelsuche.evolution.EvolutionGenome.RewriteGene;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessWeight;
import de.regelsuche.evolution.EvolutionStudyPlan.PopulationPolicy;
import de.regelsuche.evolution.EvolutionStudyPlan.StudyBudget;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvolutionStudyContractsTest {
    private final EvolutionStudyContractCodec codec = new EvolutionStudyContractCodec();

    @Test
    void splitManifestIsCanonicalAndDerivesTrainOnlyGenomeScope() {
        EvolutionSplitManifest manifest = manifest();

        EvolutionSplitManifest replayed = codec.readSplitManifest(
            codec.write(manifest));

        assertEquals(manifest, replayed);
        assertEquals(manifest.trainingScope(), replayed.trainingScope());
        assertEquals(EvolutionGenome.SourceSplit.TRAIN,
            replayed.trainingScope().sourceSplit());
        assertEquals(manifest.familyPartitionHash(),
            replayed.trainingScope().familyPartitionHash());
        assertEquals(manifest.signaturePartitionHash(),
            replayed.trainingScope().signaturePartitionHash());
    }

    @Test
    void trainOnlyManifestDefersBothHeldOutStages() {
        List<EvolutionSplitManifest.CaseReference> train = List.of(
            caseRef("train_only_case", "train_only_family", "train-only"));

        EvolutionSplitManifest manifest = EvolutionSplitManifest.createTrainOnly(
            "showcase_study",
            hash("showcase corpus"),
            hash("showcase features"),
            train);

        assertTrue(manifest.heldOutMaterializationDeferred());
        assertTrue(manifest.validationCases().isEmpty());
        assertTrue(manifest.finalTestCases().isEmpty());
        assertEquals(EvolutionGenome.SourceSplit.TRAIN,
            manifest.trainingScope().sourceSplit());
        assertTrue(manifest.toCanonicalJson().contains(
            "\"heldOutMaterialization\":\"DEFERRED_TO_PUBLIC_RANDOMNESS\""));
        assertEquals(
            manifest,
            EvolutionSplitManifest.createTrainOnly(
                "showcase_study",
                hash("showcase corpus"),
                hash("showcase features"),
                train));
    }

    @Test
    void concreteSplitFactoryStillRequiresValidationAndFinalTest() {
        List<EvolutionSplitManifest.CaseReference> train = List.of(
            caseRef("train_only_case", "train_only_family", "train-only"));

        assertThrows(
            IllegalArgumentException.class,
            () -> EvolutionSplitManifest.create(
                "showcase_study",
                hash("showcase corpus"),
                hash("showcase features"),
                train,
                List.of(),
                List.of()));
    }

    @Test
    void splitManifestRejectsEveryCrossSplitLeakageIdentity() {
        EvolutionSplitManifest.CaseReference train = caseRef(
            "train_case", "train_family", "train");
        EvolutionSplitManifest.CaseReference validation = caseRef(
            "validation_case", "validation_family", "validation");
        EvolutionSplitManifest.CaseReference test = caseRef(
            "test_case", "test_family", "test");

        assertThrows(IllegalArgumentException.class, () ->
            createSplit(train, copy(validation, "familyId", train.familyId()), test));
        assertThrows(IllegalArgumentException.class, () ->
            createSplit(train,
                copy(validation, "exactSignatureHash", train.exactSignatureHash()),
                test));
        assertThrows(IllegalArgumentException.class, () ->
            createSplit(train,
                copy(validation, "alphaSignatureHash", train.alphaSignatureHash()),
                test));
        assertThrows(IllegalArgumentException.class, () ->
            createSplit(train,
                copy(validation, "hiddenTargetHash", train.hiddenTargetHash()),
                test));
    }

    @Test
    void studyPlanBindsOnlySeedsFromFrozenTrainScope() {
        EvolutionSplitManifest manifest = manifest();
        EvolutionGenome seed = seedGenome(manifest);

        EvolutionStudyPlan plan = plan(manifest, seed);
        EvolutionStudyPlan replayed = codec.readStudyPlan(codec.write(plan));

        assertEquals(plan, replayed);
        assertEquals(EvolutionStudyPlan.StudyStatus.NOT_STARTED, plan.status());
        assertEquals(EvolutionStudyPlan.GateStatus.NOT_EVALUATED,
            plan.publicEvidenceStatus());
        assertEquals(List.of(seed.contentHash()), plan.seedGenomeHashes());

        EvolutionGenome foreign = EvolutionGenome.create(
            Objective.OPEN_TARGET_OPERATOR,
            EvolutionGenomeTestFixtures.scope('a'),
            seed.rewrites(),
            seed.rankingFeatures(),
            seed.guardPolicy(),
            seed.budget(),
            seed.requiredCapabilities(),
            List.of());
        assertThrows(IllegalArgumentException.class, () ->
            plan(manifest, foreign));
    }

    @Test
    void frozenBudgetAndPopulationPolicyParticipateInPlanIdentity() {
        EvolutionSplitManifest manifest = manifest();
        EvolutionGenome seed = seedGenome(manifest);
        EvolutionStudyPlan baseline = plan(manifest, seed);
        EvolutionStudyPlan changed = EvolutionStudyPlan.create(
            baseline.studyId(),
            baseline.objective(),
            manifest,
            List.of(seed),
            baseline.mutationOperators(),
            new PopulationPolicy(10, 3, 2, 4, 2, 2, 42L),
            baseline.fitnessWeights(),
            baseline.budget());

        assertNotEquals(baseline.contentHash(), changed.contentHash());
    }

    @Test
    void strictCodecRejectsUnknownFieldsAndTamperedHashes() {
        EvolutionSplitManifest manifest = manifest();
        String manifestJson = codec.write(manifest);
        assertThrows(IllegalArgumentException.class, () ->
            codec.readSplitManifest(manifestJson.replaceFirst(
                "\\{", "{\"unknown\":true,")));
        assertThrows(IllegalArgumentException.class, () ->
            codec.readSplitManifest(manifestJson.replace(
                manifest.contentHash(), hash("tampered-manifest"))));

        EvolutionStudyPlan plan = plan(manifest, seedGenome(manifest));
        String planJson = codec.write(plan);
        assertThrows(IllegalArgumentException.class, () ->
            codec.readStudyPlan(planJson.replaceFirst(
                "\\{", "{\"selectedConfigurationHash\":\""
                    + hash("forbidden-selection") + "\",")));
        assertThrows(IllegalArgumentException.class, () ->
            codec.readStudyPlan(planJson.replace(
                plan.contentHash(), hash("tampered-plan"))));
    }

    @Test
    void planFreezesNamedFitnessComponentsWithoutTestOutcomeFields() {
        EvolutionStudyPlan plan = plan(manifest(), seedGenome(manifest()));
        String json = plan.toCanonicalJson();

        assertTrue(json.contains("TRAIN_CASES_NEWLY_SOLVED"));
        assertTrue(json.contains("STRUCTURAL_DIVERSITY"));
        assertTrue(json.contains("ONE_TIME_AFTER_FROZEN_VALIDATION_SELECTION"));
        assertTrue(!json.contains("finalTestOutcome"));
        assertTrue(!json.contains("selectedConfigurationHash"));
    }

    private static EvolutionSplitManifest manifest() {
        return createSplit(
            caseRef("train_case", "train_family", "train"),
            caseRef("validation_case", "validation_family", "validation"),
            caseRef("test_case", "test_family", "test"));
    }

    private static EvolutionSplitManifest createSplit(
        EvolutionSplitManifest.CaseReference train,
        EvolutionSplitManifest.CaseReference validation,
        EvolutionSplitManifest.CaseReference test
    ) {
        return EvolutionSplitManifest.create(
            "population_study_v1",
            hash("corpus"),
            hash("feature-schema"),
            List.of(train),
            List.of(validation),
            List.of(test));
    }

    private static EvolutionStudyPlan plan(
        EvolutionSplitManifest manifest,
        EvolutionGenome seed
    ) {
        return EvolutionStudyPlan.create(
            "population_study_v1",
            Objective.OPEN_TARGET_OPERATOR,
            manifest,
            List.of(seed),
            List.of(
                EvolutionMutationKind.GENERALIZE_PLACEHOLDER,
                EvolutionMutationKind.COMPOSE_REWRITES,
                EvolutionMutationKind.ADD_ASSUMPTION),
            new PopulationPolicy(8, 3, 2, 4, 2, 2, 42L),
            List.of(
                new FitnessWeight(FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, 600),
                new FitnessWeight(FitnessComponent.STRUCTURAL_DIVERSITY, 400)),
            new StudyBudget(100, 80, 24, 12, 4));
    }

    private static EvolutionGenome seedGenome(EvolutionSplitManifest manifest) {
        RewriteGene gene = new RewriteGene(
            "remove_additive_zero",
            "?x+0",
            "?x",
            RewriteKind.SIMPLIFY,
            true,
            -2,
            4,
            4,
            List.of(),
            List.of(
                EvidenceObligation.SEMANTIC_VALIDATION,
                EvidenceObligation.COUNTEREXAMPLE_SEARCH,
                EvidenceObligation.HOLDOUT_EVALUATION));
        return EvolutionGenome.create(
            Objective.OPEN_TARGET_OPERATOR,
            manifest.trainingScope(),
            List.of(gene),
            List.of(
                new FeatureWeight(FitnessSignal.UNSEEN_TRAIN_CASES_SOLVED, 500),
                new FeatureWeight(FitnessSignal.COUNTEREXAMPLE_RISK, -500)),
            GuardPolicy.strictDefault(),
            new ResourceBudget(16, 128, 12, 32, 80),
            List.of("core.ast-rewrite"),
            List.of());
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

    private static EvolutionSplitManifest.CaseReference copy(
        EvolutionSplitManifest.CaseReference source,
        String field,
        String value
    ) {
        return new EvolutionSplitManifest.CaseReference(
            field.equals("caseId") ? value : source.caseId(),
            field.equals("familyId") ? value : source.familyId(),
            field.equals("exactSignatureHash") ? value : source.exactSignatureHash(),
            field.equals("alphaSignatureHash") ? value : source.alphaSignatureHash(),
            field.equals("inputHash") ? value : source.inputHash(),
            field.equals("hiddenTargetHash") ? value : source.hiddenTargetHash());
    }

    private static String hash(String material) {
        return EvolutionGenome.hash(material);
    }
}
