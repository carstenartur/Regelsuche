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
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessWeight;
import de.regelsuche.evolution.EvolutionStudyPlan.PopulationPolicy;
import de.regelsuche.evolution.EvolutionStudyPlan.StudyBudget;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EvolutionPopulationCheckpointTest {
    private final EvolutionPopulationEngine engine =
        new EvolutionPopulationEngine();

    @Test
    void resumedRunIsByteIdenticalAndDoesNotRepeatTrainEvaluations() {
        Fixture fixture = fixture();
        MutationCatalog catalog = MutationCatalog.empty();
        AtomicInteger uninterruptedCalls = new AtomicInteger();
        var uninterrupted = engine.run(
            fixture.plan(),
            fixture.seeds(),
            catalog,
            countingEvaluator(uninterruptedCalls));

        AtomicInteger checkpointCalls = new AtomicInteger();
        EvolutionPopulationCheckpoint checkpoint = engine.checkpoint(
            fixture.plan(),
            fixture.seeds(),
            catalog,
            countingEvaluator(checkpointCalls),
            1);
        String json = checkpoint.toCanonicalJson();
        EvolutionPopulationCheckpoint restored =
            EvolutionPopulationCheckpoint.fromCanonicalJson(json);
        AtomicInteger resumeCalls = new AtomicInteger();
        var resumed = engine.resume(
            fixture.plan(),
            fixture.seeds(),
            catalog,
            countingEvaluator(resumeCalls),
            restored);

        assertEquals(uninterrupted.toCanonicalJson(), resumed.toCanonicalJson());
        assertEquals(
            uninterruptedCalls.get(),
            checkpointCalls.get() + resumeCalls.get());
        assertEquals(json, restored.toCanonicalJson());
        assertEquals(1, restored.completedGeneration());
        assertEquals(2, restored.nextGeneration());
        assertTrue(json.contains("\"validationStatus\":\"NOT_EVALUATED\""));
        assertTrue(json.contains("\"finalTestStatus\":\"NOT_EVALUATED\""));
        assertFalse(json.contains("validationCases"));
        assertFalse(json.contains("finalTestOutcome"));
    }

    @Test
    void rejectsCatalogSubstitutionAndUnknownJsonFields() {
        Fixture fixture = fixture();
        EvolutionPopulationCheckpoint checkpoint = engine.checkpoint(
            fixture.plan(),
            fixture.seeds(),
            MutationCatalog.empty(),
            countingEvaluator(new AtomicInteger()),
            1);
        MutationCatalog replacement = new MutationCatalog(
            List.of(),
            List.of(new FeatureWeight(FitnessSignal.RUNTIME_COST, -100)),
            List.of(0, 1, 2, 3));

        assertThrows(IllegalArgumentException.class, () -> engine.resume(
            fixture.plan(),
            fixture.seeds(),
            replacement,
            countingEvaluator(new AtomicInteger()),
            checkpoint));
        String tampered = checkpoint.toCanonicalJson().replaceFirst(
            "\\{", "{\"unexpected\":true,");
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionPopulationCheckpoint.fromCanonicalJson(tampered));
    }

    private static EvolutionPopulationEngine.TrainFitnessEvaluator
            countingEvaluator(AtomicInteger calls) {
        return genome -> {
            calls.incrementAndGet();
            int primary = 300 + Integer.parseInt(
                genome.contentHash().substring(7, 9), 16) % 600;
            return EvolutionPopulationEngine.TrainFitness.scored(Map.of(
                FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, primary,
                FitnessComponent.STRUCTURAL_DIVERSITY, 1000 - primary));
        };
    }

    private static Fixture fixture() {
        EvolutionSplitManifest manifest = EvolutionSplitManifest.create(
            "population_checkpoint_study_v1",
            hash("checkpoint-corpus"),
            hash("checkpoint-feature-schema"),
            List.of(caseRef("train_case", "train_family", "train")),
            List.of(caseRef(
                "validation_case", "validation_family", "validation")),
            List.of(caseRef("test_case", "test_family", "test")));
        List<EvolutionGenome> seeds = List.of(
            seed(manifest, EvolutionGenomeTestFixtures.gene(
                "remove_additive_zero", "?A+0", "?A")),
            seed(manifest, EvolutionGenomeTestFixtures.gene(
                "remove_multiplicative_one", "?B*1", "?B")));
        EvolutionStudyPlan plan = EvolutionStudyPlan.create(
            "population_checkpoint_study_v1",
            Objective.OPEN_TARGET_OPERATOR,
            manifest,
            seeds,
            List.of(
                EvolutionMutationKind.GENERALIZE_PLACEHOLDER,
                EvolutionMutationKind.SPECIALIZE_PLACEHOLDER,
                EvolutionMutationKind.REVERSE_REWRITE,
                EvolutionMutationKind.COMPOSE_REWRITES),
            new PopulationPolicy(4, 4, 1, 2, 2, 4, 20260723L),
            List.of(
                new FitnessWeight(
                    FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, 700),
                new FitnessWeight(
                    FitnessComponent.STRUCTURAL_DIVERSITY, 300)),
            new StudyBudget(512, 256, 1, 1, 1));
        return new Fixture(plan, seeds);
    }

    private static EvolutionGenome seed(
        EvolutionSplitManifest manifest,
        EvolutionGenome.RewriteGene gene
    ) {
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

    private static String hash(String material) {
        return EvolutionGenome.hash(material);
    }

    private record Fixture(EvolutionStudyPlan plan, List<EvolutionGenome> seeds) {
    }
}
