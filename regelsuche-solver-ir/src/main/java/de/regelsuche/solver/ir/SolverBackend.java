package de.regelsuche.solver.ir;

import de.regelsuche.solver.ir.SolverIr.BackendDescriptor;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.SolverResult;

/** Executes one canonical obligation without changing its semantics. */
public interface SolverBackend {
    BackendDescriptor descriptor();

    SolverResult solve(Obligation obligation);
}
