package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverBackend;
import de.regelsuche.solver.ir.SolverExecution;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
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
        boolean proofCapable = profile.roles().contains(BackendRole.SYMBOLIC_CONFIRMATION)
            || profile.roles().contains(BackendRole.FORMAL_PROOF);
        boolean confirmed = execution.result().status() == ResultStatus.CONFIRMED;
        if (proofCapable && confirmed
                && !execution.result().certificateHash().matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalStateException(
                "proof-capable backend confirmed without a certificate hash");
        }
        return execution;
    }
}
