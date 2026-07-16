package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.PolynomialNormalFormSolverBackend;
import de.regelsuche.solver.ir.RegelsucheSearchBackend;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.BinaryOperator;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.Sort;
import java.util.Arrays;
import java.util.List;

/** Explicit declarations for the initial three-role portfolio; selection remains capability-based. */
public final class StandardPortfolioBackends {
    private StandardPortfolioBackends() {
    }

    public static PortfolioBackend search() {
        RegelsucheSearchBackend backend = new RegelsucheSearchBackend();
        return new DeclaredPortfolioBackend(
            backend,
            BackendCapabilityProfile.create(
                backend.descriptor().backendId(), backend.descriptor().backendVersion(),
                List.of(SolverIr.OBLIGATION_SCHEMA),
                backend.descriptor().supportedTheories(),
                backend.descriptor().supportedRelations(),
                List.of(),
                List.of(Sort.REAL),
                Arrays.asList(BinaryOperator.values()),
                true,
                backend.descriptor().supportedEvidence(),
                List.of(BackendRole.SEARCH_GUIDANCE),
                CostClass.LOW,
                5L,
                true,
                true,
                BackendAvailability.AVAILABLE,
                SolverIr.sha256("regelsuche-search/default-heuristic/v1")));
    }

    public static PortfolioBackend polynomialNormalForm() {
        PolynomialNormalFormSolverBackend backend =
            new PolynomialNormalFormSolverBackend();
        return new DeclaredPortfolioBackend(
            backend,
            BackendCapabilityProfile.create(
                backend.descriptor().backendId(), backend.descriptor().backendVersion(),
                List.of(SolverIr.OBLIGATION_SCHEMA),
                backend.descriptor().supportedTheories(),
                backend.descriptor().supportedRelations(),
                List.of(),
                List.of(Sort.REAL),
                List.of(BinaryOperator.ADD, BinaryOperator.SUBTRACT,
                    BinaryOperator.MULTIPLY, BinaryOperator.POWER),
                false,
                backend.descriptor().supportedEvidence(),
                List.of(BackendRole.ORACLE_VALIDATION,
                    BackendRole.COUNTEREXAMPLE,
                    BackendRole.SYMBOLIC_CONFIRMATION),
                CostClass.MEDIUM,
                20L,
                true,
                true,
                BackendAvailability.AVAILABLE,
                SolverIr.sha256("polynomial-normal-form/exact-rational/v1")));
    }

    public static PortfolioBackend z3(
        Z3SmtSolverBackend backend,
        BackendAvailability availability
    ) {
        return new DeclaredPortfolioBackend(
            backend,
            BackendCapabilityProfile.create(
                backend.descriptor().backendId(), backend.descriptor().backendVersion(),
                List.of(SolverIr.OBLIGATION_SCHEMA),
                backend.descriptor().supportedTheories(),
                backend.descriptor().supportedRelations(),
                Arrays.asList(Relation.values()),
                List.of(Sort.REAL),
                Arrays.asList(BinaryOperator.values()),
                false,
                backend.descriptor().supportedEvidence(),
                List.of(BackendRole.ORACLE_VALIDATION,
                    BackendRole.COUNTEREXAMPLE,
                    BackendRole.FORMAL_PROOF),
                CostClass.HIGH,
                100L,
                true,
                true,
                availability,
                backend.configurationHash()));
    }
}
