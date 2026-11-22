package de.regelsuche.math.sympy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import java.util.LinkedHashMap;
import java.util.Map;

/** Diagnostic receipt from an explicitly pinned historical backend process. */
public final class FrozenSymPyOperationObservation {
    public enum Coverage { DECLARED_OPERATIONS, COMPLETE_BACKEND_WORK }
    public enum Status { OBSERVED, WORK_EXHAUSTED, UNMEASURED }

    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String BRIDGE = "d67e266e11c92fdd3e5e2c85c30a89a7b5292f56e291bc86a9d036ac7aab23c4";
    private static final String PINS = "5d1e9fdc4960ffa6112bcbe475700a34c7914518c2c58bb9197ea9d26750a14c";

    private final Status status;
    private final PolynomialWorkLedger work;
    private final String receipt;

    private FrozenSymPyOperationObservation(Status status, PolynomialWorkLedger work, String receipt) {
        this.status = status;
        this.work = work;
        this.receipt = receipt;
    }

    public Status status() { return status; }
    public PolynomialWorkLedger work() { return work; }
    public String receipt() { return receipt; }

    static FrozenSymPyOperationObservation decode(String receipt, String inputHash, String meterHash, long limit, Coverage goal) {
        JsonNode root;
        try {
            root = JSON.readTree(receipt);
        } catch (JsonProcessingException malformed) {
            throw new IllegalArgumentException("malformed frozen operation receipt", malformed);
        }
        require(root != null && root.isObject(), "expected receipt object");
        requireIdentity(root, inputHash, meterHash);
        require(root.path("strictNative").isBoolean() && root.path("strictNative").booleanValue(), "strict native checks required");
        require(root.path("studyAuthorized").isBoolean() && !root.path("studyAuthorized").booleanValue(), "diagnostic receipt required");
        require(limit >= 0 && unsigned(root.path("workLimit")) == limit, "foreign work limit");
        var ledger = readLedger(root.path("stages"));
        long used = unsigned(root.path("used"));
        require(used <= limit && used == ledger.totalWorkUnits(), "inconsistent operation ledger");
        require(goal.name().equals(root.path("coverageGoal").textValue()), "foreign coverage boundary");
        require(root.path("boundaryGaps").isObject(), "missing semantic boundary inventory");
        Status status = Status.valueOf(root.path("status").asText());
        if (goal == Coverage.COMPLETE_BACKEND_WORK && !root.path("boundaryGaps").isEmpty()) {
            require(status == Status.UNMEASURED, "uncovered work cannot be a completed prefix");
        }
        verifyOutcome(root, status, used, limit);
        return new FrozenSymPyOperationObservation(status, ledger, receipt);
    }

    private static void requireIdentity(JsonNode root, String inputHash, String meterHash) {
        Map.of(
            "schema", "regelsuche.frozen-sympy-operation-observation/v1",
            "runtime", "graalpy", "implementationVersion", "25.1.3",
            "sympy", "1.14.0", "mpmath", "1.3.0",
            "stdlibRandomSha256", "0693d4ded36916f5b07d6c395cc331dbf1011bb70e90daaa29eaa32490a09425",
            "inputSha256", inputHash, "meterSha256", meterHash,
            "bridgeSha256", BRIDGE, "pinsSha256", PINS
        ).forEach((key, expected) -> require(expected.equals(root.path(key).textValue()), "wrong " + key));
    }

    private static PolynomialWorkLedger readLedger(JsonNode stages) {
        require(stages.isObject(), "expected operation stages");
        Map<String, Long> values = new LinkedHashMap<>();
        stages.properties().forEach(entry -> {
            String name = entry.getKey();
            require(name.matches("(sympy|mpmath|random|graalpy|regelsuche_frozen_bridge)\\.[A-Za-z0-9_.<>-]+"), "foreign operation stage");
            long units = unsigned(entry.getValue());
            require(units > 0, "empty stage");
            values.put(name, units);
        });
        return new PolynomialWorkLedger(values);
    }

    private static void verifyOutcome(JsonNode root, Status status, long used, long limit) {
        JsonNode opaque = root.path("opaqueCalls");
        JsonNode foreign = root.path("foreignCallbacks");
        require(opaque.isArray() && foreign.isObject(), "missing operation coverage record");
        if (status == Status.OBSERVED) {
            require(opaque.isEmpty() && foreign.isEmpty() && root.path("result").isObject() && root.path("reason").isNull(),
                "invalid completed observation");
        } else {
            require(root.path("result").isNull() && root.path("reason").isTextual() && !root.path("reason").textValue().isBlank(), "invalid interrupted observation");
            if (status == Status.WORK_EXHAUSTED) {
                require(used == limit && opaque.isEmpty() && foreign.isEmpty(), "incomplete budget prefix");
            }
        }
    }

    private static long unsigned(JsonNode node) {
        require(node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0, "invalid work count");
        return node.longValue();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
