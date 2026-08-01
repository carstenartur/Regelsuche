package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationCatalog;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.RepeatBounds;
import de.regelsuche.evolution.EvolutionGenome.FeatureWeight;
import de.regelsuche.evolution.EvolutionGenome.FitnessSignal;
import de.regelsuche.evolution.EvolutionGenome.GuardPolicy;
import de.regelsuche.evolution.EvolutionGenome.Objective;
import de.regelsuche.evolution.EvolutionGenome.ResourceBudget;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Priority;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Requirement;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.PopulationRun;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainFitnessEvidence.CaseMeasurement;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainFitnessEvidence.PathCorrectness;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.EvaluatorProfile;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.TrainCase;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessWeight;
import de.regelsuche.evolution.EvolutionStudyPlan.PopulationPolicy;
import de.regelsuche.evolution.EvolutionStudyPlan.StudyBudget;
import de.regelsuche.search.SearchHeuristic;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramPopulationEngineTest {
    private final EvolutionRewriteProgramPopulationEngine engine =
        new EvolutionRewriteProgramPopulationEngine();

    @Test
    void repeatedRunsAreCanonicalAndUseCombinedTopologyDiversity() {
        Fixture fixture = fixture();

        PopulationRun first = engine.run(
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            scoringEvaluator(fixture.suite(), new AtomicInteger()));
        PopulationRun second = engine.run(
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            scoringEvaluator(fixture.suite(), new AtomicInteger()));

        assertEquals(first, second);
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertFalse(first.finalCandidateHashes().isEmpty());
        assertFalse(first.generationReports().isEmpty());
        assertTrue(first.generationReports().stream()
            .flatMap(report -> report.lineage().stream())
            .anyMatch(edge -> !edge.childCandidateHash()
                .equals(edge.parentCandidateHash())));
        assertTrue(first.generationReports().stream()
            .allMatch(report -> report.distinctAlphaStructures() >= 2));
        assertTrue(first.toCanonicalJson().contains(
            "\"validationStatus\":\"NOT_EVALUATED\""));
        assertTrue(first.toCanonicalJson().contains(
            "\"finalTestStatus\":\"NOT_EVALUATED\""));
        assertFalse(first.toCanonicalJson().contains("validationCases"));
        assertFalse(first.toCanonicalJson().contains("finalTestOutcome"));
    }

    @Test
    void checkpointResumeMatchesUninterruptedAndDoesNotReevaluateCandidates() {
        Fixture fixture = fixture();
        AtomicInteger uninterruptedCalls = new AtomicInteger();
        PopulationRun uninterrupted = engine.run(
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            scoringEvaluator(fixture.suite(), uninterruptedCalls));

        AtomicInteger checkpointCalls = new AtomicInteger();
        var checkpoint = engine.checkpoint(
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            scoringEvaluator(fixture.suite(), checkpointCalls),
            1);
        AtomicInteger resumeCalls = new AtomicInteger();
        PopulationRun resumed = engine.resume(
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            scoringEvaluator(fixture.suite(), resumeCalls),
            checkpoint);

        assertEquals(uninterrupted.toCanonicalJson(), resumed.toCanonicalJson());
        assertEquals(
            uninterruptedCalls.get(),
            checkpointCalls.get() + resumeCalls.get());
        assertEquals(1, checkpoint.completedGeneration());
        assertEquals(2, checkpoint.nextGeneration());
        assertTrue(checkpoint.toCanonicalJson().contains(
            "\"validationStatus\":\"NOT_EVALUATED\""));
        assertTrue(checkpoint.toCanonicalJson().contains(
            "\"finalTestStatus\":\"NOT_EVALUATED\""));
    }

    @Test
    void everyBlockedSeedProducesTransparentExtinction() {
        Fixture fixture = fixture();
        PopulationRun run = engine.run(
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            candidate -> evidence(
                fixture.suite(),
                candidate,
                Map.of(
                    FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, 0,
                    FitnessComponent.CANDIDATE_COMPLEXITY, 0),
                List.of("FORCED_TRAIN_BLOCKER")));

        assertEquals(
            EvolutionRewriteProgramPopulationEngine.TerminalOutcome.EXTINCT,
            run.terminalOutcome());
        assertTrue(run.finalCandidateHashes().isEmpty());
        assertEquals(1, run.generationReports().size());
        assertTrue(run.generationReports().getFirst()
            .selectedCandidateHashes().isEmpty());
        assertTrue(run.generationReports().getFirst().evaluations().stream()
            .allMatch(evaluation -> evaluation.blockers().contains(
                "FORCED_TRAIN_BLOCKER")));
    }

    @Test
    void rejectsSplitSuiteCatalogSeedAndCheckpointSubstitution() {
        Fixture fixture = fixture();
        var checkpoint = engine.checkpoint(
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            scoringEvaluator(fixture.suite(), new AtomicInteger()),
            1);
        MutationCatalog replacementCatalog = new MutationCatalog(
            List.of(new RepeatBounds(1, 3)),
            List.of(Requirement.maxPrimitiveSteps(3)),
            List.of(Priority.estimatedCostThenRule()),
            List.of(4),
            List.of("mul_one"));

        assertThrows(IllegalArgumentException.class, () -> engine.resume(
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            replacementCatalog,
            scoringEvaluator(fixture.suite(), new AtomicInteger()),
            checkpoint));

        EvolutionRewriteProgramTrainSuite substitutedSuite =
            EvolutionRewriteProgramTrainSuite.create(
                "substituted_train_suite",
                EvaluatorProfile
                    .EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
                List.of(new TrainCase(
                    "train_program_case",
                    "train_program_family",
                    "x+0",
                    "x",
                    List.of())),
                fixture.suite().heuristic());
        assertThrows(IllegalArgumentException.class, () -> engine.resume(
            fixture.study(),
            fixture.manifest(),
            substitutedSuite,
            fixture.seeds(),
            fixture.catalog(),
            scoringEvaluator(substitutedSuite, new AtomicInteger()),
            checkpoint));
    }

    @Test
    void studyFreezeRequiresExactTrainCasesAndCatalogGenesInEverySeed() {
        Fixture fixture = fixture();
        EvolutionRewriteProgramTrainSuite wrongFamily =
            EvolutionRewriteProgramTrainSuite.create(
                "program_population_train_suite",
                EvaluatorProfile
                    .EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
                List.of(new TrainCase(
                    "train_program_case",
                    "different_train_family",
                    "x+0",
                    "x",
                    List.of())),
                fixture.suite().heuristic());
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionRewriteProgramStudyPlan.create(
                fixture.manifest().studyId(),
                fixture.manifest(),
                wrongFamily,
                fixture.catalog(),
                fixture.seeds(),
                Arrays.asList(EvolutionRewriteProgramMutationKind.values()),
                fixture.study().populationPolicy(),
                fixture.study().fitnessWeights(),
                fixture.study().budget()));

        MutationCatalog unknownGene = new MutationCatalog(
            List.of(), List.of(), List.of(), List.of(),
            List.of("unknown_gene"));
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionRewriteProgramStudyPlan.create(
                fixture.manifest().studyId(),
                fixture.manifest(),
                fixture.suite(),
                unknownGene,
                fixture.seeds(),
                Arrays.asList(EvolutionRewriteProgramMutationKind.values()),
                fixture.study().populationPolicy(),
                fixture.study().fitnessWeights(),
                fixture.study().budget()));
    }

    private static EvolutionRewriteProgramPopulationEngine.ProgramFitnessEvaluator
            scoringEvaluator(
                EvolutionRewriteProgramTrainSuite suite,
                AtomicInteger calls
            ) {
        return candidate -> {
            calls.incrementAndGet();
            int topologyValue = Math.min(1000,
                candidate.plan().nodeCount() * 120);
            int simplicity = Math.max(0,
                1000 - candidate.plan().nodeCount() * 25);
            return evidence(
                suite,
                candidate,
                Map.of(
                    FitnessComponent.TRAIN_CASES_NEWLY_SOLVED,
                    topologyValue,
                    FitnessComponent.CANDIDATE_COMPLEXITY,
                    simplicity),
                List.of());
        };
    }

    private static EvolutionRewriteProgramTrainFitnessEvidence evidence(
        EvolutionRewriteProgramTrainSuite suite,
        EvolutionRewriteProgramCandidate candidate,
        Map<FitnessComponent, Integer> components,
        List<String> blockers
    ) {
        TrainCase trainCase = suite.cases().getFirst();
        CaseMeasurement measurement = new CaseMeasurement(
            trainCase.caseId(),
            trainCase.familyId(),
            "TARGET_NOT_REACHED",
            "TARGET_NOT_REACHED",
            false,
            false,
            PathCorrectness.NOT_EVALUATED,
            PathCorrectness.NOT_EVALUATED,
            -1,
            -1,
            0,
            0,
            1,
            1,
            0,
            0,
            false,
            false,
            false,
            false,
            false);
        return EvolutionRewriteProgramTrainFitnessEvidence.create(
            suite,
            candidate,
            List.of(measurement),
            components,
            blockers);
    }

    private static Fixture fixture() {
        EvolutionSplitManifest manifest = EvolutionSplitManifest.create(
            "program_population_study_v1",
            hash("program-population-corpus"),
            hash("program-population-feature-schema"),
            List.of(caseRef(
                "train_program_case",
                "train_program_family",
                "train-program")),
            List.of(caseRef(
                "validation_program_case",
                "validation_program_family",
                "validation-program")),
            List.of(caseRef(
                "final_program_case",
                "final_program_family",
                "final-program")));
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
                "program_population_train_suite",
                EvaluatorProfile
                    .EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
                List.of(new TrainCase(
                    "train_program_case",
                    "train_program_family",
                    "x+0",
                    "x",
                    List.of())),
                new SearchHeuristic(4, 256, 1, 4, 80, 16));
        MutationCatalog catalog = new MutationCatalog(
            List.of(new RepeatBounds(1, 2)),
            List.of(Requirement.maxPrimitiveSteps(3)),
            List.of(Priority.estimatedCostThenRule()),
            List.of(4),
            List.of("mul_one"));
        EvolutionRewriteProgramStudyPlan study =
            EvolutionRewriteProgramStudyPlan.create(
                manifest.studyId(),
                manifest,
                suite,
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
        return new Fixture(manifest, suite, catalog, seeds, study);
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
        MutationCatalog catalog,
        List<EvolutionRewriteProgramCandidate> seeds,
        EvolutionRewriteProgramStudyPlan study
    ) {
    }
}
