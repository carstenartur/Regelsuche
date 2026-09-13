package de.regelsuche.math.sympy;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SymPyNamedOperationEngineTest {
    @Test void everyNamedOperationHasItsOwnFrozenConfigurationAndNoTargetParameter() {
        var hashes = java.util.Arrays.stream(SymPyNamedOperationEngine.Operation.values())
            .map(SymPyNamedOperationEngine::configurationHash).toList();
        assertEquals(6, hashes.stream().distinct().count());
        assertFalse(SymPyNamedOperationEngine.script().contains("parse_expr"));
        assertFalse(SymPyNamedOperationEngine.script().contains("sympify"));
    }

    @Test void pinnedEmbeddedOperationsExecutePublicControlsWithoutFallback() {
        try (var engine = new SymPyNamedOperationEngine()) {
            var trig = engine.execute(SymPyNamedOperationEngine.Operation.TRIGSIMP, "sin(x)^2 + cos(x)^2", List.of());
            assertEquals("COMPLETED", trig.status(), trig.canonicalJson());
            assertEquals("1", trig.output());
            assertTrue(trig.canonicalJson().contains("sympy-1.14.0"));
            var factor = engine.execute(SymPyNamedOperationEngine.Operation.FACTOR, "x^2 - 1", List.of());
            assertEquals("COMPLETED", factor.status(), factor.canonicalJson());
            assertTrue(factor.output().contains("x - 1"), factor.output());
            assertNotEquals(trig.configurationHash(), factor.configurationHash());
            for (var operation : SymPyNamedOperationEngine.Operation.values()) {
                var control = engine.execute(operation, "x^2 - 1", List.of());
                assertEquals("COMPLETED", control.status(), control.canonicalJson());
                String function = operation.name().toLowerCase(java.util.Locale.ROOT);
                assertTrue(control.canonicalJson().contains("\"function\":\"" + function + "\""), control.canonicalJson());
            }
            assertEquals(trig.canonicalJson(), engine.execute(SymPyNamedOperationEngine.Operation.TRIGSIMP,
                "sin(x)^2 + cos(x)^2", List.of()).canonicalJson());
            assertEquals("UNSUPPORTED", engine.execute(SymPyNamedOperationEngine.Operation.CANCEL, "x / 0", List.of("0 != 0")).status());
            var unknown = engine.execute(SymPyNamedOperationEngine.Operation.CANCEL, "(a*x)/a", List.of());
            assertEquals("UNSUPPORTED", unknown.status());
            assertTrue(unknown.output().isEmpty());
            var guarded = engine.execute(SymPyNamedOperationEngine.Operation.CANCEL, "(a*x)/a", List.of("a != 0"));
            assertEquals("x", guarded.output());
            assertThrows(IllegalArgumentException.class, () -> engine.execute(SymPyNamedOperationEngine.Operation.FU,
                "__import__('os')", List.of()));
        }
    }
}
