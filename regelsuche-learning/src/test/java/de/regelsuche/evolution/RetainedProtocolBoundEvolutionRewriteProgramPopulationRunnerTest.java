package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.equivalence.AssumptionAwareEquivalenceService;
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
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.EvaluatorProfile;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.TrainCase;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessWeight;
import de.regelsuche.evolution.EvolutionStudyPlan.PopulationPolicy;
import de.regelsuche.evolution.EvolutionStudyPlan.StudyBudget;
import de.regelsuche.search.SearchHeuristic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RetainedProtocolBoundEvolutionRewriteProgramPopulationRunnerTest {
    private static final Set<FitnessComponent> COMPONENTS = Set.of(
        FitnessComponent.TRAIN_CASES_NEWLY_SOLVED,
        FitnessComponent.CANDIDATE_COMPLEXITY);

    private final RetainedProtocolBoundEvolutionRewriteProgramPopulationRunner
        runner =
            new RetainedProtocolBoundEvolutionRewriteProgramPopulationRunner();

    @Test
    void uninterruptedAndResumedRunsRetainIdenticalCompleteFinalCandidates() {
        Fixture fixture = fixture();

        RetainedEvolutionRewriteProgramPopulationRun uninterrupted =
            runner.run(
                fixture.study(),
                fixture.manifest(),
                fixture.suite(),
                fixture.seeds(),
                fixture.catalog(),
                evaluator(fixture.suite()));
        var checkpoint = runner.checkpoint(
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            evaluator(fixture.suite()),
            1);
        RetainedEvolutionRewriteProgramPopulationRun resumed =
            runner.resume(
                fixture.study(),
                fixture.manifest(),
                fixture.suite(),
                fixture.seeds(),
                fixture.catalog(),
                evaluator(fixture.suite()),
                checkpoint);

        assertEquals(
            uninterrupted.populationRun().toCanonicalJson(),
            resumed.populationRun().toCanonicalJson());
        assertEquals(
            uninterrupted.toCanonicalJson(),
            resumed.toCanonicalJson());
        assertEquals(
            uninterrupted.populationRun().finalCandidateHashes(),
            uninterrupted.finalCandidates().stream()
                .map(
                    RetainedEvolutionRewriteProgramPopulationRun
                        .RetainedCandidate::candidateHash)
                .toList());
        assertEquals(
            uninterrupted.populationRun().finalCandidateHashes(),
            uninterrupted.finalEvaluations().stream()
                .map(
                    EvolutionRewriteProgramPopulationEngine
                        .CandidateEvaluation::candidateHash)
                .toList());
        assertFalse(uninterrupted.finalCandidates().isEmpty());
        assertTrue(uninterrupted.finalCandidates().stream()
            .allMatch(candidate ->
                !candidate.genomeJson().isBlank()
                    && !candidate.planJson().isBlank()
                    && !candidate.humanReadableProgram().isBlank()));
        assertTrue(uninterrupted.toCanonicalJson().contains(
            "\"validationStatus\":\"NOT_EVALUATED\""));
        assertTrue(uninterrupted.toCanonicalJson().contains(
            "\"finalTestStatus\":\"NOT_EVALUATED\""));
        assertFalse(uninterrupted.toCanonicalJson().contains(
            "validationCases"));
        assertFalse(uninterrupted.toCanonicalJson().contains(
            "finalTestOutcome"));
    }

    @Test
    void finalRootsCannotBeDetachedFromTheirCompleteCandidatePayloads() {
        Fixture fixture = fixture();
        RetainedEvolutionRewriteProgramPopulationRun retained =
            runner.run(
                fixture.study(),
                fixture.manifest(),
                fixture.suite(),
                fixture.seeds(),
                fixture.catalog(),
                evaluator(fixture.suite()));

        assertThrows(IllegalArgumentException.class, () ->
            RetainedEvolutionRewriteProgramPopulationRun.create(
                retained.populationRun(),
                List.of()));
        assertEquals(
            retained.finalCandidates().stream()
                .map(
                    RetainedEvolutionRewriteProgramPopulationRun
                        .RetainedCandidate::candidateHash)
                .toList(),
            retained.candidates().stream()
                .map(EvolutionRewriteProgramCandidate::contentHash)
                .toList());
    }

    @Test
    void strictSchemaRetainsPayloadAndLaterStageBoundaries()
            throws Exception {
        String schema = Files.readString(repositoryRoot()
            .resolve("docs")
            .resolve("schemas")
            .resolve(
                "regelsuche-evolution-rewrite-program-"
                    + "retained-population-run-v1.schema.json"));

        assertTrue(schema.contains(
            "regelsuche.evolution-rewrite-program-"
                + "retained-population-run/v1"));
        assertTrue(schema.contains("\"populationRunJson\""));
        assertTrue(schema.contains("\"genomeJson\""));
        assertTrue(schema.contains("\"planJson\""));
        assertTrue(schema.contains("\"humanReadableProgram\""));
        assertTrue(schema.contains("\"additionalProperties\": false"));
        assertTrue(schema.contains("\"const\": \"NOT_EVALUATED\""));
    }

    private static EvolutionRewriteProgramFitnessEvaluator evaluator(
        EvolutionRewriteProgramTrainSuite suite
    ) {
        return new ProtocolBoundInformationParityRewriteProgramTrainFitnessEvaluator(
            suite,
            COMPONENTS,
            (left, right, assumptions) ->
                AssumptionAwareEquivalenceService.Evaluation.confirmed());
    }

    private static Fixture fixture() {
        EvolutionSplitManifest manifest = EvolutionSplitManifest.create(
            "retained_program_population_study_v1",
            hash("retained-program-population-corpus"),
            hash("retained-program-population-features"),
            List.of(caseRef(
                "train_retained_program_case",
                "train_retained_program_family",
                "train-retained-program")),
            List.of(caseRef(
                "validation_retained_program_case",
                "validation_retained_program_family",
                "validation-retained-program")),
            List.of(caseRef(
                "final_retained_program_case",
                "final_retained_program_family",
                "final-retained-program")));
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
                new FeatureWeight(
                    FitnessSignal.COUNTEREXAMPLE_RISK, -500)),
            GuardPolicy.strictDefault(),
            new ResourceBudget(16, 128, 12, 32, 80),
            List.of("core.ast-rewrite"),
            List.of());
        EvolutionRewriteProgramPlan seedPlan =
            EvolutionRewriteProgramPlan.create(
                genome,
                new Source("retained_seed_add_zero", List.of("add_zero")),
                12,
                12);
        List<EvolutionRewriteProgramCandidate> seeds = List.of(
            EvolutionRewriteProgramCandidate.create(genome, seedPlan));
        EvolutionRewriteProgramTrainSuite suite =
            EvolutionRewriteProgramTrainSuite.create(
                "retained_program_population_train_suite",
                EvaluatorProfile
                    .EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
                List.of(new TrainCase(
                    "train_retained_program_case",
                    "train_retained_program_family",
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
                Arrays.asList(
                    EvolutionRewriteProgramMutationKind.values()),
                new PopulationPolicy(
                    4, 3, 1, 2, 2, 2, 20260808L),
                List.of(
                    new FitnessWeight(
                        FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, 700),
                    new FitnessWeight(
                        FitnessComponent.CANDIDATE_COMPLEXITY, 300)),
                new StudyBudget(1000, 1000, 1, 1, 2));
        return new Fixture(
            manifest, suite, catalog, seeds, study);
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

    private static Path repositoryRoot() {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null
                && !Files.exists(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("repository root not found");
        }
        return root;
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
