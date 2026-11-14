package de.regelsuche.quality.complexity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Applies the v1 hotspot policy to the extractor's two method rankings. */
final class ComplexityHotspots {
    private static final String POLICY = "regelsuche.quality.complexity-hotspot-policy/v1";
    private static final String REPORT = "regelsuche.quality.complexity-hotspot-report/v1";

    private ComplexityHotspots() {}

    record Method(String sourceFile, String type, String signature, int cognitiveComplexity,
                  int cyclomaticComplexity, int maxNestingDepth) {
        static Method read(JsonNode method) {
            return new Method(text(method, "sourceFile"), method.path("type").asText(""), text(method, "signature"),
                number(method, "cognitiveComplexity"), number(method, "cyclomaticComplexity"), numberOrZero(method, "maxNestingDepth"));
        }

        String identity() {
            return sourceFile + "|" + signature;
        }
    }

    record Change(String identity, String status, boolean blocked, String exceptionId, int cognitiveIncrease,
                  int cyclomaticIncrease, Method baseline, Method current) {
        String violation() {
            return status + ": " + current.sourceFile + " :: " + current.signature
                + " (cognitive=" + current.cognitiveComplexity + ", cyclomatic=" + current.cyclomaticComplexity + ")";
        }
    }

    record Report(String schema, String status, int allowedCognitiveIncrease, int allowedCyclomaticIncrease,
                  int currentHotspotCount, List<Change> changes, List<Method> retiredOrNoLongerTopHotspots,
                  List<String> activeExceptions, List<String> violations) {}

    private record ExceptionLimit(String id, int cognitive, int cyclomatic) {
        boolean permits(Method method) {
            return method.cognitiveComplexity <= cognitive && method.cyclomaticComplexity <= cyclomatic;
        }
    }

    static Report evaluate(JsonNode measurement, JsonNode policy, LocalDate today) {
        if (!POLICY.equals(text(policy, "schema"))) throw new IllegalArgumentException("unsupported hotspot policy schema");
        var current = readMeasurements(measurement.path("codeComplexity"));
        var baseline = new TreeMap<String, Method>();
        for (var raw : rows(policy, "baselineHotspots")) {
            Method method = Method.read(raw);
            if (baseline.putIfAbsent(method.identity(), method) != null)
                throw new IllegalArgumentException("duplicate baseline method " + method.identity());
        }
        var exceptions = readExceptions(policy, today);
        int cognitive = numberOrZero(policy, "allowedCognitiveIncrease");
        int cyclomatic = numberOrZero(policy, "allowedCyclomaticIncrease");
        var changes = new ArrayList<Change>();
        for (var method : current.values()) {
            String id = method.identity();
            changes.add(compare(method, baseline.remove(id), exceptions.get(id), cognitive, cyclomatic));
        }
        var violations = changes.stream().filter(Change::blocked).map(Change::violation).toList();
        var active = exceptions.values().stream().map(ExceptionLimit::id).sorted().toList();
        return new Report(REPORT, violations.isEmpty() ? "PASSED" : "FAILED", cognitive, cyclomatic,
            current.size(), changes, List.copyOf(baseline.values()), active, violations);
    }

    private static Map<String, Method> readMeasurements(JsonNode code) {
        var current = new TreeMap<String, Method>();
        for (String ranking : List.of("topCyclomaticMethods", "topCognitiveMethods")) {
            for (var raw : rows(code, ranking)) {
                Method method = Method.read(raw);
                var previous = current.putIfAbsent(method.identity(), method);
                if (previous != null && !previous.equals(method))
                    throw new IllegalArgumentException("conflicting measurements for " + method.identity());
            }
        }
        return current;
    }

    private static Map<String, ExceptionLimit> readExceptions(JsonNode policy, LocalDate today) {
        var exceptions = new TreeMap<String, ExceptionLimit>();
        var entries = policy.path("exceptions");
        if (entries.isMissingNode()) return exceptions;
        if (!entries.isArray()) throw new IllegalArgumentException("exceptions must be an array");
        for (var item : entries) {
            String key = text(item, "sourceFile") + "|" + text(item, "signature");
            text(item, "rationale");
            var limit = new ExceptionLimit(text(item, "id"), number(item, "maximumCognitiveComplexity"),
                number(item, "maximumCyclomaticComplexity"));
            if (LocalDate.parse(text(item, "expiresOn")).isBefore(today))
                throw new IllegalArgumentException("expired complexity exception " + limit.id);
            if (exceptions.putIfAbsent(key, limit) != null)
                throw new IllegalArgumentException("duplicate complexity exception for " + key);
        }
        return exceptions;
    }

    private static Change compare(Method method, Method baseline, ExceptionLimit limit, int cognitive, int cyclomatic) {
        int cognitiveIncrease = method.cognitiveComplexity;
        int cyclomaticIncrease = method.cyclomaticComplexity;
        String status = "NEW_HOTSPOT";
        if (baseline != null) {
            cognitiveIncrease -= baseline.cognitiveComplexity;
            cyclomaticIncrease -= baseline.cyclomaticComplexity;
            status = cognitiveIncrease > cognitive || cyclomaticIncrease > cyclomatic ? "WORSENED" : "UNCHANGED_OR_IMPROVED";
        }
        boolean blocked = !status.equals("UNCHANGED_OR_IMPROVED") && (limit == null || !limit.permits(method));
        return new Change(method.identity(), status, blocked, limit == null ? null : limit.id,
            cognitiveIncrease, cyclomaticIncrease, baseline, method);
    }

    private static JsonNode rows(JsonNode object, String field) {
        var array = object.path(field);
        if (!array.isArray() || array.isEmpty()) throw new IllegalArgumentException(field + " must be a nonempty array");
        return array;
    }

    private static String text(JsonNode object, String field) {
        var value = object.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException("missing or blank " + field);
        return value.textValue();
    }

    private static int numberOrZero(JsonNode object, String field) {
        return object.has(field) ? number(object, field) : 0;
    }

    private static int number(JsonNode object, String field) {
        var value = object.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0)
            throw new IllegalArgumentException("expected a non-negative integer for " + field);
        return value.intValue();
    }
}
