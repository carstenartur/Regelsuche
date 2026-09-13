package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationCatalog;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.DifficultyTier;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.ExpectedTerminalClass;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.RevealCase;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.PrimitiveWorkBudget;
import de.regelsuche.evolution.EvolutionRewriteProgramValidationEvidence.Measurement;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.math.algorithms.equivalence.RationalFunctionNormalFormEquivalencePortAdapter;
import de.regelsuche.search.SearchHeuristic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real native search on public synthetic cases; no protected FINAL data or study execution. */
class EvolutionRewriteProgramWorkBudgetReviewTest {
    @TempDir Path directory;
    private static final PrimitiveWorkBudget BUDGET = new PrimitiveWorkBudget(4, 128, 80, 4, 5);

    @Test
    void incompleteMeasurementPreservesTheOriginalNativeAtomicCharge() {
        var engine = de.regelsuche.transform.MeasuredTransformationEngines.counting(
            new de.regelsuche.transform.AstRewriteTransformationEngine());
        var budget = BUDGET.toSearchBudget();
        var direct = new de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy().search(
            new de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Problem("c+0", "c",
                new de.regelsuche.search.strategy.SearchExpansionSource.Measured(engine),
                new de.regelsuche.scoring.ExpressionScorer(), new de.regelsuche.canonical.ExpressionCanonicalizer(), budget));
        var observed = new NativeEvolutionRewriteProgramValidationEvaluator().evaluateSide(engine, "c+0", "c", List.of(), budget);
        assertEquals("WORK_BUDGET", direct.status().name());
        assertTrue(direct.metrics().chargedSearchWorkUnits() > budget.mechanicalSearchWorkBudget());
        assertEquals(direct.status().name(), observed.terminalReason());
        assertEquals(direct.metrics().chargedSearchWorkUnits(), observedWork(observed));
        assertEquals(direct.metrics().transformationWork(), observed.transformationWork());
        requireIncompleteOverrun(observed);
    }

    @Test
    void nativeWorkBudgetOverrunCannotAuthorizeValidationSelection() throws Exception {
        var fixture = fixture(false);
        var selection = fixture.selection();
        assertAll(
            () -> assertFalse(selection.hasSelection(), "a native WORK_BUDGET result cannot select a configuration"),
            () -> assertTrue(selection.candidates().stream().noneMatch(candidate -> candidate.eligible())),
            () -> assertThrows(IllegalStateException.class, selection::handoff));
        for (var candidate : selection.candidates()) {
            for (var row : candidate.cases()) {
                requireIncompleteOverrun(row.baseline());
                requireIncompleteOverrun(row.candidate());
            }
            assertFalse(candidate.eligible());
            assertNull(candidate.validationMetrics());
        }
        assertEquals(selection, EvolutionRewriteProgramValidationSelection.fromCanonicalJson(
            selection.toCanonicalJson(), selection.plan()));
    }

    @Test
    void actualFinalBudgetOverrunConsumesAttemptButCannotCountAsCompletedOrQualified() throws Exception {
        var fixture = fixture(true);
        assertTrue(fixture.selection().hasSelection(), "root-already-target VALIDATION fits the same small budget");
        var plan = fixture.finalPlan();
        var store = new FileEvolutionRewriteProgramFinalTestAttemptStore(directory.resolve("final"));
        var result = fixture.execute(store);
        var row = result.cases().getFirst();
        assertAll(
            () -> assertEquals(0, result.summary().completedCases()),
            () -> assertEquals(1, result.summary().technicalFailures()),
            () -> assertFalse(result.qualificationEligible()));
        requireIncompleteOverrun(row.baseline());
        requireIncompleteOverrun(row.candidate());
        assertNull(result.summary().baselineWorkUnits());
        assertNull(result.summary().candidateWorkUnits());
        assertFalse(result.qualificationEligible());
        assertEquals("COMPLETED", result.finalTestStatus(), "the one-shot attempt remains consumed");
        assertTrue(Files.exists(store.reservationPath(plan)));
        assertEquals(result, store.readEvaluation(plan));
        assertThrows(IOException.class, () -> fixture.execute(store));
    }

    @Test
    void modelAndFullyRehashedFinalImportCannotEraseWorkBudgetFailure() throws Exception {
        var fixture = fixture(true);
        var result = fixture.execute(new FileEvolutionRewriteProgramFinalTestAttemptStore(directory.resolve("final")));
        var row = result.cases().getFirst();
        var measured = row.candidate();
        long candidateWork = observedWork(measured);
        long baselineWork = observedWork(row.baseline());
        ObjectNode forged = (ObjectNode) EvolutionValidationArtifactSupport.JSON.readTree(result.toCanonicalJson());
        for (String side : List.of("baseline", "candidate")) {
            ObjectNode measurement = (ObjectNode) forged.at("/cases/0/" + side);
            measurement.put("failure", "");
            measurement.put("totalWorkUnits", side.equals("baseline") ? baselineWork : candidateWork);
        }
        ObjectNode summary = (ObjectNode) forged.get("summary");
        summary.put("completedCases", 1);
        summary.put("technicalFailures", 0);
        summary.put("baselineWorkUnits", baselineWork);
        summary.put("candidateWorkUnits", candidateWork);
        ((ArrayNode) summary.get("blockers")).removeAll();
        forged.remove("contentHash");
        var material = EvolutionValidationArtifactSupport.JSON.convertValue(forged,
            new com.fasterxml.jackson.core.type.TypeReference<TreeMap<String, Object>>() {});
        forged.put("contentHash", EvolutionValidationArtifactSupport.hash(material));
        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () -> new Measurement(measured.terminalReason(), false,
                -1, 0, List.of(), false, measured.correctness(), measured.searchWork(), measured.transformationWork(),
                0, candidateWork, "")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                EvolutionRewriteProgramFinalTestEvaluation.fromCanonicalJson(forged.toString(), fixture.finalPlan())));
    }

    private static long observedWork(Measurement measurement) {
        return Math.addExact(measurement.searchWork().totalWorkUnits(), measurement.transformationWork().totalWorkUnits());
    }

    private static void requireIncompleteOverrun(Measurement measurement) {
        assertEquals("WORK_BUDGET", measurement.terminalReason());
        assertFalse(measurement.reached());
        assertTrue(observedWork(measurement) > BUDGET.maxWorkUnits(), "retain the entire actually charged batch");
        assertFalse(measurement.complete());
        assertEquals("WORK_BUDGET", measurement.failure());
        assertNull(measurement.totalWorkUnits());
    }

    private Fixture fixture(boolean validationAlreadyTarget) throws IOException {
        String studyId = "synthetic_work_budget_review";
        var validation = EvolutionRewriteProgramHeldOutRevealBundle.create(studyId, Split.VALIDATION,
            List.of(caseValue("validation_case", "validation_family", validationAlreadyTarget ? "a" : "a*1", "a")));
        var finalBundle = EvolutionRewriteProgramHeldOutRevealBundle.create(studyId, Split.FINAL_TEST,
            List.of(caseValue("final_case", "final_family", "c+0", "c")));
        var trainCase = caseValue("train_case", "train_family", "x*x", "x^2");
        var manifest = EvolutionSplitManifest.create(studyId, EvolutionGenome.hash("synthetic-corpus"),
            EvolutionGenome.hash("synthetic-features"), List.of(new EvolutionSplitManifest.CaseReference(
                trainCase.caseId(), trainCase.familyId(), trainCase.exactSignatureHash(), trainCase.alphaSignatureHash(),
                trainCase.inputHash(), trainCase.targetHash())), validation.splitReferences(), finalBundle.splitReferences());
        var genome = EvolutionGenome.create(EvolutionGenome.Objective.OPEN_TARGET_OPERATOR, manifest.trainingScope(),
            List.of(EvolutionGenomeTestFixtures.gene("add_zero", "?A+0", "?A"),
                EvolutionGenomeTestFixtures.gene("mul_one", "?A*1", "?A")),
            List.of(new EvolutionGenome.FeatureWeight(EvolutionGenome.FitnessSignal.UNSEEN_TRAIN_CASES_SOLVED, 500),
                new EvolutionGenome.FeatureWeight(EvolutionGenome.FitnessSignal.COUNTEREXAMPLE_RISK, -500)),
            EvolutionGenome.GuardPolicy.strictDefault(), new EvolutionGenome.ResourceBudget(16, 128, 12, 32, 80),
            List.of("core.ast-rewrite"), List.of());
        var seeds = List.of(
            EvolutionRewriteProgramCandidate.create(genome, EvolutionRewriteProgramPlan.create(genome,
                new EvolutionRewriteProgramPlan.Source("source_add_zero", List.of("add_zero")), 12, 12)),
            EvolutionRewriteProgramCandidate.create(genome, EvolutionRewriteProgramPlan.create(genome,
                new EvolutionRewriteProgramPlan.Repeat("repeat_mul_one",
                    new EvolutionRewriteProgramPlan.Source("source_mul_one", List.of("mul_one")), 1, 2), 12, 12)));
        var trainBudget = new PrimitiveWorkBudget(4, 128, 80, 4, 30_000);
        var suite = EvolutionRewriteProgramTrainSuite.create("synthetic_work_budget_train",
            EvolutionRewriteProgramTrainSuite.EvaluatorProfile.EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
            List.of(new EvolutionRewriteProgramTrainSuite.TrainCase(trainCase.caseId(), trainCase.familyId(),
                trainCase.inputExpression(), trainCase.targetExpression(), List.of())),
            new SearchHeuristic(4, 128, 1, 4, 80, 16), trainBudget);
        var catalog = new MutationCatalog(List.of(), List.of(), List.of(), List.of(), List.of("mul_one"));
        var study = EvolutionRewriteProgramStudyPlan.create(studyId, manifest, suite, catalog, seeds,
            List.of(EvolutionRewriteProgramMutationKind.values()), new EvolutionStudyPlan.PopulationPolicy(2, 1, 1, 2, 1, 1, 721L),
            List.of(new EvolutionStudyPlan.FitnessWeight(FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, 700),
                new EvolutionStudyPlan.FitnessWeight(FitnessComponent.CANDIDATE_COMPLEXITY, 300)),
            new EvolutionStudyPlan.StudyBudget(1, 2, 4, 1, 1));
        var train = new RetainedProtocolBoundEvolutionRewriteProgramPopulationRunner().run(study, manifest, suite,
            seeds, catalog, new ProtocolBoundInformationParityRewriteProgramTrainFitnessEvaluator(suite,
                Set.of(FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, FitnessComponent.CANDIDATE_COMPLEXITY),
                new RationalFunctionNormalFormEquivalencePortAdapter()));
        var plan = EvolutionRewriteProgramValidationPlan.create(study, manifest, train, validation.commitment(), List.of(BUDGET));
        var store = new FileEvolutionRewriteProgramValidationAttemptStore(directory.resolve("validation"));
        var selection = new EvolutionRewriteProgramValidationRunner().executeOnce(plan, study, manifest, train, () -> validation, store);
        return new Fixture(study, manifest, train, selection, finalBundle, store);
    }

    private static RevealCase caseValue(String id, String family, String input, String target) {
        return RevealCase.create(id, family, input, target, List.of(), DifficultyTier.CONTROL, ExpectedTerminalClass.CONFIRMED);
    }

    private record Fixture(EvolutionRewriteProgramStudyPlan study, EvolutionSplitManifest manifest,
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun train, EvolutionRewriteProgramValidationSelection selection,
        EvolutionRewriteProgramHeldOutRevealBundle finalBundle, FileEvolutionRewriteProgramValidationAttemptStore validationStore) {
        EvolutionRewriteProgramFinalTestPlan finalPlan() {
            return EvolutionRewriteProgramFinalTestPlan.create(study, manifest, train, selection.handoff(), finalBundle.commitment());
        }
        EvolutionRewriteProgramFinalTestEvaluation execute(FileEvolutionRewriteProgramFinalTestAttemptStore store) throws IOException {
            return new EvolutionRewriteProgramFinalTestRunner().executeOnce(finalPlan(), study, manifest, train,
                validationStore, () -> finalBundle, store);
        }
    }
}
