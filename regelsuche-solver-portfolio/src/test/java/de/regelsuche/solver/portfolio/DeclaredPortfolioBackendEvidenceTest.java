package de.regelsuche.solver.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.solver.ir.SolverBackend;
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

class DeclaredPortfolioBackendEvidenceTest {

    @Test
    void proofCapableBackendCannotConfirmWithoutCertificate() {
        Obligation obligation = new SolverObligationFactory().equality(
            "uncertified-formal-proof",
            "x + 0",
            "x",
            List.of(),
            RequestedEvidence.FORMAL_PROOF,
            new SourceProvenance(
                "portfolio-test",
                "uncertified-formal-proof",
                SolverIr.sha256("uncertified-formal-proof/v1")));

        SolverBackend uncertified = new SolverBackend() {
            private final BackendDescriptor descriptor = new BackendDescriptor(
                "uncertified-formal-backend",
                "1",
                List.of(Theory.REAL_ARITHMETIC),
                List.of(Relation.EQUALS),
                List.of(RequestedEvidence.FORMAL_PROOF),
                true);

            @Override
            public BackendDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public SolverExecution execute(Obligation submitted) {
                SolverTranslation translation = SolverTranslation.create(
                    submitted,
                    descriptor,
                    TranslationStatus.LOSSLESS,
                    List.of(),
                    Map.of(
                        "goal.left", submitted.goal().left().canonicalMaterial(),
                        "goal.right", submitted.goal().right().canonicalMaterial()));
                SolverResult result = SolverResult.create(
                    submitted,
                    descriptor,
                    ResultStatus.CONFIRMED,
                    TranslationStatus.LOSSLESS,
                    List.of("UNCERTIFIED_FORMAL_CONFIRMATION"),
                    List.of(),
                    "backend claimed proof without certificate",
                    Map.of(),
                    "");
                return SolverExecution.create(submitted, translation, result);
            }
        };

        BackendCapabilityProfile profile = BackendCapabilityProfile.create(
            uncertified.descriptor().backendId(),
            uncertified.descriptor().backendVersion(),
            List.of(SolverIr.OBLIGATION_SCHEMA),
            List.of(Theory.REAL_ARITHMETIC),
            List.of(Relation.EQUALS),
            List.of(),
            List.of(Sort.REAL),
            Arrays.asList(BinaryOperator.values()),
            false,
            List.of(RequestedEvidence.FORMAL_PROOF),
            List.of(BackendRole.FORMAL_PROOF),
            CostClass.HIGH,
            10L,
            true,
            true,
            BackendAvailability.AVAILABLE,
            SolverIr.sha256("uncertified-formal-backend/v1"));

        PortfolioRequest request = PortfolioRequest.create(
            obligation,
            SolverObjective.FORMAL_PROOF,
            PortfolioPolicy.CAPABILITY_FIRST,
            PortfolioBudget.standard(),
            "uncertified-formal-proof-request/v1");
        PortfolioRun run = new SolverPortfolioExecutor(List.of(
            new DeclaredPortfolioBackend(uncertified, profile)))
            .execute(request);

        assertEquals(PortfolioOutcome.ERROR, run.report().outcome());
        assertFalse(run.report().proofAuthorized());
        assertTrue(run.report().promotionBlocked());
        assertEquals(AttemptDisposition.FAILED,
            run.report().attempts().getFirst().disposition());
        assertTrue(run.report().attempts().getFirst().issues().stream()
            .anyMatch(issue -> issue.contains("certificate")));
        assertTrue(run.executions().isEmpty());
        assertNull(run.selectedExecution());
    }
}
