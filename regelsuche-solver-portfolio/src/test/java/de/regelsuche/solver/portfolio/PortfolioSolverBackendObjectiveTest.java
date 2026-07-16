package de.regelsuche.solver.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class PortfolioSolverBackendObjectiveTest {

    @Test
    void formalObjectiveContinuesPastSymbolicResultForExistingProofConsumer() {
        TestBackend symbolic = backend(
            "symbolic-stage", BackendRole.SYMBOLIC_CONFIRMATION, 5L);
        TestBackend formal = backend(
            "formal-stage", BackendRole.FORMAL_PROOF, 20L);
        PortfolioSolverBackend portfolio = new PortfolioSolverBackend(
            List.of(symbolic, formal),
            SolverObjective.FORMAL_PROOF,
            PortfolioPolicy.COUNTEREXAMPLE_FIRST,
            PortfolioBudget.standard(),
            "existing-proof-consumer/v1");
        Obligation existingObligation = new SolverObligationFactory().equality(
            "existing-proof-obligation",
            "x + 0",
            "x",
            List.of(),
            RequestedEvidence.SYMBOLIC_CERTIFICATE,
            new SourceProvenance(
                "open-target-conjecture",
                "existing-candidate",
                SolverIr.sha256("existing-candidate/v1")));

        SolverExecution selected = portfolio.execute(existingObligation);

        assertEquals("formal-stage", selected.result().backendId());
        assertEquals(existingObligation.contentHash(), selected.obligationHash());
        assertTrue(portfolio.lastRun().orElseThrow().report().proofAuthorized());
        assertEquals(2,
            portfolio.lastRun().orElseThrow().report().executedInvocations());
    }

    private static TestBackend backend(
        String backendId,
        BackendRole role,
        long cost
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
            SolverIr.sha256("objective-test:" + backendId));
        return new TestBackend(profile);
    }

    private record TestBackend(
        BackendCapabilityProfile profile
    ) implements PortfolioBackend {
        @Override
        public SolverExecution execute(Obligation obligation) {
            BackendDescriptor descriptor = new BackendDescriptor(
                profile.backendId(),
                profile.backendVersion(),
                profile.supportedTheories(),
                profile.supportedGoalRelations(),
                profile.supportedEvidence(),
                true);
            SolverTranslation translation = SolverTranslation.create(
                obligation,
                descriptor,
                TranslationStatus.LOSSLESS,
                List.of(),
                Map.of(
                    "goal.left", obligation.goal().left().canonicalMaterial(),
                    "goal.right", obligation.goal().right().canonicalMaterial()));
            SolverResult result = SolverResult.create(
                obligation,
                descriptor,
                ResultStatus.CONFIRMED,
                TranslationStatus.LOSSLESS,
                profile.roles().stream().map(Enum::name).toList(),
                List.of(),
                "confirmed by " + profile.backendId(),
                Map.of(),
                SolverIr.sha256("certificate:" + profile.backendId()));
            return SolverExecution.create(obligation, translation, result);
        }
    }
}
