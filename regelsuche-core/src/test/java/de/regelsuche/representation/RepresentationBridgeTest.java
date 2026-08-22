package de.regelsuche.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.representation.RepresentationBridge.Relation;
import de.regelsuche.representation.RepresentationBridge.Result;
import de.regelsuche.representation.RepresentationBridge.Status;
import de.regelsuche.representation.RepresentationBridge.WorkLedger;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RepresentationBridgeTest {

    @Test
    void representedResultRequiresAndRetainsCompletePayload() {
        WorkLedger work = WorkLedger.of(10, 4);

        Result<String, String> result = Result.represented(
            "matrix",
            "certificate",
            Relation.SOLUTION_SET_EQUIVALENCE,
            work,
            "represented");

        assertTrue(result.represented());
        assertEquals("matrix", result.representation().orElseThrow());
        assertEquals("certificate", result.certificate().orElseThrow());
        assertEquals(6, result.work().remainingWorkUnits());
        assertEquals("represented", result.detailCode());
    }

    @Test
    void nonRepresentedResultHasNoSemanticPayload() {
        Result<String, String> result = Result.withoutRepresentation(
            Status.BUDGET_INCONCLUSIVE,
            WorkLedger.of(3, 3),
            " exhausted ");

        assertFalse(result.represented());
        assertTrue(result.representation().isEmpty());
        assertTrue(result.certificate().isEmpty());
        assertTrue(result.relation().isEmpty());
        assertEquals("exhausted", result.detailCode());
    }

    @Test
    void invalidBudgetsLedgersAndPayloadCombinationsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new Budget(-1));
        assertThrows(IllegalArgumentException.class,
            () -> new WorkLedger(2, 2, 1));
        assertThrows(IllegalArgumentException.class,
            () -> WorkLedger.of(1, 2));
        assertThrows(IllegalArgumentException.class,
            () -> Result.withoutRepresentation(
                Status.REPRESENTED,
                WorkLedger.of(1, 0),
                "invalid"));
        assertThrows(IllegalArgumentException.class, () -> new Result<>(
            Status.NONLINEAR,
            Optional.of("unexpected"),
            Optional.empty(),
            Optional.empty(),
            WorkLedger.of(1, 0),
            "invalid"));
        assertThrows(IllegalArgumentException.class, () -> new Result<>(
            Status.REPRESENTED,
            Optional.of("matrix"),
            Optional.empty(),
            Optional.of(Relation.SOLUTION_SET_EQUIVALENCE),
            WorkLedger.of(1, 0),
            "incomplete"));
        assertThrows(IllegalArgumentException.class, () -> new Result<>(
            Status.NOT_APPLICABLE,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            WorkLedger.of(1, 0),
            " "));
    }
}
