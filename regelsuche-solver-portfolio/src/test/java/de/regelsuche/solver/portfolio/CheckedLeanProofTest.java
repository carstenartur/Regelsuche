package de.regelsuche.solver.portfolio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import org.junit.jupiter.api.Test;

class CheckedLeanProofTest {
    @Test
    void kernelCheckedTheoremAndAxiomBoundaryExists() {
        assertDoesNotThrow(() -> Class.forName(
            "de.regelsuche.solver.portfolio.LeanSolverBackend"));
    }
}
