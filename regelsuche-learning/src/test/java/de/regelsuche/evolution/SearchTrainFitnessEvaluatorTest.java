package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.EvolutionGenome.FeatureWeight;
import de.regelsuche.evolution.EvolutionGenome.FitnessSignal;
import de.regelsuche.evolution.EvolutionGenome.GuardPolicy;
import de.regelsuche.evolution.EvolutionGenome.Objective;
import de.regelsuche.evolution.EvolutionGenome.ResourceBudget;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.search.SearchHeuristic;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SearchTrainFitnessEvaluatorTest {
    @Test
    void pairedSearchEvidenceIsCanonicalAndMeasuresCandidateOnlyReachability() {
        EvolutionSplitManifest manifest = manifest();
        EvolutionGenome genome = genome(
            manifest,
            EvolutionGenomeTestFixtures.gene(
                "expand_cube", "?A^3", "(?A*?A)*?A"));
        EvolutionTrainSearchSuite suite = suite();
        var evaluator = new SearchTrainFitnessEvaluator(
            suite,
            Set.of(
                FitnessComponent.TRAIN_CASES_NEWLY_SOLVED,
                FitnessComponent.TRAIN_PATH_LENGTH_REDUCTION,
                FitnessComponent.TRAIN_EXPLORED_STATE_REDUCTION,
                FitnessComponent.SUPPORT,
                FitnessComponent.CANDIDATE_COMPLEXITY));

        EvolutionTrainFitnessEvidence first =
            evaluator.evaluateWithEvidence(genome);
        EvolutionTrainFitnessEvidence second =
            evaluator.evaluateWithEvidence(genome);

        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertEquals(suite.contentHash(), first.suiteHash());
        assertEquals(genome.contentHash(), first.genomeHash());
        assertTrue(first.blockers().isEmpty());
        assertEquals(500, first.rawComponents().get(
            FitnessComponent.TRAIN_CASES_NEWLY_SOLVED));
        assertEquals(1000, first.rawComponents().get(FitnessComponent.SUPPORT));
        assertTrue(first.rawComponents().get(
            FitnessComponent.CANDIDATE_COMPLEXITY) > 0);

        EvolutionTrainFitnessEvidence.CaseMeasurement cube = first.cases().stream()
            .filter(item -> item.caseId().equals("cube_expansion"))
            .findFirst()
            .orElseThrow();
        assertFalse(cube.baselineReached());
        assertTrue(cube.candidateReached());
        assertTrue(cube.newlySolved());
        assertFalse(cube.correctnessRegression());

        EvolutionTrainFitnessEvidence.CaseMeasurement ordinary =
            first.cases().stream()
                .filter(item -> item.caseId().equals("add_zero"))
                .findFirst()
                .orElseThrow();
        assertTrue(ordinary.baselineReached());
        assertTrue(ordinary.candidateReached());
        assertFalse(ordinary.newlySolved());

        String json = first.toCanonicalJson();
        assertTrue(json.contains(
            "\"schema\":\"regelsuche.evolution-train-fitness/v1\""));
        assertTrue(json.contains("\"validationStatus\":\"NOT_EVALUATED\""));
        assertTrue(json.contains("\"finalTestStatus\":\"NOT_EVALUATED\""));
        assertFalse(json.contains("validationCases"));
        assertFalse(json.contains("finalTestCases"));
    }

    @Test
    void effectiveCandidateBudgetConstrainsBothTransformationEngines() {
        EvolutionGenome genome = genome(
            manifest(),
            EvolutionGenomeTestFixtures.gene(
                "expand_cube", "?A^3", "(?A*?A)*?A"));
        EvolutionTrainSearchSuite boundedSuite = EvolutionTrainSearchSuite.create(
            "bounded_train_suite_v1",
            List.of(new EvolutionTrainSearchSuite.TrainCase(
                "bounded_candidates", "budget", "(x+0)*1", "x")),
            new SearchHeuristic(3, 64, 1, 3, 1, 12));
        List<Integer> emittedCandidates = new ArrayList<>();
        var evaluator = new SearchTrainFitnessEvaluator(
            boundedSuite,
            Set.of(FitnessComponent.SUPPORT),
            new EvolutionGenomeCompiler(),
            (engine, input, target, heuristic) -> {
                emittedCandidates.add(engine.transform(input).size());
                return SearchTrainFitnessEvaluator.search(
                    engine, input, target, heuristic);
            });

        EvolutionTrainFitnessEvidence evidence =
            evaluator.evaluateWithEvidence(genome);

        assertTrue(evidence.blockers().isEmpty());
        assertEquals(2, emittedCandidates.size());
        assertTrue(emittedCandidates.stream().allMatch(count -> count <= 1));
    }

    @Test
    void failedCaseEvaluationRemainsInCompleteSuiteEvidence() {
        EvolutionGenome genome = genome(
            manifest(),
            EvolutionGenomeTestFixtures.gene(
                "expand_cube", "?A^3", "(?A*?A)*?A"));
        AtomicInteger invocations = new AtomicInteger();
        var evaluator = new SearchTrainFitnessEvaluator(
            suite(),
            Set.of(FitnessComponent.SUPPORT),
            new EvolutionGenomeCompiler(),
            (engine, input, target, heuristic) -> {
                invocations.incrementAndGet();
                throw new IllegalStateException("deterministic failure");
            });

        EvolutionTrainFitnessEvidence evidence =
            evaluator.evaluateWithEvidence(genome);

        assertEquals(suite().cases().size(), evidence.cases().size());
        assertEquals(suite().cases().size(), invocations.get());
        assertTrue(evidence.cases().stream().allMatch(item ->
            item.baselineStatus().equals("EVALUATION_FAILED")
                && item.candidateStatus().equals("NOT_RUN")
                && !item.baselineReached()
                && !item.candidateReached()));
        assertEquals(0, evidence.rawComponents().get(FitnessComponent.SUPPORT));
        assertEquals(suite().cases().size(), evidence.blockers().stream()
            .filter(item -> item.startsWith(
                "TRAIN_CASE_EVALUATION_FAILED:"))
            .count());
    }

    @Test
    void unsupportedLaterStageComponentsFailClosedWithoutInventedScores() {
        EvolutionGenome genome = genome(
            manifest(),
            EvolutionGenomeTestFixtures.gene(
                "expand_cube", "?A^3", "(?A*?A)*?A"));
        var evaluator = new SearchTrainFitnessEvaluator(
            suite(), Set.of(FitnessComponent.PROJECT_NOVELTY));

        EvolutionTrainFitnessEvidence evidence =
            evaluator.evaluateWithEvidence(genome);

        assertEquals(0, evidence.rawComponents().get(
            FitnessComponent.PROJECT_NOVELTY));
        assertTrue(evidence.blockers().contains(
            "UNSUPPORTED_TRAIN_FITNESS_COMPONENT:PROJECT_NOVELTY"));
        assertTrue(evaluator.evaluate(genome).blockers().contains(
            "UNSUPPORTED_TRAIN_FITNESS_COMPONENT:PROJECT_NOVELTY"));
    }

    @Test
    void suiteIdentityBindsCasesAndSearchPolicy() {
        EvolutionTrainSearchSuite first = suite();
        EvolutionTrainSearchSuite second = suite();
        EvolutionTrainSearchSuite changed = EvolutionTrainSearchSuite.create(
            "search_train_suite_v1",
            first.cases(),
            new SearchHeuristic(4, 64, 1, 3, 40, 12));

        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertFalse(first.contentHash().equals(changed.contentHash()));
    }

    private static EvolutionTrainSearchSuite suite() {
        return EvolutionTrainSearchSuite.create(
            "search_train_suite_v1",
            List.of(
                new EvolutionTrainSearchSuite.TrainCase(
                    "cube_expansion", "powers", "x^3", "(x*x)*x"),
                new EvolutionTrainSearchSuite.TrainCase(
                    "add_zero", "identities", "y+0", "y")),
            new SearchHeuristic(3, 64, 1, 3, 40, 12));
    }

    private static EvolutionSplitManifest manifest() {
        return EvolutionSplitManifest.create(
            "search_train_fitness_v1",
            hash("corpus"),
            hash("features"),
            List.of(caseRef("train_case", "train_family", "train")),
            List.of(caseRef(
                "validation_case", "validation_family", "validation")),
            List.of(caseRef("test_case", "test_family", "test")));
    }

    private static EvolutionGenome genome(
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
            new ResourceBudget(16, 128, 12, 8, 80),
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
}
