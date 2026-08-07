package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvolutionRewriteProgramCheckpointArtifactTest {
    @TempDir
    Path temporaryDirectory;

    private final EvolutionRewriteProgramPopulationEngine engine =
        new EvolutionRewriteProgramPopulationEngine();
    private final EvolutionRewriteProgramCheckpointArtifact artifact =
        new EvolutionRewriteProgramCheckpointArtifact();

    @Test
    void persistedReloadedResumeMatchesUninterruptedRun() throws IOException {
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
        Path directory = temporaryDirectory.resolve("checkpoint");
        var manifest = artifact.write(directory, checkpoint);
        var loaded = artifact.read(directory);

        assertEquals(checkpoint.toCanonicalJson(), loaded.checkpoint().toCanonicalJson());
        assertEquals(checkpoint.contentHash(), manifest.checkpointHash());
        assertEquals(manifest, loaded.manifest());
        try (var entries = Files.list(directory)) {
            assertEquals(3L, entries.count());
        }

        AtomicInteger resumeCalls = new AtomicInteger();
        PopulationRun resumed = engine.resume(
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            scoringEvaluator(fixture.suite(), resumeCalls),
            loaded.checkpoint());

        assertEquals(uninterrupted.toCanonicalJson(), resumed.toCanonicalJson());
        assertEquals(
            uninterruptedCalls.get(),
            checkpointCalls.get() + resumeCalls.get());
        assertTrue(loaded.checkpoint().toCanonicalJson().contains(
            "\"validationStatus\":\"NOT_EVALUATED\""));
        assertTrue(loaded.checkpoint().toCanonicalJson().contains(
            "\"finalTestStatus\":\"NOT_EVALUATED\""));
    }

    @Test
    void repeatedExportsAreByteIdentical() throws IOException {
        Fixture fixture = fixture();
        var checkpoint = checkpoint(fixture);
        Path first = temporaryDirectory.resolve("first");
        Path second = temporaryDirectory.resolve("second");

        artifact.write(first, checkpoint);
        artifact.write(second, checkpoint);

        for (String name : List.of(
                EvolutionRewriteProgramCheckpointArtifact.CHECKPOINT_FILE_NAME,
                EvolutionRewriteProgramCheckpointArtifact.STATE_FILE_NAME,
                EvolutionRewriteProgramCheckpointArtifact.MANIFEST_FILE_NAME)) {
            assertEquals(
                Files.readString(first.resolve(name), StandardCharsets.UTF_8),
                Files.readString(second.resolve(name), StandardCharsets.UTF_8));
        }
    }

    @Test
    void tamperedStateIsRejectedBeforeResume() throws IOException {
        Fixture fixture = fixture();
        Path directory = temporaryDirectory.resolve("tampered");
        artifact.write(directory, checkpoint(fixture));
        Path state = directory.resolve(
            EvolutionRewriteProgramCheckpointArtifact.STATE_FILE_NAME);
        Files.writeString(
            state,
            Files.readString(state, StandardCharsets.UTF_8) + " ",
            StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> artifact.read(directory));
    }

    @Test
    void missingManifestAndUnexpectedEntriesFailClosed() throws IOException {
        Fixture fixture = fixture();
        Path missing = temporaryDirectory.resolve("missing");
        artifact.write(missing, checkpoint(fixture));
        Files.delete(missing.resolve(
            EvolutionRewriteProgramCheckpointArtifact.MANIFEST_FILE_NAME));
        assertThrows(IllegalArgumentException.class, () -> artifact.read(missing));

        Path unexpected = temporaryDirectory.resolve("unexpected");
        artifact.write(unexpected, checkpoint(fixture));
        Files.writeString(
            unexpected.resolve("extra.json"),
            "{}",
            StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> artifact.read(unexpected));
    }

    private EvolutionRewriteProgramPopulationEngine.PopulationCheckpoint checkpoint(
        Fixture fixture
    ) {
        return engine.checkpoint(
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            scoringEvaluator(fixture.suite(), new AtomicInteger()),
            1);
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
            "durable_program_population_study_v1",
            hash("durable-program-population-corpus"),
            hash("durable-program-population-feature-schema"),
            List.of(caseRef(
                "train_durable_program_case",
                "train_durable_program_family",
                "train-durable-program")),
            List.of(caseRef(
                "validation_durable_program_case",
                "validation_durable_program_family",
                "validation-durable-program")),
            List.of(caseRef(
                "final_durable_program_case",
                "final_durable_program_family",
                "final-durable-program")));
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
                "durable_program_population_train_suite",
                EvaluatorProfile
                    .EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
                List.of(new TrainCase(
                    "train_durable_program_case",
                    "train_durable_program_family",
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
                new PopulationPolicy(4, 3, 1, 2, 2, 2, 20260807L),
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
