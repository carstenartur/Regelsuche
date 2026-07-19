package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class EvolutionPopulationEngineTest {
    private final EvolutionPopulationEngine engine =
        new EvolutionPopulationEngine();

    @Test
    void pinnedParallelRunIsCanonicalDiverseAndLineageBounded() {
        Fixture fixture = fixture(
            List.of(
                EvolutionMutationKind.GENERALIZE_PLACEHOLDER,
                EvolutionMutationKind.SPECIALIZE_PLACEHOLDER,
                EvolutionMutationKind.REVERSE_REWRITE,
                EvolutionMutationKind.COMPOSE_REWRITES),
            new PopulationPolicy(4, 3, 1, 2, 2, 4, 20260719L),
            new StudyBudget(256, 128, 1, 1, 1));

        var evaluator = deterministicEvaluator();
        var first = engine.run(
            fixture.plan(), fixture.seeds(), MutationCatalog.empty(), evaluator);
        var second = engine.run(
            fixture.plan(), fixture.seeds(), MutationCatalog.empty(), evaluator);

        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertEquals(
            first.generationReports().stream()
                .map(EvolutionPopulationEngine.GenerationReport::toCanonicalJson)
                .toList(),
            second.generationReports().stream()
                .map(EvolutionPopulationEngine.GenerationReport::toCanonicalJson)
                .toList());
        assertFalse(first.generationReports().isEmpty());
        assertTrue(first.mutationAttempts() <= fixture.plan().budget().maxMutationAttempts());
        assertTrue(first.trainEvaluations() <= fixture.plan().budget().maxTrainEvaluations());
        assertEquals(
            first.finalPopulation().size(),
            new HashSet<>(first.finalPopulation().stream()
                .map(EvolutionGenome::alphaStructuralHash)
                .toList()).size());
        assertTrue(first.generationReports().stream()
            .flatMap(report -> report.acceptedLineage().stream())
            .findAny().isPresent());

        for (var report : first.generationReports()) {
            assertEquals(
                report.selectedGenomeHashes().size(),
                report.distinctAlphaStructures());
            assertTrue(report.candidates().stream().allMatch(candidate ->
                candidate.rawComponents().keySet().equals(EnumSet.of(
                    FitnessComponent.TRAIN_CASES_NEWLY_SOLVED,
                    FitnessComponent.STRUCTURAL_DIVERSITY))));
            assertTrue(report.acceptedLineage().stream().allMatch(edge ->
                fixture.plan().mutationOperators().contains(edge.mutationKind())));
            Map<String, Long> offspringPerParent = report.acceptedLineage().stream()
                .collect(Collectors.groupingBy(
                    EvolutionPopulationEngine.LineageEdge::parentGenomeHash,
                    Collectors.counting()));
            assertTrue(offspringPerParent.values().stream().allMatch(count ->
                count <= fixture.plan().populationPolicy().maxOffspringPerLineage()));
        }

        String runJson = first.toCanonicalJson();
        assertTrue(runJson.contains("\"validationStatus\":\"NOT_EVALUATED\""));
        assertTrue(runJson.contains("\"finalTestStatus\":\"NOT_EVALUATED\""));
        assertFalse(runJson.contains("selectedConfigurationHash"));
        assertFalse(runJson.contains("finalTestOutcome"));
    }

    @Test
    void hardBlockersRemainSeparateFromFitnessAndUndeclaredMutationsAreNotEvaluated() {
        Fixture fixture = fixture(
            List.of(EvolutionMutationKind.GENERALIZE_PLACEHOLDER),
            new PopulationPolicy(4, 3, 1, 2, 2, 3, 17L),
            new StudyBudget(256, 128, 1, 1, 1));
        MutationCatalog catalog = new MutationCatalog(
            List.of(),
            List.of(new FeatureWeight(FitnessSignal.RUNTIME_COST, -100)),
            List.of(0, 1, 2, 3));

        var run = engine.run(
            fixture.plan(),
            fixture.seeds(),
            catalog,
            genome -> {
                Map<FitnessComponent, Integer> components = Map.of(
                    FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, 500,
                    FitnessComponent.STRUCTURAL_DIVERSITY, 500);
                if (!genome.seedGenomeHashes().isEmpty()) {
                    return EvolutionPopulationEngine.TrainFitness.blocked(
                        components,
                        "SYNTHETIC_HARD_BLOCKER");
                }
                return EvolutionPopulationEngine.TrainFitness.scored(components);
            });

        assertTrue(run.generationReports().stream()
            .flatMap(report -> report.candidates().stream())
            .anyMatch(candidate ->
                candidate.blockers().contains("SYNTHETIC_HARD_BLOCKER")
                    && candidate.weightedScorePermille() == 0));
        assertTrue(run.generationReports().stream()
            .flatMap(report -> report.acceptedLineage().stream())
            .allMatch(edge ->
                edge.mutationKind() == EvolutionMutationKind.GENERALIZE_PLACEHOLDER));
        assertTrue(run.generationReports().stream()
            .flatMap(report -> report.rejectedMutations().stream())
            .anyMatch(rejection -> rejection.blockers().stream().anyMatch(blocker ->
                blocker.startsWith("MUTATION_KIND_NOT_PREREGISTERED:"))));
        assertTrue(run.finalPopulation().stream().allMatch(genome ->
            genome.seedGenomeHashes().isEmpty()));
    }

    @Test
    void trainEvaluationBudgetFailsClosedWithExplicitTerminalEvidence() {
        Fixture fixture = fixture(
            List.of(EvolutionMutationKind.GENERALIZE_PLACEHOLDER),
            new PopulationPolicy(4, 3, 1, 2, 2, 2, 9L),
            new StudyBudget(64, 1, 1, 1, 1));

        var run = engine.run(
            fixture.plan(),
            fixture.seeds(),
            MutationCatalog.empty(),
            deterministicEvaluator());

        assertEquals(1, run.trainEvaluations());
        assertEquals(
            EvolutionPopulationEngine.TerminalOutcome.DIVERSITY_FLOOR_UNMET,
            run.terminalOutcome());
        assertTrue(run.generationReports().getFirst().candidates().stream()
            .anyMatch(candidate -> candidate.blockers().contains(
                "TRAIN_EVALUATION_BUDGET_EXHAUSTED")));
    }

    private static EvolutionPopulationEngine.TrainFitnessEvaluator
            deterministicEvaluator() {
        return genome -> {
            int primary = 300 + Integer.parseInt(
                genome.contentHash().substring(7, 9), 16) % 600;
            return EvolutionPopulationEngine.TrainFitness.scored(Map.of(
                FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, primary,
                FitnessComponent.STRUCTURAL_DIVERSITY, 1000 - primary));
        };
    }

    private static Fixture fixture(
        List<EvolutionMutationKind> mutationKinds,
        PopulationPolicy populationPolicy,
        StudyBudget budget
    ) {
        EvolutionSplitManifest manifest = EvolutionSplitManifest.create(
            "population_engine_study_v1",
            hash("population-engine-corpus"),
            hash("population-engine-feature-schema"),
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
            "population_engine_study_v1",
            Objective.OPEN_TARGET_OPERATOR,
            manifest,
            seeds,
            mutationKinds,
            populationPolicy,
            List.of(
                new FitnessWeight(
                    FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, 700),
                new FitnessWeight(
                    FitnessComponent.STRUCTURAL_DIVERSITY, 300)),
            budget);
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
