package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExecutionProtocolBoundStratifiedEvolutionRewriteProgramPopulationRunnerTest {
    private static final Set<FitnessComponent> COMPONENTS = Set.of(
        FitnessComponent.TRAIN_CASES_NEWLY_SOLVED,
        FitnessComponent.CANDIDATE_COMPLEXITY);

    @Test
    void stratifiedProtocolRunsAndResumesWithIdenticalBoundEvidence() {
        Fixture fixture = fixture();
        EvolutionRewriteProgramPopulationExecutionProtocol protocol =
            EvolutionRewriteProgramPopulationExecutionProtocol
                .stratifiedMutationKindV1();
        EvolutionRewriteProgramPopulationExecutionPlan executionPlan =
            EvolutionRewriteProgramPopulationExecutionPlan.create(
                fixture.study(), protocol);
        var runner =
            new ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRunner(
                protocol);

        var uninterrupted = runner.run(
            executionPlan,
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            evaluator(fixture.suite()));
        var checkpoint = runner.checkpoint(
            executionPlan,
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            evaluator(fixture.suite()),
            1);
        var resumed = runner.resume(
            executionPlan,
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            evaluator(fixture.suite()),
            checkpoint);

        uninterrupted.requireCompatible(executionPlan, protocol);
        resumed.requireCompatible(executionPlan, protocol);
        checkpoint.requireCompatible(executionPlan, protocol);
        assertEquals(uninterrupted.toCanonicalJson(), resumed.toCanonicalJson());
        assertEquals(protocol.contentHash(), uninterrupted.executionProtocolHash());
        assertEquals(protocol.contentHash(), checkpoint.executionProtocolHash());
        assertEquals(executionPlan.contentHash(), uninterrupted.executionPlanHash());
        assertEquals(executionPlan.contentHash(), checkpoint.executionPlanHash());
        assertEquals(
            StratifiedMutationKindRewriteProgramMutator.class.getName(),
            protocol.mutatorImplementationClass());
        assertFalse(
            uninterrupted.retainedRun().retainedPopulation()
                .populationRun().generationReports().isEmpty());
    }

    @Test
    void stratifiedDiagnosticsAreDeterministicBoundAndTrainOnly() {
        Fixture fixture = fixture();
        EvolutionRewriteProgramPopulationExecutionProtocol protocol =
            EvolutionRewriteProgramPopulationExecutionProtocol
                .stratifiedMutationKindV1();
        EvolutionRewriteProgramPopulationExecutionPlan executionPlan =
            EvolutionRewriteProgramPopulationExecutionPlan.create(
                fixture.study(), protocol);
        var runner =
            new ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRunner(
                protocol);

        var first = runner.runWithDiagnostics(
            executionPlan,
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            evaluator(fixture.suite()));
        var second = runner.runWithDiagnostics(
            executionPlan,
            fixture.study(),
            fixture.manifest(),
            fixture.suite(),
            fixture.seeds(),
            fixture.catalog(),
            evaluator(fixture.suite()));

        assertEquals(first.run().toCanonicalJson(), second.run().toCanonicalJson());
        assertEquals(
            first.diagnostics().toCanonicalJson(),
            second.diagnostics().toCanonicalJson());
        assertEquals(
            first.run().contentHash(),
            first.diagnostics().executionBoundRunHash());
        assertEquals(
            first.run().retainedRun().retainedPopulation()
                .populationRun().contentHash(),
            first.diagnostics().populationRunHash());
        assertEquals(
            first.run().retainedRun().retainedPopulation()
                .populationRun().generationReports().size(),
            first.diagnostics().generations().size());
        assertTrue(first.diagnostics().mutationBatches().stream()
            .allMatch(batch -> batch.mutationBatchJson().contains(
                DeterministicRewriteProgramMutator.SCHEMA)));
        assertTrue(first.diagnostics().candidateStructures().size()
            >= fixture.seeds().size());
        assertEquals(
            EvolutionRewriteProgramTrainDiagnostics.DATA_SCOPE,
            first.diagnostics().dataScope());
        assertFalse(first.diagnostics().toCanonicalJson().contains(
            "validationCases"));
        assertFalse(first.diagnostics().toCanonicalJson().contains(
            "finalTestOutcome"));
    }

    @Test
    void legacyProtocolCannotRequestStratifiedDiagnostics() {
        Fixture fixture = fixture();
        EvolutionRewriteProgramPopulationExecutionProtocol protocol =
            EvolutionRewriteProgramPopulationExecutionProtocol.legacyV1();
        EvolutionRewriteProgramPopulationExecutionPlan executionPlan =
            EvolutionRewriteProgramPopulationExecutionPlan.create(
                fixture.study(), protocol);
        var runner =
            new ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRunner(
                protocol);

        assertThrows(
            IllegalArgumentException.class,
            () -> runner.runWithDiagnostics(
                executionPlan,
                fixture.study(),
                fixture.manifest(),
                fixture.suite(),
                fixture.seeds(),
                fixture.catalog(),
                evaluator(fixture.suite())));
    }

    @Test
    void stratifiedSchedulingWithLegacyMutatorIdentityIsNotExecutable() {
        EvolutionRewriteProgramPopulationExecutionProtocol implemented =
            EvolutionRewriteProgramPopulationExecutionProtocol
                .stratifiedMutationKindV1();
        EvolutionRewriteProgramPopulationExecutionProtocol wrongImplementation =
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

        assertNotEquals(implemented.contentHash(), wrongImplementation.contentHash());
        assertThrows(
            IllegalArgumentException.class,
            () -> new ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRunner(
                wrongImplementation));
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
            "stratified_program_population_study_v1",
            hash("stratified-program-population-corpus"),
            hash("stratified-program-population-features"),
            List.of(caseRef(
                "train_stratified_program_case",
                "train_stratified_program_family",
                "train-stratified-program")),
            List.of(caseRef(
                "validation_stratified_program_case",
                "validation_stratified_program_family",
                "validation-stratified-program")),
            List.of(caseRef(
                "final_stratified_program_case",
                "final_stratified_program_family",
                "final-stratified-program")));
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
                new Source("stratified_seed_add_zero", List.of("add_zero")),
                12,
                12);
        EvolutionRewriteProgramPlan multiplyOneSeedPlan =
            EvolutionRewriteProgramPlan.create(
                genome,
                new EvolutionRewriteProgramPlan.Repeat(
                    "stratified_seed_mul_one_repeat",
                    new Source(
                        "stratified_seed_mul_one",
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
                "stratified_program_population_train_suite",
                EvaluatorProfile
                    .EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
                List.of(new TrainCase(
                    "train_stratified_program_case",
                    "train_stratified_program_family",
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
        EvolutionRewriteProgramEvaluationProtocol evaluationProtocol =
            EvolutionRewriteProgramEvaluationProtocol
                .informationParityExactRationalV1();
        EvolutionRewriteProgramStudyPlan study =
            EvolutionRewriteProgramStudyPlan.create(
                manifest.studyId(),
                manifest,
                suite,
                evaluationProtocol,
                catalog,
                seeds,
                Arrays.asList(
                    EvolutionRewriteProgramMutationKind.values()),
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

    private record Fixture(
        EvolutionSplitManifest manifest,
        EvolutionRewriteProgramTrainSuite suite,
        MutationCatalog catalog,
        List<EvolutionRewriteProgramCandidate> seeds,
        EvolutionRewriteProgramStudyPlan study
    ) {
    }
}
