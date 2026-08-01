package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.evolution.EvolutionGenome.AssumptionTemplate;
import de.regelsuche.evolution.EvolutionGenome.RewriteGene;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Sequence;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainFitnessEvidence.PathCorrectness;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.EvaluatorProfile;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.PrimitiveWorkBudget;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.TrainCase;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InformationParityRewriteProgramTrainFitnessEvaluatorTest {
    private static final Set<FitnessComponent> COMPONENTS = Set.of(
        FitnessComponent.TRAIN_CASES_NEWLY_SOLVED,
        FitnessComponent.TRAIN_PATH_LENGTH_REDUCTION,
        FitnessComponent.TRAIN_EXPLORED_STATE_REDUCTION,
        FitnessComponent.SUPPORT,
        FitnessComponent.ASSUMPTION_SIMPLICITY,
        FitnessComponent.CANDIDATE_COMPLEXITY,
        FitnessComponent.PROOF_COST_PROXY);

    @Test
    void twoStepProgramCannotBypassOnePrimitiveStepBudget() {
        EvolutionRewriteProgramCandidate candidate = cancellationCandidate();
        EvolutionRewriteProgramTrainSuite suite = suite(
            guardedCancellationCase(),
            new PrimitiveWorkBudget(1, 16, 80, 4, 10_000));

        EvolutionRewriteProgramTrainFitnessEvidence evidence =
            new InformationParityRewriteProgramTrainFitnessEvaluator(
                suite, COMPONENTS).evaluate(candidate);
        var measurement = evidence.cases().getFirst();

        assertFalse(measurement.baselineReached());
        assertFalse(measurement.candidateReached());
        assertFalse(measurement.programUsed());
        assertFalse(measurement.newlySolved());
        assertEquals(0,
            evidence.rawComponents().get(
                FitnessComponent.TRAIN_CASES_NEWLY_SOLVED));
        assertEquals(0,
            evidence.rawComponents().get(
                FitnessComponent.TRAIN_PATH_LENGTH_REDUCTION));
        assertTrue(
            measurement.candidateTransformationWork().programNodeVisits() > 0,
            "the rejected macro's internal formation work is still retained");
    }

    @Test
    void equalPrimitiveBudgetLetsBothSidesReachWithoutHiddenResourceCredit() {
        EvolutionRewriteProgramCandidate candidate = cancellationCandidate();
        EvolutionRewriteProgramTrainSuite suite = suite(
            guardedCancellationCase(),
            new PrimitiveWorkBudget(2, 16, 80, 4, 10_000));

        EvolutionRewriteProgramTrainFitnessEvidence evidence =
            new InformationParityRewriteProgramTrainFitnessEvaluator(
                suite, COMPONENTS).evaluate(candidate);
        var measurement = evidence.cases().getFirst();

        assertTrue(measurement.baselineReached());
        assertTrue(measurement.candidateReached());
        assertTrue(measurement.programUsed());
        assertFalse(measurement.newlySolved());
        assertEquals(PathCorrectness.CONFIRMED,
            measurement.baselinePathCorrectness());
        assertEquals(PathCorrectness.CONFIRMED,
            measurement.candidatePathCorrectness());
        assertEquals(2, measurement.baselinePrimitiveSteps());
        assertEquals(2, measurement.candidatePrimitiveSteps());
        assertEquals(2, measurement.baselinePathLength());
        assertEquals(1, measurement.candidatePathLength(),
            "macro compression remains a representation fact");
        assertTrue(
            measurement.candidateTotalWorkUnits()
                > measurement.baselineTotalWorkUnits(),
            "all additional program formation work must remain visible");
        assertEquals(0,
            evidence.rawComponents().get(
                FitnessComponent.TRAIN_PATH_LENGTH_REDUCTION),
            "a shorter outer path cannot earn credit with greater total work");
        assertEquals(0,
            evidence.rawComponents().get(
                FitnessComponent.TRAIN_EXPLORED_STATE_REDUCTION));
    }

    @Test
    void flatGenomeRuleCanSolveWithoutFabricatingProgramCredit() {
        EvolutionGenome genome = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.addZero("add_zero", "A"));
        EvolutionRewriteProgramPlan plan = EvolutionRewriteProgramPlan.create(
            genome,
            new Source("flat_zero_program", List.of("add_zero")),
            4,
            4);
        EvolutionRewriteProgramCandidate candidate =
            EvolutionRewriteProgramCandidate.create(genome, plan);
        EvolutionRewriteProgramTrainSuite suite = suite(new TrainCase(
            "train_flat_rule_control",
            "flat_rule_control_family",
            "x+0",
            "x",
            List.of()), new PrimitiveWorkBudget(1, 16, 80, 4, 10_000));

        EvolutionRewriteProgramTrainFitnessEvidence evidence =
            new InformationParityRewriteProgramTrainFitnessEvaluator(
                suite, COMPONENTS).evaluate(candidate);
        var measurement = evidence.cases().getFirst();

        assertTrue(measurement.baselineReached());
        assertTrue(measurement.candidateReached());
        assertFalse(measurement.programUsed());
        assertFalse(measurement.newlySolved());
        assertEquals(PathCorrectness.CONFIRMED,
            measurement.baselinePathCorrectness());
        assertEquals(0,
            evidence.rawComponents().get(
                FitnessComponent.TRAIN_CASES_NEWLY_SOLVED));
        assertEquals(0,
            evidence.rawComponents().get(
                FitnessComponent.TRAIN_PATH_LENGTH_REDUCTION));
        assertEquals(0,
            evidence.rawComponents().get(
                FitnessComponent.TRAIN_EXPLORED_STATE_REDUCTION));
    }

    @Test
    void unsafeProgramIsRefutedEvenWhenFlatRuleAndProgramReachTarget() {
        EvolutionGenome genome = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.gene(
                "unsafe_zero", "?A", "0"));
        EvolutionRewriteProgramPlan plan = EvolutionRewriteProgramPlan.create(
            genome,
            new Source("unsafe_program", List.of("unsafe_zero")),
            4,
            4);
        EvolutionRewriteProgramCandidate candidate =
            EvolutionRewriteProgramCandidate.create(genome, plan);
        EvolutionRewriteProgramTrainSuite suite = suite(new TrainCase(
            "train_refuted_control",
            "refuted_control_family",
            "x",
            "0",
            List.of()), new PrimitiveWorkBudget(1, 16, 80, 4, 10_000));

        EvolutionRewriteProgramTrainFitnessEvidence evidence =
            new InformationParityRewriteProgramTrainFitnessEvaluator(
                suite, COMPONENTS).evaluate(candidate);
        var measurement = evidence.cases().getFirst();

        assertTrue(measurement.baselineReached());
        assertTrue(measurement.candidateReached());
        assertEquals(PathCorrectness.REFUTED,
            measurement.baselinePathCorrectness());
        assertEquals(PathCorrectness.REFUTED,
            measurement.candidatePathCorrectness());
        assertFalse(measurement.newlySolved());
        assertTrue(measurement.correctnessFailure());
        assertTrue(evidence.blockers().contains(
            "TRAIN_CORRECTNESS_FAILURE:train_refuted_control:REFUTED"));
    }

    @Test
    void narrowerSuiteBoundaryBlocksBothFlatAndProgramSources() {
        EvolutionRewriteProgramCandidate candidate = cancellationCandidate();
        EvolutionRewriteProgramTrainSuite suite = suite(
            guardedCancellationCase(),
            new PrimitiveWorkBudget(2, 128, 20, 4, 10_000));

        EvolutionRewriteProgramTrainFitnessEvidence evidence =
            new InformationParityRewriteProgramTrainFitnessEvaluator(
                suite, COMPONENTS).evaluate(candidate);

        assertTrue(evidence.blockers().contains(
            "SUITE_CANDIDATE_BOUND_NARROWER_THAN_GENOME_AND_PROGRAM_SOURCES"));
    }

    @Test
    void totalWorkBudgetIsAppliedEquallyAndRetained() {
        EvolutionRewriteProgramCandidate candidate = cancellationCandidate();
        EvolutionRewriteProgramTrainSuite suite = suite(
            guardedCancellationCase(),
            new PrimitiveWorkBudget(2, 16, 80, 4, 3));

        EvolutionRewriteProgramTrainFitnessEvidence evidence =
            new InformationParityRewriteProgramTrainFitnessEvaluator(
                suite, COMPONENTS).evaluate(candidate);
        var measurement = evidence.cases().getFirst();

        assertFalse(measurement.candidateReached());
        assertEquals("WORK_BUDGET", measurement.candidateStatus());
        assertTrue(measurement.candidateTotalWorkUnits() > 3);
        assertTrue(measurement.baselineTotalWorkUnits() > 0);
    }

    private static TrainCase guardedCancellationCase() {
        return new TrainCase(
            "train_guarded_cancellation",
            "guarded_factor_family",
            "(x*y)/(x*1)",
            "y",
            List.of("x != 0"));
    }

    private static EvolutionRewriteProgramCandidate cancellationCandidate() {
        RewriteGene cancel = new RewriteGene(
            "cancel_factor",
            "(?A*?B)/(?A*?C)",
            "?B/?C",
            RewriteKind.SIMPLIFY,
            false,
            -3,
            4,
            4,
            List.of(new AssumptionTemplate(
                Assumption.Kind.NON_ZERO,
                "?A != 0",
                List.of("?A"))),
            EvolutionGenomeTestFixtures.obligations());
        RewriteGene divideOne = new RewriteGene(
            "divide_one",
            "?A/1",
            "?A",
            RewriteKind.SIMPLIFY,
            false,
            -2,
            4,
            2,
            List.of(),
            EvolutionGenomeTestFixtures.obligations());
        EvolutionGenome genome = EvolutionGenomeTestFixtures.genome(
            cancel, divideOne);
        EvolutionRewriteProgramPlan plan = EvolutionRewriteProgramPlan.create(
            genome,
            new Sequence(
                "guarded_cancel_then_cleanup",
                List.of(
                    new Source(
                        "guarded_cancel", List.of("cancel_factor")),
                    new Source(
                        "cleanup_division", List.of("divide_one")))),
            6,
            6);
        return EvolutionRewriteProgramCandidate.create(genome, plan);
    }

    private static EvolutionRewriteProgramTrainSuite suite(
        TrainCase trainCase,
        PrimitiveWorkBudget workBudget
    ) {
        return EvolutionRewriteProgramTrainSuite.create(
            "information_parity_train_suite",
            EvaluatorProfile
                .EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
            List.of(trainCase),
            new SearchHeuristic(
                workBudget.maxPrimitiveSteps(),
                workBudget.maxExploredStates(),
                1,
                workBudget.maxExpandingSteps(),
                workBudget.maxCandidatesPerState(),
                12),
            workBudget);
    }
}
