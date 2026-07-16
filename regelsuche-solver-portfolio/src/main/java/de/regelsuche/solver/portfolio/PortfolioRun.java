package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverExecution;
import java.util.List;
import java.util.Objects;

/** Runtime bundle retaining every execution while serializing only the canonical report. */
public record PortfolioRun(
    PortfolioReport report,
    List<SolverExecution> executions,
    SolverExecution selectedExecution
) {
    public PortfolioRun {
        Objects.requireNonNull(report, "report");
        executions = executions == null ? List.of() : List.copyOf(executions);
        if (selectedExecution != null
                && !report.selectedExecutionHash().equals(selectedExecution.contentHash())) {
            throw new IllegalArgumentException(
                "selected execution must match portfolio report");
        }
    }
}
