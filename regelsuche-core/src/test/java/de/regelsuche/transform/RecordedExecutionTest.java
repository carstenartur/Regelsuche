package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class RecordedExecutionTest {
    @Test void preservesRealPrimitiveMacroLineageWithoutGrantingExecutionAuthority() {
        var macro = new Transformation("macro", "x", RewriteKind.NORMALIZE, false, -2, true,
            "macro-at-root", List.of("a != 0"), "test", "PROJECT", List.of("r1", "r2"));
        var recorded = RecordedExecution.capture("x + 0 + 0", List.of(macro));
        var loaded = RecordedExecution.fromCanonicalJson(recorded.toCanonicalJson());
        assertEquals(recorded, loaded);
        assertEquals(recorded.contentHash(), loaded.contentHash());
        assertEquals(new ExecutionWork(2, 0, 0), loaded.work());
        assertEquals(List.of("a != 0"), loaded.assumptions());
        loaded.requireReplay("x + 0 + 0", List.of(macro));
        assertThrows(IllegalArgumentException.class, () -> ExactTheoryEvidence.fromVerified(loaded));
        assertThrows(IllegalArgumentException.class, () -> loaded.requireReplay("x + 0 + 0", List.of(new Transformation("macro", "x"))));
        assertThrows(IllegalArgumentException.class, () -> loaded.requireStep("x + 0 + 0", "x", "another",
            RewriteKind.NORMALIZE, true, List.of("a != 0")));
    }

    @Test void rejectsCounterEndpointSchemaAndFieldSubstitution() {
        String json = RecordedExecution.capture("x + 0", List.of(new Transformation("add zero", "x"))).toCanonicalJson();
        for (String corrupted : List.of(json.replace("\"primitiveRewrites\":1", "\"primitiveRewrites\":0"),
                json.replace("\"edgeCount\":1", "\"edgeCount\":2"),
                json.replace("\"transformedExpression\":\"x\"", "\"transformedExpression\":\"y\""),
                json.replace(RecordedExecution.SCHEMA, "unknown/v1"), json + " ",
                json.replace("\"edgeCount\":1", "\"edgeCount\":1,\"hidden\":true"))) {
            assertThrows(IllegalArgumentException.class, () -> RecordedExecution.fromCanonicalJson(corrupted));
        }
    }

    @Test void rootAndNestedSequencesRoundTripAndMalformedDataStayBounded() {
        var root = RecordedExecution.capture("x", List.of());
        assertEquals(root, RecordedExecution.fromCanonicalJson(root.toCanonicalJson()));
        assertEquals(ExecutionWork.ZERO, root.work());
        var sequence = new TransformationProvenance.Sequence("x + 0", List.of(new Transformation("add zero", "x")));
        var nested = new Transformation("program:test", "x", RewriteKind.NORMALIZE, false, 0, true,
            "nested", List.of(), "test", "PROJECT", List.of("add zero"), sequence);
        var captured = RecordedExecution.capture("x + 0", List.of(nested));
        assertEquals(captured, RecordedExecution.fromCanonicalJson(captured.toCanonicalJson()));
        assertThrows(IllegalArgumentException.class, () -> RecordedExecution.fromCanonicalJson("[".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> RecordedExecution.fromCanonicalJson("{\"x\":\"\\"));
        assertThrows(IllegalArgumentException.class, () -> RecordedExecution.capture("\ud800", List.of()));
    }
}
