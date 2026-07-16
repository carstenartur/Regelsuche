package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.OpenTargetConjectureEvaluator.CounterexampleEvidence;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationReport;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationStatus;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.NegativeHoldoutResult;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.PositiveHoldoutResult;
import de.regelsuche.mining.OpenTargetConjectureMiner.ConvergenceEvidence;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.mining.OpenTargetConjectureMiner.PathEvidence;
import de.regelsuche.mining.OpenTargetConjectureProofGate;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofStatus;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.solver.ir.SolverExecution;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.BackendDescriptor;
import de.regelsuche.solver.ir.SolverIr.BinaryOperator;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.SolverResult;
import de.regelsuche.solver.ir.SolverIr.Sort;
import de.regelsuche.solver.ir.SolverIr.Theory;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import de.regelsuche.solver.ir.SolverTranslation;
import de.regelsuche.solver.portfolio.BackendAvailability;
import de.regelsuche.solver.portfolio.BackendCapabilityProfile;
import de.regelsuche.solver.portfolio.BackendRole;
import de.regelsuche.solver.portfolio.CostClass;
import de.regelsuche.solver.portfolio.PortfolioBackend;
import de.regelsuche.solver.portfolio.PortfolioBudget;
import de.regelsuche.solver.portfolio.PortfolioOutcome;
import de.regelsuche.solver.portfolio.PortfolioPolicy;
import de.regelsuche.solver.portfolio.PortfolioSolverBackend;
import de.regelsuche.solver.portfolio.SolverObjective;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenTargetProofPortfolioIntegrationTest {

    @Test
    void proofGateConsumesExactFormalBackendExecution() {
        List<String> order = new ArrayList<>();
        PortfolioBackend symbolic = backend(
            "symbolic-stage", BackendRole.SYMBOLIC_CONFIRMATION, 5L,
            obligation -> order.add("symbolic-stage"));
        PortfolioBackend formal = backend(
            "formal-stage", BackendRole.FORMAL_PROOF, 20L,
            obligation -> order.add("formal-stage"));
        PortfolioSolverBackend portfolio = new PortfolioSolverBackend(
            List.of(formal, symbolic),
            SolverObjective.FORMAL_PROOF,
            PortfolioPolicy.COUNTEREXAMPLE_FIRST,
            PortfolioBudget.standard(),
            "open-target-formal-proof/v1");

        var report = new OpenTargetConjectureProofGate(portfolio).evaluate(
            conjecture(), acceptedEvaluation());

        assertEquals(List.of("symbolic-stage", "formal-stage"), order);
        assertEquals(ProofStatus.SYMBOLICALLY_VERIFIED, report.proofStatus());
        assertEquals("formal-stage", report.result().backendId());
        assertEquals(report.execution().contentHash(),
            portfolio.lastRun().orElseThrow().report().selectedExecutionHash());
        assertEquals(PortfolioOutcome.CONFIRMED,
            portfolio.lastRun().orElseThrow().report().outcome());
        assertTrue(portfolio.lastRun().orElseThrow().report().proofAuthorized());
        assertTrue(report.result().certificateHash()
            .matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void searchGuidanceCannotSatisfyExistingProofGate() {
        PortfolioBackend search = backend(
            "search-only", BackendRole.SEARCH_GUIDANCE, 1L,
            obligation -> { });
        PortfolioSolverBackend portfolio = new PortfolioSolverBackend(
            List.of(search),
            SolverObjective.FORMAL_PROOF,
            PortfolioPolicy.CAPABILITY_FIRST,
            PortfolioBudget.standard(),
            "open-target-formal-proof/v1");

        var report = new OpenTargetConjectureProofGate(portfolio).evaluate(
            conjecture(), acceptedEvaluation());

        assertEquals(ProofStatus.INCONCLUSIVE, report.proofStatus());
        assertEquals(ResultStatus.UNKNOWN, report.result().status());
        assertEquals("solver-portfolio", report.result().backendId());
        assertFalse(portfolio.lastRun().orElseThrow().report().proofAuthorized());
        assertTrue(portfolio.lastRun().orElseThrow().report().promotionBlocked());
    }

    private static PortfolioBackend backend(
        String backendId,
        BackendRole role,
        long cost,
        SideEffect sideEffect
    ) {
        BackendCapabilityProfile profile = BackendCapabilityProfile.create(
            backendId,
            "1",
            List.of(SolverIr.OBLIGATION_SCHEMA),
            List.of(Theory.REAL_ARITHMETIC),
            List.of(Relation.EQUALS),
            Arrays.asList(Relation.values()),
            List.of(Sort.REAL),
            Arrays.asList(BinaryOperator.values()),
            true,
            Arrays.asList(RequestedEvidence.values()),
            List.of(role),
            CostClass.LOW,
            cost,
            true,
            true,
            BackendAvailability.AVAILABLE,
            SolverIr.sha256("open-target-proof-backend:" + backendId));
        return new PortfolioBackend() {
            @Override
            public BackendCapabilityProfile profile() {
                return profile;
            }

            @Override
            public SolverExecution execute(Obligation obligation) {
                sideEffect.run(obligation);
                BackendDescriptor descriptor = new BackendDescriptor(
                    profile.backendId(), profile.backendVersion(),
                    profile.supportedTheories(), profile.supportedGoalRelations(),
                    profile.supportedEvidence(), true);
                SolverTranslation translation = SolverTranslation.create(
                    obligation, descriptor, TranslationStatus.LOSSLESS, List.of(),
                    Map.of(
                        "goal.left", obligation.goal().left().canonicalMaterial(),
                        "goal.right", obligation.goal().right().canonicalMaterial()));
                SolverResult result = SolverResult.create(
                    obligation, descriptor, ResultStatus.CONFIRMED,
                    TranslationStatus.LOSSLESS,
                    List.of(role.name()), List.of(),
                    "confirmed by " + backendId, Map.of(),
                    SolverIr.sha256("open-target-certificate:" + backendId));
                return SolverExecution.create(obligation, translation, result);
            }
        };
    }

    private static OpenTargetConjecture conjecture() {
        ConvergenceEvidence first = evidence(
            "obs-x", "x + 0", "x", "alpha-x");
        ConvergenceEvidence second = evidence(
            "obs-y", "y + 0", "y", "alpha-y");
        return new OpenTargetConjecture(
            "open-target-add-zero",
            "A + 0",
            "A",
            2,
            2,
            List.of("algebra"),
            List.of(first.observationId(), second.observationId()),
            List.of(first, second),
            List.of(),
            Map.of("A", List.of("x", "y")),
            "OBSERVED_CONJECTURE",
            "EQUIVALENCE_PRESERVING_CONVERGENT_PATHS");
    }

    private static ConvergenceEvidence evidence(
        String observationId,
        String input,
        String output,
        String alpha
    ) {
        PathEvidence direct = new PathEvidence(
            observationId + "-direct",
            List.of(input, output),
            List.of("add-zero"),
            List.of(),
            1,
            4);
        PathEvidence alternate = new PathEvidence(
            observationId + "-alternate",
            List.of(input, "normalize(" + input + ")", output),
            List.of("normalize", "add-zero"),
            List.of(),
            2,
            6);
        return new ConvergenceEvidence(
            observationId,
            "algebra",
            GoalStatus.UNTARGETED,
            input,
            output,
            SolverIr.sha256("canonical:" + output),
            10,
            alpha,
            "value-" + observationId,
            "direct||normalize>add-zero",
            List.of(direct, alternate));
    }

    private static EvaluationReport acceptedEvaluation() {
        return new EvaluationReport(
            de.regelsuche.mining.OpenTargetConjectureEvaluator.SCHEMA,
            "open-target-add-zero",
            EvaluationStatus.ACCEPTED_FOR_PROOF,
            "COMPILED",
            "dynamic-add-zero",
            SolverIr.sha256("dynamic-add-zero/v1"),
            1,
            1,
            0,
            1,
            1,
            0,
            List.of(new PositiveHoldoutResult(
                "positive", 1, true, List.of("z"))),
            List.of(new NegativeHoldoutResult(
                "negative", 0, true, List.of())),
            new CounterexampleEvidence(
                "NO_COUNTEREXAMPLE_FOUND",
                List.of("numeric-boundary-values"),
                List.of(),
                List.of(),
                "",
                "",
                "no counterexample within deterministic budget"),
            List.of(),
            "NOT_EVALUATED",
            "NOT_EVALUATED");
    }

    @FunctionalInterface
    private interface SideEffect {
        void run(Obligation obligation);
    }
}
