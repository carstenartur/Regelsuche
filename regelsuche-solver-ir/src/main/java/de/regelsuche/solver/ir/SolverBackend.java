package de.regelsuche.solver.ir;

import de.regelsuche.solver.ir.SolverIr.BackendDescriptor;
import de.regelsuche.solver.ir.SolverIr.Obligation;

/** Translates and executes one canonical obligation as one hash-linked unit. */
public interface SolverBackend {
    BackendDescriptor descriptor();

    SolverExecution execute(Obligation obligation);
}
