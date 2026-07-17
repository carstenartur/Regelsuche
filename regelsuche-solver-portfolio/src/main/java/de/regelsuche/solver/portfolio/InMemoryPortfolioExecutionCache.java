package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverExecution;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe exact-key cache used by local orchestration and tests. */
public final class InMemoryPortfolioExecutionCache implements PortfolioExecutionCache {
    private final ConcurrentMap<String, SolverExecution> entries =
        new ConcurrentHashMap<>();

    @Override
    public Optional<SolverExecution> find(String cacheKey) {
        return Optional.ofNullable(entries.get(Objects.requireNonNull(cacheKey)));
    }

    @Override
    public void put(String cacheKey, SolverExecution execution) {
        Objects.requireNonNull(cacheKey, "cacheKey");
        Objects.requireNonNull(execution, "execution");
        ResultStatus status = execution.result().status();
        if (status == ResultStatus.TIMEOUT || status == ResultStatus.ERROR) {
            return;
        }
        entries.put(cacheKey, execution);
    }
}
