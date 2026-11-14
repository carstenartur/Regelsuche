package de.regelsuche.quality.jmh;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Validates the unit-aware average-time publication contract against the frozen baseline. */
final class JmhPublication {
    record Row(String benchmark, String unit, double score, double error, double baseline) {}
    record Report(String revision, List<Row> rows) {}
    record Policy(int forks, int warmups, int iterations, String warmupTime, String measurementTime, double ratio) {}

    private JmhPublication() {}

    static Report verify(JsonNode baseline, JsonNode measurements) {
        expect("regelsuche.quality.jmh-baseline/v2".equals(baseline.path("schema").textValue()), "unsupported baseline schema");
        String revision = text(baseline, "baselineRevision");
        expect(revision.length() == 40, "invalid baselineRevision");
        Policy policy = policy(baseline.path("measurementPolicy"));
        JsonNode expected = baseline.path("benchmarks");
        expect(expected.isObject() && !expected.isEmpty(), "missing baseline benchmarks");
        Map<String, JsonNode> observed = inventory(measurements);
        expect(observed.size() == expected.size() && observed.keySet().stream().allMatch(expected::has), "result inventory differs from the baseline");
        List<Row> rows = new ArrayList<>();
        for (var entry : observed.entrySet()) {
            String name = entry.getKey();
            JsonNode result = entry.getValue();
            checkExecution(name, result, policy);
            rows.add(measurement(name, result.path("primaryMetric"), expected.path(name), policy.ratio()));
        }
        return new Report(revision, List.copyOf(rows));
    }

    private static Policy policy(JsonNode node) {
        expect("avgt".equals(text(node, "mode")), "baseline mode must be avgt");
        double ratio = number(node, "materialRegressionRatio");
        expect(ratio > 1, "materialRegressionRatio must be greater than one");
        return new Policy(integer(node, "forks"), integer(node, "warmupIterations"), integer(node, "measurementIterations"),
            duration(node, "warmupTime"), duration(node, "measurementTime"), ratio);
    }

    private static Map<String, JsonNode> inventory(JsonNode measurements) {
        expect(measurements.isArray() && !measurements.isEmpty(), "no benchmark results were retained");
        Map<String, JsonNode> rows = new TreeMap<>();
        for (JsonNode row : measurements) {
            String name = text(row, "benchmark");
            JsonNode params = row.path("params");
            expect(params.isMissingNode() || params.isNull() || (params.isObject() && params.isEmpty()),
                "unexpected parameters: " + name);
            expect(rows.putIfAbsent(name, row) == null, "duplicate benchmark: " + name);
        }
        return rows;
    }

    private static void checkExecution(String name, JsonNode row, Policy policy) {
        expect("avgt".equals(text(row, "mode")), name + " mode differs");
        checkCount(row, "forks", policy.forks(), name);
        checkCount(row, "threads", 1, name);
        checkCount(row, "warmupBatchSize", 1, name);
        checkCount(row, "measurementBatchSize", 1, name);
        checkCount(row, "warmupIterations", policy.warmups(), name);
        checkCount(row, "measurementIterations", policy.iterations(), name);
        expect(duration(row, "warmupTime").equals(policy.warmupTime()), name + " warmupTime differs");
        expect(duration(row, "measurementTime").equals(policy.measurementTime()), name + " measurementTime differs");
    }

    private static Row measurement(String name, JsonNode metric, JsonNode reference, double ratio) {
        String unit = text(reference, "scoreUnit");
        expect(unit.equals(text(metric, "scoreUnit")), name + " scoreUnit differs");
        double score = number(metric, "score");
        double error = number(metric, "scoreError");
        double baseline = number(reference, "baselineScore");
        double ceiling = baseline * ratio;
        double lowerBound = Math.max(0, score - error);
        expect(Double.isFinite(ceiling), name + " threshold overflow");
        expect(lowerBound <= ceiling, name + ": material regression, lowerBound=" + lowerBound + " > threshold=" + ceiling + " " + unit);
        return new Row(name, unit, score, error, baseline);
    }

    private static void checkCount(JsonNode row, String key, int expected, String name) {
        expect(integer(row, key) == expected, name + " " + key + " differs");
    }

    private static int integer(JsonNode node, String key) {
        JsonNode value = node.path(key);
        expect(value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0, key + " must be a positive integer");
        return value.intValue();
    }

    private static String text(JsonNode node, String key) {
        String value = node.path(key).textValue();
        expect(value != null && !value.isEmpty(), key + " must be a nonempty string");
        return value;
    }

    private static String duration(JsonNode node, String key) {
        String normalized = text(node, key).replaceAll("(?U)\\s+", "").toLowerCase(Locale.ROOT);
        expect(normalized.matches("[1-9][0-9]*(ns|us|ms|s|m|h)"), key + " has unsupported duration syntax");
        return normalized;
    }

    private static double number(JsonNode node, String key) {
        JsonNode value = node.path(key);
        double number = value.doubleValue();
        expect(value.isNumber() && Double.isFinite(number) && number >= 0, key + " must be a finite nonnegative number");
        return number;
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
