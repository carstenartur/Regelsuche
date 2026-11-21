package de.regelsuche.math.sympy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class FrozenSymPyOperationObservationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final String hash = "a".repeat(64);

    private ObjectNode receipt() {
        ObjectNode object = json.createObjectNode();
        object.put("schema", "regelsuche.frozen-sympy-operation-observation/v1");
        object.put("runtime", "graalpy");
        object.put("implementationVersion", "25.1.3");
        object.put("sympy", "1.14.0");
        object.put("mpmath", "1.3.0");
        object.put("stdlibRandomSha256", "0693d4ded36916f5b07d6c395cc331dbf1011bb70e90daaa29eaa32490a09425");
        object.put("inputSha256", hash);
        object.put("bridgeSha256", "d67e266e11c92fdd3e5e2c85c30a89a7b5292f56e291bc86a9d036ac7aab23c4");
        object.put("pinsSha256", "5d1e9fdc4960ffa6112bcbe475700a34c7914518c2c58bb9197ea9d26750a14c");
        object.put("meterSha256", hash);
        object.put("status", "WORK_EXHAUSTED");
        object.put("workLimit", 3);
        object.put("used", 3);
        object.put("strictNative", true);
        object.put("coverageGoal", "DECLARED_OPERATIONS");
        object.putObject("boundaryGaps");
        object.put("studyAuthorized", false);
        object.putNull("result");
        object.put("reason", "sympy.polys.example.add");
        object.putObject("stages").put("sympy.polys.example.mul", 3);
        object.putArray("opaqueCalls");
        object.putObject("foreignCallbacks");
        return object;
    }

    private FrozenSymPyOperationObservation receive(ObjectNode object) {
        return FrozenSymPyOperationObservation.decode(object.toString(), hash, hash, 3, FrozenSymPyOperationObservation.Coverage.DECLARED_OPERATIONS);
    }

    @Test
    void acceptsExactRejectedPrefixWithoutAResult() {
        var observation = receive(receipt());
        assertEquals("WORK_EXHAUSTED", observation.status().name());
        assertEquals(3, observation.work().totalWorkUnits());
    }

    @Test
    void rejectsLooseInputRuntimeSourceWorkOrOutcomeBindings() {
        assertThrows(IllegalArgumentException.class, () -> receive(receipt().put("inputSha256", "b".repeat(64))));
        assertThrows(IllegalArgumentException.class, () -> receive(receipt().put("runtime", "cpython")));
        assertThrows(IllegalArgumentException.class, () -> receive(receipt().put("meterSha256", "b".repeat(64))));
        assertThrows(IllegalArgumentException.class, () -> receive(receipt().put("used", 2)));
        assertThrows(IllegalArgumentException.class, () -> receive(receipt().put("result", "candidate")));
        assertThrows(IllegalArgumentException.class, () -> receive(receipt().put("studyAuthorized", true)));
        assertThrows(IllegalArgumentException.class, () -> receive(receipt().put("workLimit", 3.0)));
    }

    @Test
    void fullAuthorityCannotBeBorrowedFromAMeasuredSubset() {
        var root = receipt();
        var goal = FrozenSymPyOperationObservation.Coverage.COMPLETE_BACKEND_WORK;
        assertThrows(IllegalArgumentException.class, () -> FrozenSymPyOperationObservation.decode(root.toString(), hash, hash, 3, goal));
        root.put("coverageGoal", goal.name());
        root.putObject("boundaryGaps").put("transport.json.loads", 1);
        assertThrows(IllegalArgumentException.class, () -> FrozenSymPyOperationObservation.decode(root.toString(), hash, hash, 3, goal));
    }

    @Test
    void rejectsForeignStagePrefixesBeforeProjection() {
        ObjectNode object = receipt();
        object.putObject("stages").put("projection.root-source-hash-code-units", 3);
        assertThrows(IllegalArgumentException.class, () -> receive(object));
    }
}
