package de.regelsuche.evolution;

import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationCatalog;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.DifficultyTier;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.ExpectedTerminalClass;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.RevealCase;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.math.algorithms.equivalence.RationalFunctionNormalFormEquivalencePortAdapter;
import de.regelsuche.search.SearchHeuristic;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Temporary public synthetic studies only; no resource or private study loader is used. */
final class ProgramFinalTestFixtures {
    static final String REVISION = "1".repeat(40);
    private ProgramFinalTestFixtures() { }

    static Fixture create(Path root) throws IOException {
        return create(root, "synthetic_program_final_bridge", false);
    }

    static Fixture create(Path root, String studyId, boolean refutedGene) throws IOException {
        return create(root, studyId, refutedGene, 4);
    }

    static Fixture create(Path root, String studyId, boolean refutedGene, int selectedPrimitiveSteps) throws IOException {
        return create(root, studyId, refutedGene, selectedPrimitiveSteps, List.of(1, 2));
    }

    static Fixture create(Path root, String studyId, boolean refutedGene, int selectedPrimitiveSteps,
        List<Integer> sourceRepeatLimits) throws IOException {
        if (!studyId.startsWith("synthetic_")) { throw new IllegalArgumentException("synthetic studies only"); }
        var validation = EvolutionRewriteProgramHeldOutRevealBundle.create(studyId, Split.VALIDATION,
            List.of(caseValue("validation_one", "validation_family_one", "a*1", "a"),
                caseValue("validation_two", "validation_family_two", "(b*1)*1", "b")));
        var finalBundle = EvolutionRewriteProgramHeldOutRevealBundle.create(studyId, Split.FINAL_TEST,
            List.of(caseValue("final_one", "final_family_one", "c+0", "c"),
                caseValue("final_two", "final_family_two", "(d+0)+0", "d")));
        var trainCase = caseValue("train_one", "train_family", "x*x", "x^2");
        var manifest = EvolutionSplitManifest.create(studyId, hash("synthetic-corpus"), hash("synthetic-features"),
            List.of(new EvolutionSplitManifest.CaseReference(trainCase.caseId(), trainCase.familyId(),
                trainCase.exactSignatureHash(), trainCase.alphaSignatureHash(), trainCase.inputHash(),
                trainCase.targetHash())), validation.splitReferences(), finalBundle.splitReferences());
        var genome = EvolutionGenome.create(EvolutionGenome.Objective.OPEN_TARGET_OPERATOR, manifest.trainingScope(),
            List.of(EvolutionGenomeTestFixtures.gene("add_zero", "?A+0", refutedGene ? "?A+1" : "?A"),
                EvolutionGenomeTestFixtures.gene("mul_one", "?A*1", "?A")),
            List.of(new EvolutionGenome.FeatureWeight(EvolutionGenome.FitnessSignal.UNSEEN_TRAIN_CASES_SOLVED, 500),
                new EvolutionGenome.FeatureWeight(EvolutionGenome.FitnessSignal.COUNTEREXAMPLE_RISK, -500)),
            EvolutionGenome.GuardPolicy.strictDefault(), new EvolutionGenome.ResourceBudget(16, 128, 12, 32, 80),
            List.of("core.ast-rewrite"), List.of());
        var seeds = sourceRepeatLimits.stream().map(limit -> {
            EvolutionRewriteProgramPlan.Node source = limit == 1
                ? new EvolutionRewriteProgramPlan.Source("synthetic_source", List.of("add_zero"))
                : new EvolutionRewriteProgramPlan.Repeat(limit == 2 ? "synthetic_repeat" : "synthetic_repeat_" + limit,
                    new EvolutionRewriteProgramPlan.Source(limit == 2 ? "synthetic_repeated_source" : "synthetic_repeated_source_" + limit,
                        List.of("add_zero")), 1, limit);
            return EvolutionRewriteProgramCandidate.create(genome, EvolutionRewriteProgramPlan.create(genome, source, 12, 12));
        }).toList();
        var work = new EvolutionRewriteProgramTrainSuite.PrimitiveWorkBudget(4, 128, 80, 4, 30_000);
        var suite = EvolutionRewriteProgramTrainSuite.create("synthetic_final_bridge_train",
            EvolutionRewriteProgramTrainSuite.EvaluatorProfile.EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
            List.of(new EvolutionRewriteProgramTrainSuite.TrainCase(trainCase.caseId(), trainCase.familyId(),
                trainCase.inputExpression(), trainCase.targetExpression(), trainCase.assumptions())),
            new SearchHeuristic(4, 128, 1, 4, 80, 16), work);
        var catalog = new MutationCatalog(List.of(), List.of(), List.of(), List.of(), List.of("mul_one"));
        var study = EvolutionRewriteProgramStudyPlan.create(studyId, manifest, suite, catalog, seeds,
            List.of(EvolutionRewriteProgramMutationKind.values()),
            new EvolutionStudyPlan.PopulationPolicy(2, 1, 1, 2, 1, 1, 721L),
            List.of(new EvolutionStudyPlan.FitnessWeight(FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, 700),
                new EvolutionStudyPlan.FitnessWeight(FitnessComponent.CANDIDATE_COMPLEXITY, 300)),
            new EvolutionStudyPlan.StudyBudget(1, 2, 4, 1, 1));
        var train = new RetainedProtocolBoundEvolutionRewriteProgramPopulationRunner().run(study, manifest, suite,
            seeds, catalog, new ProtocolBoundInformationParityRewriteProgramTrainFitnessEvaluator(suite,
                Set.of(FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, FitnessComponent.CANDIDATE_COMPLEXITY),
                new RationalFunctionNormalFormEquivalencePortAdapter()));
        var validationPlan = EvolutionRewriteProgramValidationPlan.create(study, manifest, train,
            validation.commitment(), List.of(new EvolutionRewriteProgramTrainSuite.PrimitiveWorkBudget(
                selectedPrimitiveSteps, work.maxExploredStates(), work.maxCandidatesPerState(),
                Math.min(selectedPrimitiveSteps, work.maxExpandingSteps()), work.maxWorkUnits())));
        var validationStore = new FileEvolutionRewriteProgramValidationAttemptStore(root.resolve("validation"));
        var selection = new EvolutionRewriteProgramValidationRunner().executeOnce(
            validationPlan, study, manifest, train, () -> validation, validationStore);
        return new Fixture(study, manifest, train, selection.handoff(), validation, finalBundle, validationStore,
            root.resolve("final"));
    }

    private static RevealCase caseValue(String id, String family, String input, String target) {
        return RevealCase.create(id, family, input, target, List.of(), DifficultyTier.CONTROL,
            ExpectedTerminalClass.CONFIRMED);
    }

    private static String hash(String value) { return EvolutionGenome.hash(value); }

    record Fixture(EvolutionRewriteProgramStudyPlan study, EvolutionSplitManifest manifest,
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun train,
        EvolutionRewriteProgramValidationHandoff handoff, EvolutionRewriteProgramHeldOutRevealBundle validationBundle,
        EvolutionRewriteProgramHeldOutRevealBundle finalBundle,
        FileEvolutionRewriteProgramValidationAttemptStore validationStore, Path finalDirectory) {
        EvolutionRewriteProgramFinalTestPlan plan() {
            return EvolutionRewriteProgramFinalTestPlan.create(study, manifest, train, handoff, finalBundle.commitment());
        }

        EvolutionRewriteProgramFinalTestEvaluation execute() throws IOException {
            return new EvolutionRewriteProgramFinalTestRunner().executeOnce(plan(), study, manifest, train,
                validationStore, () -> finalBundle, new FileEvolutionRewriteProgramFinalTestAttemptStore(finalDirectory));
        }
    }
}
