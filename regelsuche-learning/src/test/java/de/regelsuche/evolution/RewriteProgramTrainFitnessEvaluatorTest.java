package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.evolution.EvolutionGenome.AssumptionTemplate;
import de.regelsuche.evolution.EvolutionGenome.RewriteGene;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Sequence;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainFitnessEvidence.PathCorrectness;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.EvaluatorProfile;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.TrainCase;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RewriteProgramTrainFitnessEvaluatorTest {
    private static final Set<FitnessComponent> COMPONENTS = Set.of(
        FitnessComponent.TRAIN_CASES_NEWLY_SOLVED,
        FitnessComponent.TRAIN_PATH_LENGTH_REDUCTION,
        FitnessComponent.TRAIN_EXPLORED_STATE_REDUCTION,
        FitnessComponent.SUPPORT,
        FitnessComponent.ASSUMPTION_SIMPLICITY,
        FitnessComponent.CANDIDATE_COMPLEXITY,
        FitnessComponent.PROOF_COST_PROXY);

    @Test
    void countsOnlyProgramUsedExactlyConfirmedNewReachability() {
        EvolutionRewriteProgramCandidate candidate = cancellationCandidate();
        EvolutionRewriteProgramTrainSuite suite = suite(
            new TrainCase(
                "train_guarded_cancellation",
                "guarded_factor_family",
                "(x*y)/(x*1)",
                "y",
                List.of("x != 0")));

        EvolutionRewriteProgramTrainFitnessEvidence evidence =
            new RewriteProgramTrainFitnessEvaluator(suite, COMPONENTS)
                .evaluate(candidate);
        var measurement = evidence.cases().getFirst();

        assertFalse(measurement.baselineReached());
        assertTrue(measurement.candidateReached());
        assertTrue(measurement.programUsed());
        assertTrue(measurement.newlySolved());
        assertEquals(PathCorrectness.CONFIRMED,
            measurement.candidatePathCorrectness());
        assertEquals(2, measurement.candidatePrimitiveSteps());
        assertFalse(measurement.correctnessFailure());
        assertTrue(evidence.blockers().isEmpty(), evidence.blockers().toString());
        assertEquals(1000,
            evidence.rawComponents().get(
                FitnessComponent.TRAIN_CASES_NEWLY_SOLVED));
        assertEquals(1000,
            evidence.rawComponents().get(FitnessComponent.SUPPORT));
        assertEquals(candidate.contentHash(), evidence.candidateHash());
        assertEquals(candidate.plan().contentHash(), evidence.planHash());
        assertTrue(evidence.toCanonicalJson().contains(
            "\"candidatePathCorrectness\":\"CONFIRMED\""));
    }

    @Test
    void retainsRefutedProgramPathAsBlockingNegativeEvidence() {
        EvolutionGenome genome = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.gene(
                "unsafe_zero", "?A", "0"));
        EvolutionRewriteProgramPlan plan = EvolutionRewriteProgramPlan.create(
            genome,
            new Source("unsafe_source", List.of("unsafe_zero")),
            4,
            4);
        EvolutionRewriteProgramCandidate candidate =
            EvolutionRewriteProgramCandidate.create(genome, plan);
        EvolutionRewriteProgramTrainSuite suite = suite(
            new TrainCase(
                "train_refuted_rewrite",
                "negative_control_family",
                "x",
                "0",
                List.of()));

        EvolutionRewriteProgramTrainFitnessEvidence evidence =
            new RewriteProgramTrainFitnessEvaluator(suite, COMPONENTS)
                .evaluate(candidate);
        var measurement = evidence.cases().getFirst();

        assertTrue(measurement.candidateReached());
        assertTrue(measurement.programUsed());
        assertEquals(PathCorrectness.REFUTED,
            measurement.candidatePathCorrectness());
        assertFalse(measurement.newlySolved());
        assertTrue(measurement.correctnessFailure());
        assertTrue(evidence.blockers().contains(
            "TRAIN_CORRECTNESS_FAILURE:train_refuted_rewrite:REFUTED"));
        assertEquals(0,
            evidence.rawComponents().get(
                FitnessComponent.TRAIN_CASES_NEWLY_SOLVED));
        assertEquals(0,
            evidence.rawComponents().get(FitnessComponent.SUPPORT));
    }

    @Test
    void failsClosedWhenSuiteCandidateBoundaryIsNarrowerThanProgramSources() {
        EvolutionRewriteProgramCandidate candidate = cancellationCandidate();
        EvolutionRewriteProgramTrainSuite suite =
            EvolutionRewriteProgramTrainSuite.create(
                "narrow_candidate_suite",
                EvaluatorProfile
                    .EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
                List.of(new TrainCase(
                    "train_guarded_cancellation",
                    "guarded_factor_family",
                    "(x*y)/(x*1)",
                    "y",
                    List.of("x != 0"))),
                new SearchHeuristic(1, 128, 1, 4, 20, 12));

        EvolutionRewriteProgramTrainFitnessEvidence evidence =
            new RewriteProgramTrainFitnessEvaluator(suite, COMPONENTS)
                .evaluate(candidate);

        assertTrue(evidence.blockers().contains(
            "SUITE_CANDIDATE_BOUND_NARROWER_THAN_PROGRAM_SOURCE_BOUND"));
    }

    @Test
    void combinedCandidateIdentityIncludesGenomeAndTopology() {
        EvolutionRewriteProgramCandidate first = cancellationCandidate();
        EvolutionGenome genome = first.genome();
        EvolutionRewriteProgramPlan renamedPlan =
            EvolutionRewriteProgramPlan.create(
                genome,
                new Sequence(
                    "renamed_sequence",
                    List.of(
                        new Source(
                            "renamed_cancel", List.of("cancel_factor")),
                        new Source(
                            "renamed_divide", List.of("divide_one")))),
                6,
                6);
        EvolutionRewriteProgramCandidate renamed =
            EvolutionRewriteProgramCandidate.create(genome, renamedPlan);

        assertEquals(
            first.alphaStructuralHash(), renamed.alphaStructuralHash());
        assertNotEquals(first.contentHash(), renamed.contentHash());
        assertTrue(first.toCanonicalJson().contains(
            "regelsuche.evolution-rewrite-program-candidate/v1"));

        EvolutionGenome otherGenome = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.gene("other_gene", "?A-0", "?A"));
        assertThrows(IllegalArgumentException.class,
            () -> EvolutionRewriteProgramCandidate.create(
                otherGenome, first.plan()));
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

    private static EvolutionRewriteProgramTrainSuite suite(TrainCase trainCase) {
        return EvolutionRewriteProgramTrainSuite.create(
            "program_train_suite",
            EvaluatorProfile
                .EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
            List.of(trainCase),
            new SearchHeuristic(1, 128, 1, 4, 80, 12));
    }
}
