package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationExecutionProtocol.MutationSeedDerivationPolicy;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationExecutionProtocol.OffspringSchedulingPolicy;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationExecutionProtocol.ProposalOrderingPolicy;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationExecutionProtocol.SurvivorSelectionPolicy;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.EvaluatorProfile;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.TrainCase;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessWeight;
import de.regelsuche.evolution.EvolutionStudyPlan.PopulationPolicy;
import de.regelsuche.evolution.EvolutionStudyPlan.StudyBudget;
import de.regelsuche.search.SearchHeuristic;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionProtocolBoundEvolutionRewriteProgramCheckpointArtifactTest {
    private static final Set<FitnessComponent> COMPONENTS = Set.of(
        FitnessComponent.TRAIN_CASES_NEWLY_SOLVED,
        FitnessComponent.CANDIDATE_COMPLEXITY);

    @TempDir
    Path temporaryDirectory;

    private final ExecutionProtocolBoundEvolutionRewriteProgramCheckpointArtifact
        artifact =
            new ExecutionProtocolBoundEvolutionRewriteProgramCheckpointArtifact();

    @Test
    void persistedReloadedExecutionBoundCheckpointResumesIdentically()
            throws Exception {
        Fixture fixture = fixture();
        EvolutionRewriteProgramPopulationExecutionProtocol protocol =
            EvolutionRewriteProgramPopulationExecutionProtocol.legacyV1();
        EvolutionRewriteProgramPopulationExecutionPlan plan =
            EvolutionRewriteProgramPopulationExecutionPlan.create(
                fixture.study(), protocol);
        var runner =
            new ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRunner(
                protocol);
        var uninterrupted = runner.run(
            plan,
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            evaluator(fixture.suite()));
        var checkpoint = runner.checkpoint(
            plan,
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            evaluator(fixture.suite()),
            1);

        Path directory = temporaryDirectory.resolve("bound-checkpoint");
        var manifest = artifact.write(directory, checkpoint);
        var loaded = artifact.read(directory, plan, protocol);
        var resumed = runner.resume(
            plan,
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            evaluator(fixture.suite()),
            loaded.checkpoint());

        assertEquals(
            checkpoint.toCanonicalJson(),
            loaded.checkpoint().toCanonicalJson());
        assertEquals(checkpoint.contentHash(), manifest.boundCheckpointHash());
        assertEquals(manifest, loaded.manifest());
        assertEquals(
            uninterrupted.toCanonicalJson(),
            resumed.toCanonicalJson());
        assertEquals(plan.contentHash(), manifest.executionPlanHash());
        assertEquals(protocol.contentHash(), manifest.executionProtocolHash());
        assertTrue(Files.isRegularFile(directory.resolve(
            ExecutionProtocolBoundEvolutionRewriteProgramCheckpointArtifact
                .MANIFEST_FILE_NAME)));
        assertTrue(Files.isDirectory(directory.resolve(
            ExecutionProtocolBoundEvolutionRewriteProgramCheckpointArtifact
                .CHECKPOINT_DIRECTORY_NAME)));
    }

    @Test
    void repeatedExportsAreByteIdentical() throws Exception {
        Fixture fixture = fixture();
        EvolutionRewriteProgramPopulationExecutionProtocol protocol =
            EvolutionRewriteProgramPopulationExecutionProtocol.legacyV1();
        EvolutionRewriteProgramPopulationExecutionPlan plan =
            EvolutionRewriteProgramPopulationExecutionPlan.create(
                fixture.study(), protocol);
        var runner =
            new ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRunner(
                protocol);
        var checkpoint = runner.checkpoint(
            plan,
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            evaluator(fixture.suite()),
            1);
        Path first = temporaryDirectory.resolve("first");
        Path second = temporaryDirectory.resolve("second");

        artifact.write(first, checkpoint);
        artifact.write(second, checkpoint);

        assertEquals(
            Files.readString(first.resolve(
                ExecutionProtocolBoundEvolutionRewriteProgramCheckpointArtifact
                    .MANIFEST_FILE_NAME), StandardCharsets.UTF_8),
            Files.readString(second.resolve(
                ExecutionProtocolBoundEvolutionRewriteProgramCheckpointArtifact
                    .MANIFEST_FILE_NAME), StandardCharsets.UTF_8));
        for (String name : List.of(
                EvolutionRewriteProgramCheckpointArtifact.CHECKPOINT_FILE_NAME,
                EvolutionRewriteProgramCheckpointArtifact.STATE_FILE_NAME,
                EvolutionRewriteProgramCheckpointArtifact.MANIFEST_FILE_NAME)) {
            Path firstNested = first.resolve(
                ExecutionProtocolBoundEvolutionRewriteProgramCheckpointArtifact
                    .CHECKPOINT_DIRECTORY_NAME).resolve(name);
            Path secondNested = second.resolve(
                ExecutionProtocolBoundEvolutionRewriteProgramCheckpointArtifact
                    .CHECKPOINT_DIRECTORY_NAME).resolve(name);
            assertEquals(
                Files.readString(firstNested, StandardCharsets.UTF_8),
                Files.readString(secondNested, StandardCharsets.UTF_8));
        }
    }

    @Test
    void missingOuterManifestAndTamperingFailClosed() throws Exception {
        Fixture fixture = fixture();
        EvolutionRewriteProgramPopulationExecutionProtocol protocol =
            EvolutionRewriteProgramPopulationExecutionProtocol.legacyV1();
        EvolutionRewriteProgramPopulationExecutionPlan plan =
            EvolutionRewriteProgramPopulationExecutionPlan.create(
                fixture.study(), protocol);
        var runner =
            new ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRunner(
                protocol);
        var checkpoint = runner.checkpoint(
            plan,
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            evaluator(fixture.suite()),
            1);

        Path missing = temporaryDirectory.resolve("missing-manifest");
        artifact.write(missing, checkpoint);
        Files.delete(missing.resolve(
            ExecutionProtocolBoundEvolutionRewriteProgramCheckpointArtifact
                .MANIFEST_FILE_NAME));
        assertThrows(
            IllegalArgumentException.class,
            () -> artifact.read(missing, plan, protocol));

        Path tampered = temporaryDirectory.resolve("tampered-nested");
        artifact.write(tampered, checkpoint);
        Path state = tampered.resolve(
            ExecutionProtocolBoundEvolutionRewriteProgramCheckpointArtifact
                .CHECKPOINT_DIRECTORY_NAME).resolve(
                    EvolutionRewriteProgramCheckpointArtifact.STATE_FILE_NAME);
        Files.writeString(
            state,
            Files.readString(state, StandardCharsets.UTF_8) + " ",
            StandardCharsets.UTF_8);
        assertThrows(
            IllegalArgumentException.class,
            () -> artifact.read(tampered, plan, protocol));
    }

    @Test
    void wrongExecutionIdentityFailsBeforeResume() throws Exception {
        Fixture fixture = fixture();
        EvolutionRewriteProgramPopulationExecutionProtocol legacy =
            EvolutionRewriteProgramPopulationExecutionProtocol.legacyV1();
        EvolutionRewriteProgramPopulationExecutionPlan legacyPlan =
            EvolutionRewriteProgramPopulationExecutionPlan.create(
                fixture.study(), legacy);
        var runner =
            new ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRunner(
                legacy);
        var checkpoint = runner.checkpoint(
            legacyPlan,
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            evaluator(fixture.suite()),
            1);
        Path directory = temporaryDirectory.resolve("wrong-protocol");
        artifact.write(directory, checkpoint);

        EvolutionRewriteProgramPopulationExecutionProtocol future =
            EvolutionRewriteProgramPopulationExecutionProtocol.create(
                EvolutionRewriteProgramPopulationEngine.class,
                DeterministicRewriteProgramMutator.class,
                ProposalOrderingPolicy
                    .KEY_ASCENDING_THEN_GLOBAL_SEED_ROTATION_V1,
                OffspringSchedulingPolicy.STRATIFIED_MUTATION_KIND_V1,
                2,
                MutationSeedDerivationPolicy
                    .STUDY_HASH_GENERATION_PARENT_HASH_SHA256_PREFIX64_V1,
                SurvivorSelectionPolicy
                    .FITNESS_DESC_NODES_ASC_HASH_ASC_UNIQUE_ALPHA_ELITES_V1);
        EvolutionRewriteProgramPopulationExecutionPlan futurePlan =
            EvolutionRewriteProgramPopulationExecutionPlan.create(
                fixture.study(), future);

        assertThrows(
            IllegalArgumentException.class,
            () -> artifact.read(directory, futurePlan, future));
    }

    @Test
    void strictSchemaDescribesOuterCommitBoundary() throws Exception {
        Path schema = repositoryRoot().resolve("docs/schemas/")
            .resolve(
                "regelsuche-evolution-rewrite-program-"
                    + "execution-protocol-bound-checkpoint-artifact-v1.schema.json");
        String json = Files.readString(schema, StandardCharsets.UTF_8);

        assertTrue(json.contains(
            "regelsuche.evolution-rewrite-program-"
                + "execution-protocol-bound-checkpoint-artifact/v1"));
        assertTrue(json.contains("\"executionPlanHash\""));
        assertTrue(json.contains("\"executionProtocolHash\""));
        assertTrue(json.contains(
            "\"const\": \"OUTER_MANIFEST_LAST_ATOMIC_RENAME_V1\""));
        assertTrue(json.contains("\"additionalProperties\": false"));
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
            "durable_execution_bound_population_study_v1",
            hash("durable-execution-bound-corpus"),
            hash("durable-execution-bound-features"),
            List.of(caseRef(
                "train_durable_execution_case",
                "train_durable_execution_family",
                "train-durable-execution")),
            List.of(caseRef(
                "validation_durable_execution_case",
                "validation_durable_execution_family",
                "validation-durable-execution")),
            List.of(caseRef(
                "final_durable_execution_case",
                "final_durable_execution_family",
                "final-durable-execution")));
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
        EvolutionRewriteProgramPlan addZeroSeedPlan =
            EvolutionRewriteProgramPlan.create(
                genome,
                new Source("durable_bound_seed_add_zero", List.of("add_zero")),
                12,
                12);
        EvolutionRewriteProgramPlan multiplyOneSeedPlan =
            EvolutionRewriteProgramPlan.create(
                genome,
                new EvolutionRewriteProgramPlan.Repeat(
                    "durable_bound_seed_mul_one_repeat",
                    new Source(
                        "durable_bound_seed_mul_one",
                        List.of("mul_one")),
                    1,
                    2),
                12,
                12);
        List<EvolutionRewriteProgramCandidate> seeds = List.of(
            EvolutionRewriteProgramCandidate.create(genome, addZeroSeedPlan),
            EvolutionRewriteProgramCandidate.create(
                genome, multiplyOneSeedPlan));
        EvolutionRewriteProgramTrainSuite suite =
            EvolutionRewriteProgramTrainSuite.create(
                "durable_execution_bound_train_suite",
                EvaluatorProfile
                    .EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
                List.of(new TrainCase(
                    "train_durable_execution_case",
                    "train_durable_execution_family",
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
        EvolutionRewriteProgramEvaluationProtocol evaluatorProtocol =
            EvolutionRewriteProgramEvaluationProtocol
                .informationParityExactRationalV1();
        EvolutionRewriteProgramStudyPlan study =
            EvolutionRewriteProgramStudyPlan.create(
                manifest.studyId(),
                manifest,
                suite,
                evaluatorProtocol,
                catalog,
                seeds,
                Arrays.asList(EvolutionRewriteProgramMutationKind.values()),
                new PopulationPolicy(
                    4, 3, 1, 2, 2, 2, 20260809L),
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
