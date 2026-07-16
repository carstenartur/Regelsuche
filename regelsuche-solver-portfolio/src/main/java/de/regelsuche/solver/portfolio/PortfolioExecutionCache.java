package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverExecution;
import java.util.Optional;

/** Cache keyed by exact obligation, backend semantic configuration and attempt limits. */
public interface PortfolioExecutionCache {
    Optional<SolverExecution> find(String cacheKey);

    void put(String cacheKey, SolverExecution execution);
}
