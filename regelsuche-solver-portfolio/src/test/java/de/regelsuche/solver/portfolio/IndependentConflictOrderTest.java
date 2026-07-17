package de.regelsuche.solver.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import de.regelsuche.solver.ir.SolverIr.SourceProvenance;
import de.regelsuche.solver.ir.SolverIr.Theory;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import de.regelsuche.solver.ir.SolverObligationFactory;
import de.regelsuche.solver.ir.SolverTranslation;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndependentConflictOrderTest {

    @Test
    void independentPolicyContinuesAfterCheaperRefutationAndFindsConflict() {
        PortfolioBackend refuting = backend("a-refuting", 1L, ResultStatus.REFUTED);
        PortfolioBackend confirming = backend("b-confirming", 20L, ResultStatus.CONFIRMED);
        Obligation obligation = new SolverObligationFactory().equality(
            "independent-conflict", "x + 0", "x", List.of(),
            RequestedEvidence.FORMAL_PROOF,
            new SourceProvenance(
                "portfolio-test", "independent-conflict",
                SolverIr.sha256("independent-conflict/v1")));
        PortfolioRequest request = PortfolioRequest.create(
            obligation,
            SolverObjective.FORMAL_PROOF,
            PortfolioPolicy.INDEPENDENT_CONFIRMATION,
            PortfolioBudget.standard(),
            "independent-conflict/v1");

        PortfolioRun run = new SolverPortfolioExecutor(
            List.of(confirming, refuting)).execute(request);

        assertEquals(PortfolioOutcome.CONFLICT, run.report().outcome());
        assertEquals(2, run.report().executedInvocations());
        assertEquals(2, run.report().conflictExecutionHashes().size());
        assertTrue(run.report().promotionBlocked());
        assertFalse(run.report().proofAuthorized());
    }

    private static PortfolioBackend backend(
        String backendId,
        long cost,
        ResultStatus status
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
            false,
            Arrays.asList(RequestedEvidence.values()),
            List.of(BackendRole.FORMAL_PROOF),
            CostClass.LOW,
            cost,
            true,
            true,
            BackendAvailability.AVAILABLE,
            SolverIr.sha256("conflict-backend:" + backendId));
        return new PortfolioBackend() {
            @Override
            public BackendCapabilityProfile profile() {
                return profile;
            }

            @Override
            public SolverExecution execute(Obligation obligation) {
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
                    obligation, descriptor, status, TranslationStatus.LOSSLESS,
                    List.of(BackendRole.FORMAL_PROOF.name()), List.of(),
                    status.name(), Map.of(),
                    SolverIr.sha256("certificate:" + backendId + ':' + status));
                return SolverExecution.create(obligation, translation, result);
            }
        };
    }
}
