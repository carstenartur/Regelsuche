package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverExecution;
import de.regelsuche.solver.ir.SolverIr.Obligation;

/** Executable backend plus its complete machine-readable portfolio profile. */
public interface PortfolioBackend {
    BackendCapabilityProfile profile();

    SolverExecution execute(Obligation obligation);
}
