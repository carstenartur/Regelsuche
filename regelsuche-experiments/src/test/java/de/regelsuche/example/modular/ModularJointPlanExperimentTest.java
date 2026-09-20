package de.regelsuche.example.modular;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModularJointPlanExperimentTest {
    @Test void actuallyRunsEachLengthAndChargesSetupAndTheSameAuditForBothRoutes() {
        var report = new ModularJointPlanExperiment().run(new ModularJointPlanExperiment.Config(List.of(1, 3), 1, 64, 32));
        assertEquals(4, report.rows().size());
        assertTrue(report.searchWork() > 0);
        assertTrue(report.preparationWork() > 0);
        assertTrue(report.outputCost() < report.inputCost());
        for (int length : List.of(1, 3)) {
            var rows = report.rows().stream().filter(row -> row.length() == length).toList();
            assertEquals(2, rows.size());
            assertEquals(rows.getFirst().checksum(), rows.getLast().checksum());
            for (var row : rows) {
                assertEquals(length, row.executedInputs());
                assertEquals(length, row.auditedInputs());
                assertTrue(row.execution().wallNanos() > 0);
                assertTrue(row.audit().wallNanos() > 0);
                assertEquals(row.commonSetup().wallNanos() + row.routeSetup().wallNanos()
                    + row.execution().wallNanos() + row.audit().wallNanos(), row.paidWallNanos());
            }
        }
    }
}
