package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverBackend;
import de.regelsuche.solver.ir.SolverExecution;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import java.util.Objects;

/** Adapter that attaches an explicit capability profile to an existing backend. */
public record DeclaredPortfolioBackend(
    SolverBackend backend,
    BackendCapabilityProfile profile
) implements PortfolioBackend {
    public DeclaredPortfolioBackend {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(profile, "profile");
        if (!backend.descriptor().backendId().equals(profile.backendId())
                || !backend.descriptor().backendVersion().equals(profile.backendVersion())) {
            throw new IllegalArgumentException(
                "profile must identify the wrapped backend revision");
        }
    }

    @Override
    public SolverExecution execute(Obligation obligation) {
        SolverExecution execution = backend.execute(obligation);
        if (execution == null) {
            throw new IllegalStateException("declared backend returned no execution");
        }
        return execution;
    }
}
