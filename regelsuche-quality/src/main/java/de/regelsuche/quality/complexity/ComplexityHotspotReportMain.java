package de.regelsuche.quality.complexity;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Writes both diagnostics before returning a failing hotspot decision to the build. */
public final class ComplexityHotspotReportMain {
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
        .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
        .disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE).build();

    private ComplexityHotspotReportMain() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 3) throw new IllegalArgumentException("expected <complexity.json> <policy.json> <output directory>");
        var report = evaluate(JSON.readTree(Path.of(args[0]).toFile()),
            JSON.readTree(Path.of(args[1]).toFile()), LocalDate.now());
        Path output = Files.createDirectories(Path.of(args[2]));
        var indent = new DefaultIndenter("  ", "\n");
        var printer = new DefaultPrettyPrinter(Separators.createDefaultInstance()
            .withObjectFieldValueSpacing(Separators.Spacing.AFTER).withObjectEmptySeparator("").withArrayEmptySeparator(""))
            .withObjectIndenter(indent).withArrayIndenter(indent);
        write(output.resolve("complexity-hotspot-report.md"), markdown(report));
        write(output.resolve("complexity-hotspot-report.json"), JSON.writer(printer).writeValueAsString(report) + "\n");
        System.out.println("complexityHotspotStatus=" + report.get("status").textValue());
        System.out.println("complexityHotspotCount=" + report.get("currentHotspotCount").intValue());
        if (!report.get("violations").isEmpty()) throw new IllegalStateException(report.get("violations").toString());
    }

    static ObjectNode evaluate(JsonNode measurement, JsonNode policy, LocalDate today) {
        if (!"regelsuche.quality.complexity-hotspot-policy/v1".equals(text(policy, "schema")))
            throw new IllegalArgumentException("unsupported hotspot policy schema");
        Map<String, ObjectNode> current = new TreeMap<>();
        for (String field : List.of("topCyclomaticMethods", "topCognitiveMethods")) {
            readMethods(rows(measurement.path("codeComplexity"), field), current, true);
        }
        Map<String, ObjectNode> baseline = new TreeMap<>();
        readMethods(rows(policy, "baselineHotspots"), baseline, false);
        Map<String, JsonNode> exceptions = exceptions(policy, today);
        int cognitive = numberOrZero(policy, "allowedCognitiveIncrease");
        int cyclomatic = numberOrZero(policy, "allowedCyclomaticIncrease");
        var report = JSON.createObjectNode().put("schema", "regelsuche.quality.complexity-hotspot-report/v1")
            .put("allowedCognitiveIncrease", cognitive).put("allowedCyclomaticIncrease", cyclomatic)
            .put("currentHotspotCount", current.size());
        var changes = report.putArray("changes");
        var violations = report.putArray("violations");
        for (var entry : current.entrySet()) {
            String id = entry.getKey();
            var change = compare(id, entry.getValue(), baseline.remove(id), exceptions.get(id), cognitive, cyclomatic);
            changes.add(change);
            if (change.get("blocked").booleanValue()) {
                var method = entry.getValue();
                violations.add(text(change, "status") + ": " + text(method, "sourceFile") + " :: " + text(method, "signature")
                    + " (cognitive=" + method.get("cognitiveComplexity") + ", cyclomatic=" + method.get("cyclomaticComplexity") + ")");
            }
        }
        var retired = report.putArray("retiredOrNoLongerTopHotspots");
        baseline.values().forEach(retired::add);
        var active = report.putArray("activeExceptions");
        exceptions.values().stream().map(item -> text(item, "id")).sorted().forEach(active::add);
        return report.put("status", violations.isEmpty() ? "PASSED" : "FAILED");
    }

    private static void readMethods(JsonNode rows, Map<String, ObjectNode> methods, boolean overlappingRankings) {
        for (var raw : rows) {
            var method = JSON.createObjectNode().put("sourceFile", text(raw, "sourceFile")).put("signature", text(raw, "signature"))
                .put("type", raw.path("type").asText("")).put("maxNestingDepth", numberOrZero(raw, "maxNestingDepth"))
                .put("cognitiveComplexity", number(raw, "cognitiveComplexity")).put("cyclomaticComplexity", number(raw, "cyclomaticComplexity"));
            String key = identity(method);
            var prior = methods.putIfAbsent(key, method);
            if (prior != null && (!overlappingRankings || !prior.equals(method)))
                throw new IllegalArgumentException("conflicting or duplicate method " + key);
        }
    }

    private static ObjectNode compare(String id, JsonNode method, JsonNode baseline, JsonNode exception,
                                      int cognitive, int cyclomatic) {
        int currentCognitive = method.get("cognitiveComplexity").intValue();
        int currentCyclomatic = method.get("cyclomaticComplexity").intValue();
        int cognitiveIncrease = currentCognitive;
        int cyclomaticIncrease = currentCyclomatic;
        String status = "NEW_HOTSPOT";
        if (baseline != null) {
            cognitiveIncrease -= baseline.get("cognitiveComplexity").intValue();
            cyclomaticIncrease -= baseline.get("cyclomaticComplexity").intValue();
            status = cognitiveIncrease > cognitive || cyclomaticIncrease > cyclomatic ? "WORSENED" : "UNCHANGED_OR_IMPROVED";
        }
        boolean allowed = exception != null && currentCognitive <= number(exception, "maximumCognitiveComplexity")
            && currentCyclomatic <= number(exception, "maximumCyclomaticComplexity");
        var change = JSON.createObjectNode().put("identity", id).put("status", status)
            .put("blocked", !status.equals("UNCHANGED_OR_IMPROVED") && !allowed)
            .put("exceptionId", exception == null ? null : text(exception, "id"))
            .put("cognitiveIncrease", cognitiveIncrease).put("cyclomaticIncrease", cyclomaticIncrease);
        change.set("baseline", baseline);
        change.set("current", method);
        return change;
    }

    private static Map<String, JsonNode> exceptions(JsonNode policy, LocalDate today) {
        var result = new TreeMap<String, JsonNode>();
        var entries = policy.path("exceptions");
        if (entries.isMissingNode()) return result;
        if (!entries.isArray()) throw new IllegalArgumentException("exceptions must be an array");
        for (var item : entries) {
            String key = identity(item);
            text(item, "rationale");
            text(item, "id");
            number(item, "maximumCognitiveComplexity");
            number(item, "maximumCyclomaticComplexity");
            if (LocalDate.parse(text(item, "expiresOn")).isBefore(today))
                throw new IllegalArgumentException("expired complexity exception " + text(item, "id"));
            if (result.putIfAbsent(key, item) != null) throw new IllegalArgumentException("duplicate exception for " + key);
        }
        return result;
    }

    private static String identity(JsonNode method) {
        return text(method, "sourceFile") + "|" + text(method, "signature");
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

    private static void write(Path output, String contents) throws IOException {
        var stage = Files.createTempFile(output.getParent(), ".complexity-", ".tmp");
        try {
            Files.writeString(stage, contents);
            Files.move(stage, output, ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(stage);
        }
    }

    private static String markdown(JsonNode report) {
        var text = new StringBuilder("# Cognitive-complexity hotspot trend\n\nStatus: **")
            .append(report.get("status").textValue()).append("**\n\n")
            .append("| Status | Cognitive Δ | Cyclomatic Δ | Method |\n| --- | ---: | ---: | --- |\n");
        for (var change : report.get("changes")) {
            text.append("| ").append(change.get("status").textValue()).append(" | ").append(signed(change.get("cognitiveIncrease").intValue()))
                .append(" | ").append(signed(change.get("cyclomaticIncrease").intValue())).append(" | `")
                .append(change.at("/current/sourceFile").textValue()).append(" :: ").append(change.at("/current/signature").textValue()).append("` |\n");
        }
        text.append("\n## Exception format\n\nAn exception must name an exact source/signature pair, finite cognitive and ")
            .append("cyclomatic ceilings, a rationale and an ISO expiry date. Expired, duplicate ")
            .append("or ceiling-exceeding exceptions fail closed.\n");
        if (!report.get("violations").isEmpty()) {
            text.append("\n## Violations\n\n");
            report.get("violations").forEach(item -> text.append("- ").append(item.textValue()).append('\n'));
        }
        return text.toString();
    }

    private static String signed(int value) {
        return value >= 0 ? "+" + value : Integer.toString(value);
    }
}
