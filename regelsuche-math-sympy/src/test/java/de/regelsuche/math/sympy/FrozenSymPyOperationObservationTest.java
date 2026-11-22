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

    @Test
    void completedObservationsRequireAResultWithoutUnaccountedCalls() {
        ObjectNode root = receipt();
        root.put("status", "OBSERVED");
        root.putObject("result").put("verified", true);
        root.putNull("reason");

        var accepted = receive(root);
        assertEquals(FrozenSymPyOperationObservation.Status.OBSERVED, accepted.status());
        assertEquals(3, accepted.work().totalWorkUnits());
        assertEquals(root.toString(), accepted.receipt());

        ObjectNode opaque = root.deepCopy();
        opaque.putArray("opaqueCalls").add("hidden.factor");
        assertThrows(IllegalArgumentException.class, () -> receive(opaque));

        ObjectNode callback = root.deepCopy();
        callback.putObject("foreignCallbacks").put("unmetered", 1);
        assertThrows(IllegalArgumentException.class, () -> receive(callback));

        ObjectNode absentResult = root.deepCopy();
        absentResult.putNull("result");
        assertThrows(IllegalArgumentException.class, () -> receive(absentResult));

        ObjectNode spuriousReason = root.deepCopy();
        spuriousReason.put("reason", "unexpected");
        assertThrows(IllegalArgumentException.class, () -> receive(spuriousReason));
    }

    @Test
    void unmeasuredFullWorkAndUnderBudgetExhaustionRemainDistinct() {
        ObjectNode full = receipt();
        full.put("coverageGoal", FrozenSymPyOperationObservation.Coverage.COMPLETE_BACKEND_WORK.name());
        full.put("status", "UNMEASURED");
        full.putObject("boundaryGaps").put("transport.json.loads", 1);
        var observation = FrozenSymPyOperationObservation.decode(full.toString(), hash, hash, 3,
            FrozenSymPyOperationObservation.Coverage.COMPLETE_BACKEND_WORK);
        assertEquals(FrozenSymPyOperationObservation.Status.UNMEASURED, observation.status());

        ObjectNode underBudget = receipt();
        underBudget.put("workLimit", 4);
        assertThrows(IllegalArgumentException.class,
            () -> FrozenSymPyOperationObservation.decode(underBudget.toString(), hash, hash, 4,
                FrozenSymPyOperationObservation.Coverage.DECLARED_OPERATIONS));

        ObjectNode blankReason = receipt();
        blankReason.put("status", "UNMEASURED");
        blankReason.put("reason", "  ");
        assertThrows(IllegalArgumentException.class, () -> receive(blankReason));

        ObjectNode interruptedResult = receipt();
        interruptedResult.put("status", "UNMEASURED");
        interruptedResult.putObject("result").put("unverified", true);
        assertThrows(IllegalArgumentException.class, () -> receive(interruptedResult));
    }

    @Test
    void malformedReceiptsAndUnmeteredStageValuesFailClosed() {
        assertThrows(IllegalArgumentException.class,
            () -> FrozenSymPyOperationObservation.decode("[]", hash, hash, 3,
                FrozenSymPyOperationObservation.Coverage.DECLARED_OPERATIONS));
        assertThrows(IllegalArgumentException.class,
            () -> FrozenSymPyOperationObservation.decode(receipt() + " trailing", hash, hash, 3,
                FrozenSymPyOperationObservation.Coverage.DECLARED_OPERATIONS));

        ObjectNode badIdentity = receipt();
        badIdentity.put("strictNative", false);
        assertThrows(IllegalArgumentException.class, () -> receive(badIdentity));

        ObjectNode noInventory = receipt();
        noInventory.putNull("boundaryGaps");
        assertThrows(IllegalArgumentException.class, () -> receive(noInventory));

        ObjectNode zeroUnits = receipt();
        zeroUnits.putObject("stages").put("sympy.polys.example.add", 0);
        assertThrows(IllegalArgumentException.class, () -> receive(zeroUnits));

        ObjectNode fractionalUnits = receipt();
        fractionalUnits.putObject("stages").put("sympy.polys.example.add", 3.5);
        assertThrows(IllegalArgumentException.class, () -> receive(fractionalUnits));

        ObjectNode negativeUnits = receipt();
        negativeUnits.putObject("stages").put("sympy.polys.example.add", -1);
        assertThrows(IllegalArgumentException.class, () -> receive(negativeUnits));

        ObjectNode missingOperation = receipt();
        missingOperation.putNull("stages");
        assertThrows(IllegalArgumentException.class, () -> receive(missingOperation));

        ObjectNode unknownStatus = receipt();
        unknownStatus.put("status", "SUCCESS");
        assertThrows(IllegalArgumentException.class, () -> receive(unknownStatus));
    }
}
