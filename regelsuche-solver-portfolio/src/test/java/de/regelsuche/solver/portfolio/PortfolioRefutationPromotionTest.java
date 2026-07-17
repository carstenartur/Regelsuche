package de.regelsuche.solver.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.SourceProvenance;
import de.regelsuche.solver.ir.SolverObligationFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

class PortfolioRefutationPromotionTest {

    @Test
    void validationRefutationBlocksPromotionEvenThoughItIsNotAProofObjective() {
        var obligation = new SolverObligationFactory().equality(
            "refuted-validation",
            "x + 1",
            "x",
            List.of(),
            RequestedEvidence.DECISION,
            new SourceProvenance(
                "portfolio-test",
                "refuted-validation",
                SolverIr.sha256("refuted-validation/v1")));
        PortfolioRequest request = PortfolioRequest.create(
            obligation,
            SolverObjective.VALIDATION,
            PortfolioPolicy.CAPABILITY_FIRST,
            PortfolioBudget.standard(),
            "refutation-promotion/v1");

        PortfolioRun run = new SolverPortfolioExecutor(
            List.of(StandardPortfolioBackends.polynomialNormalForm()))
            .execute(request);

        assertEquals(PortfolioOutcome.REFUTED, run.report().outcome());
        assertTrue(run.report().promotionBlocked());
        assertFalse(run.report().proofAuthorized());
        assertEquals("polynomial-normal-form", run.report().selectedBackendId());
    }
}
