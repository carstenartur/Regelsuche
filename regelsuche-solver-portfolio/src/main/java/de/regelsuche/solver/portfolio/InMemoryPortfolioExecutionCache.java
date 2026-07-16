package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverExecution;
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
        entries.put(
            Objects.requireNonNull(cacheKey),
            Objects.requireNonNull(execution));
    }
}
