package de.regelsuche.evolution;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.equivalence.AssumptionAwareEquivalenceService;
import de.regelsuche.evolution.EvolutionGenome.AssumptionTemplate;
import de.regelsuche.evolution.EvolutionGenome.RewriteGene;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Sequence;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.EvaluatorProfile;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.PrimitiveWorkBudget;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.TrainCase;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.transform.RewriteKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Writes deterministic runtime evidence consumed by the independent verifier. */
public final class RewriteProgramWorkEvidenceFixtureWriter {
    private static final Set<FitnessComponent> COMPONENTS = Set.of(
        FitnessComponent.TRAIN_CASES_NEWLY_SOLVED,
        FitnessComponent.TRAIN_PATH_LENGTH_REDUCTION,
        FitnessComponent.TRAIN_EXPLORED_STATE_REDUCTION,
        FitnessComponent.SUPPORT,
        FitnessComponent.ASSUMPTION_SIMPLICITY,
        FitnessComponent.CANDIDATE_COMPLEXITY,
        FitnessComponent.PROOF_COST_PROXY);

    private RewriteProgramWorkEvidenceFixtureWriter() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                "expected one output-directory argument");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(output);

        EvolutionRewriteProgramCandidate candidate = candidate();
        EvolutionRewriteProgramTrainSuite suite = suite();
        EvolutionRewriteProgramEvaluationProtocol protocol =
            EvolutionRewriteProgramEvaluationProtocol
                .informationParityExactRationalV1();
        EvolutionRewriteProgramTrainFitnessEvidence evidence =
            new InformationParityRewriteProgramTrainFitnessEvaluator(
                suite,
                COMPONENTS,
                confirmedEquivalence())
                .evaluate(candidate);

        Files.writeString(output.resolve("suite.json"),
            suite.toCanonicalJson() + System.lineSeparator());
        Files.writeString(output.resolve("protocol.json"),
            protocol.toCanonicalJson() + System.lineSeparator());
        Files.writeString(output.resolve("candidate.json"),
            candidate.toCanonicalJson() + System.lineSeparator());
        Files.writeString(output.resolve("evidence.json"),
            evidence.toCanonicalJson() + System.lineSeparator());
    }

    private static EvolutionRewriteProgramCandidate candidate() {
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
                    new Source("guarded_cancel", List.of("cancel_factor")),
                    new Source("cleanup_division", List.of("divide_one")))),
            6,
            6);
        return EvolutionRewriteProgramCandidate.create(genome, plan);
    }

    private static EvolutionRewriteProgramTrainSuite suite() {
        PrimitiveWorkBudget budget = new PrimitiveWorkBudget(
            2,
            16,
            80,
            4,
            10_000);
        return EvolutionRewriteProgramTrainSuite.create(
            "independent_work_verifier_suite",
            EvaluatorProfile
                .EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
            List.of(new TrainCase(
                "train_guarded_cancellation",
                "guarded_factor_family",
                "(x*y)/(x*1)",
                "y",
                List.of("x != 0"))),
            new SearchHeuristic(
                budget.maxPrimitiveSteps(),
                budget.maxExploredStates(),
                1,
                budget.maxExpandingSteps(),
                budget.maxCandidatesPerState(),
                12),
            budget);
    }

    private static AssumptionAwareEquivalenceService confirmedEquivalence() {
        return (left, right, assumptions) ->
            AssumptionAwareEquivalenceService.Evaluation.confirmed();
    }
}
