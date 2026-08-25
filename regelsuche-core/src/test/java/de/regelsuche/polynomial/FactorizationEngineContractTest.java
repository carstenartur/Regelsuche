package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FactorizationEngineContractTest {
    @Test
    void workLedgerRetainsCanonicalStageOrder() {
        Map<String, Long> reverseInsertionOrder = new LinkedHashMap<>();
        reverseInsertionOrder.put("verify.product", 3L);
        reverseInsertionOrder.put("engine.enumeration", 5L);
        reverseInsertionOrder.put("engine.constraints", 2L);

        FactorizationEngine.WorkLedger first =
            new FactorizationEngine.WorkLedger(reverseInsertionOrder);
        FactorizationEngine.WorkLedger second =
            new FactorizationEngine.WorkLedger(Map.of(
                "engine.constraints", 2L,
                "verify.product", 3L,
                "engine.enumeration", 5L));

        assertEquals(
            List.of(
                "engine.constraints",
                "engine.enumeration",
                "verify.product"),
            new ArrayList<>(first.stages().keySet()));
        assertEquals(first, second);
        assertEquals(
            first.canonicalMaterial(),
            second.canonicalMaterial());
        assertEquals(10L, first.totalWorkUnits());
        assertThrows(UnsupportedOperationException.class, () ->
            first.stages().put("late-stage", 1L));
    }
}
