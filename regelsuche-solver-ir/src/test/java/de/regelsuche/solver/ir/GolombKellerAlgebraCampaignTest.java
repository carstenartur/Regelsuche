package de.regelsuche.solver.ir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import org.junit.jupiter.api.Test;

class GolombKellerAlgebraCampaignTest {
    @Test
    void typedManuscriptObligationsAreAvailable() {
        assertDoesNotThrow(() -> Class.forName(
            "de.regelsuche.solver.ir.examples.GolombKellerAlgebra"));
    }
}
