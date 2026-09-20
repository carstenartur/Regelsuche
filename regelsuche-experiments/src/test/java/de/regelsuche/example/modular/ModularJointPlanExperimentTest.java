package de.regelsuche.example.modular;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.json.JsonReader;
import java.util.List;
import java.util.Map;
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
        var document = new JsonReader(report.toJson()).readObject();
        assertEquals(false, document.get("learnedGainClaim"));
        assertEquals(report.source(), document.get("sourceAst"));
        assertEquals(report.selected(), document.get("selectedAst"));
        assertEquals(report.witnessRules(), document.get("witnessRules"));
        var serializedRows = (List<?>) document.get("rows");
        assertEquals(report.rows().size(), serializedRows.size());
        for (int i = 0; i < serializedRows.size(); i++) {
            var serialized = (Map<?, ?>) serializedRows.get(i);
            var observed = report.rows().get(i);
            assertEquals(observed.checksum(), serialized.get("checksum"));
            assertEquals(observed.executedInputs(), ((Number) serialized.get("executedInputs")).intValue());
            assertEquals(observed.auditedInputs(), ((Number) serialized.get("auditedInputs")).intValue());
            var paid = (Map<?, ?>) serialized.get("fullyPaid");
            assertEquals(observed.paidWallNanos(), ((Number) paid.get("wallNanos")).longValue());
        }
    }

    @Test void exportedAccountingPaysEveryPhaseAndPreservesUnavailableCounters() {
        var common = new ModularJointPlanExperiment.Measurement(11, -1, 101, 1_000, 900);
        var setup = new ModularJointPlanExperiment.Measurement(17, 7, -1, 900, 950);
        var execution = new ModularJointPlanExperiment.Measurement(23, 11, 103, 950, 850);
        var audit = new ModularJointPlanExperiment.Measurement(29, 13, 107, 850, 800);
        var row = new ModularJointPlanExperiment.Row(2, 0, "PREPARED", 2, 2, "checksum",
            common, setup, execution, audit);
        var report = new ModularJointPlanExperiment.Report(
            new ModularJointPlanExperiment.Config(List.of(2), 1, 64, 32), List.of(row),
            101, 23, 2_014, 1_028, "BUDGET_EXHAUSTED", 4, List.of("rule\"quoted\nnext"), "source", "selected");
        var document = new JsonReader(report.toJson()).readObject();
        assertEquals(report.witnessRules(), document.get("witnessRules"));
        var serialized = (Map<?, ?>) ((List<?>) document.get("rows")).getFirst();
        var paid = (Map<?, ?>) serialized.get("fullyPaid");
        assertEquals(80, ((Number) paid.get("wallNanos")).longValue());
        assertEquals(-1, ((Number) paid.get("processCpuNanos")).longValue());
        assertEquals(-1, ((Number) paid.get("currentThreadAllocatedBytes")).longValue());
        assertEquals(1_000, ((Number) paid.get("heapBeforeBytes")).longValue());
        assertEquals(800, ((Number) paid.get("heapAfterBytes")).longValue());
    }
}
