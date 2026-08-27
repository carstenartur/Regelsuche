package de.regelsuche.math.sympy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class SymPyProgramHashTest {
    @Test
    void processEvidenceBindsTheExactExecutedWrapperProgram() {
        ProcessSymPyFactorizationEngine<?> engine =
            ProcessSymPyFactorizationEngine.integers("python3");

        assertEquals(
            SymPyEvidence.sha256(SymPyScript.processProgram()),
            SymPyScript.processProgramHash());
        assertEquals(
            SymPyScript.processProgramHash(),
            engine.adapterProgramHash());
        assertNotEquals(
            SymPyScript.sourceHash(),
            engine.adapterProgramHash());
    }
}
