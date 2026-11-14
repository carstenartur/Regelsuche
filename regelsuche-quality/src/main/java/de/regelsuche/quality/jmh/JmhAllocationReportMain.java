package de.regelsuche.quality.jmh;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Verifies the scoped JMH GC-profiler inventory and writes its canonical summary. */
public final class JmhAllocationReportMain {
    private static final String SEARCH = "de.regelsuche.benchmark.PreparedAstSearchBenchmarks.";
    private static final String REWRITE = "de.regelsuche.benchmark.RewriteProgramBenchmarks.";
    private static final String GC_METRIC = "·gc.alloc.rate.norm";
    private static final List<String> REQUIRED = List.of(
        REWRITE + "directAstRewriteSource",
        REWRITE + "preparedAstRewriteSource",
        SEARCH + "referenceFixedWorkSearch",
        SEARCH + "preparedFixedWorkSearch",
        SEARCH + "referenceTargetedSearch",
        SEARCH + "preparedTargetedSearch");
    private static final List<String> COUNTERS = List.of(
        "enqueuedStates", "expandedStates", "exploredStates", "generatedTransformations", "reachedTargets", "searches");
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DefaultIndenter INDENT = new DefaultIndenter("  ", "\n");

    private JmhAllocationReportMain() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("expected <JMH result> <allocation summary output>");
        }
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        if (Files.exists(output) && Files.isSameFile(input, output)) {
            throw new IllegalArgumentException("allocation summary output aliases the JMH result");
        }
        var report = summarize(JSON.readTree(input.toFile()));
        var printer = new DefaultPrettyPrinter(Separators.createDefaultInstance()
            .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
            .withObjectEmptySeparator("").withArrayEmptySeparator(""))
            .withObjectIndenter(INDENT).withArrayIndenter(INDENT);
        String contents = JSON.writer(printer).writeValueAsString(report) + "\n";
        Files.createDirectories(output.getParent());
        Path staged = Files.createTempFile(output.getParent(), ".allocation-", ".tmp");
        try {
            Files.writeString(staged, contents);
            Files.move(staged, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
        System.out.println("verified " + REQUIRED.size() + " JMH allocation benchmarks: " + output);
    }

    private static ObjectNode summarize(JsonNode input) {
        Map<String, JsonNode> entries = readEntries(input);
        var missing = REQUIRED.stream().filter(name -> !entries.containsKey(name)).sorted().toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("required allocation benchmarks missing: " + missing);
        }
        var report = JSON.createObjectNode().put("allocationUnit", "B/op");
        var rows = report.putArray("benchmarks");
        for (String name : REQUIRED) {
            var row = rows.addObject().put("benchmark", name);
            writeMeasurements(row, name, entries.get(name));
        }
        return report.put("primaryUnit", "ms/op").put("schema", "regelsuche.jmh-allocation-summary/v1");
    }

    private static Map<String, JsonNode> readEntries(JsonNode input) {
        if (input == null || !input.isArray()) {
            throw new IllegalArgumentException("JMH allocation result must be a JSON array");
        }
        Map<String, JsonNode> entries = new HashMap<>();
        for (JsonNode entry : input) {
            var name = entry.path("benchmark");
            if (!entry.isObject() || !name.isTextual()) {
                throw new IllegalArgumentException("invalid JMH benchmark entry");
            }
            if (REQUIRED.contains(name.textValue()) && entries.putIfAbsent(name.textValue(), entry) != null) {
                throw new IllegalArgumentException("duplicate JMH allocation benchmark: " + name.textValue());
            }
        }
        return entries;
    }

    private static void writeMeasurements(ObjectNode row, String name, JsonNode entry) {
        var primary = requireObject(entry.path("primaryMetric"), name + ": primaryMetric");
        var secondary = requireObject(entry.path("secondaryMetrics"), name + ": secondaryMetrics");
        var allocation = requireObject(secondary.path(GC_METRIC), name + ": " + GC_METRIC);
        requireUnit(primary, "ms/op", name + ": expected primary unit");
        requireUnit(allocation, "B/op", name + ": expected " + GC_METRIC + " unit");
        row.putRawValue("bytesPerOperation", measurement(allocation, name + " " + GC_METRIC));
        row.putRawValue("millisecondsPerOperation", measurement(primary, name + " primary score"));
        var counters = row.putObject("workCounters");
        for (String counter : COUNTERS) {
            var value = secondary.path(counter);
            if (value.isObject() && value.has("score")) {
                counters.putRawValue(counter, measurement(value, name + " " + counter));
            }
        }
    }

    private static JsonNode requireObject(JsonNode value, String name) {
        if (!value.isObject()) throw new IllegalArgumentException(name + " missing");
        return value;
    }

    private static void requireUnit(JsonNode metric, String unit, String description) {
        String actual = metric.path("scoreUnit").asText();
        if (!unit.equals(actual)) {
            throw new IllegalArgumentException(description + " " + unit + ", found " + actual);
        }
    }

    private static RawValue measurement(JsonNode metric, String label) {
        var score = metric.path("score");
        if (!score.isNumber()) throw new IllegalArgumentException(label + " must be numeric");
        double value = score.doubleValue();
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(label + " must be finite and non-negative");
        }
        // The retained v1 Python JSON writer switches to scientific notation
        // outside [1e-4, 1e16) and emits a signed, at least two-digit exponent.
        if (value == 0.0) return new RawValue(Double.toString(value));
        var decimal = BigDecimal.valueOf(value).stripTrailingZeros();
        // Java may print two significant digits for a subnormal that also
        // round-trips with one digit. The v1 format uses the shortest form.
        if (value < Double.MIN_NORMAL && decimal.precision() == 2) {
            var oneDigit = new BigDecimal(value, new MathContext(1, RoundingMode.HALF_EVEN));
            if (oneDigit.doubleValue() == value) decimal = oneDigit;
        }
        int exponent = decimal.precision() - decimal.scale() - 1;
        if (exponent >= -4 && exponent < 16) {
            return new RawValue((decimal.scale() < 1 ? decimal.setScale(1) : decimal).toPlainString());
        }
        String digits = Integer.toString(Math.abs(exponent));
        String power = (exponent < 0 ? "-" : "+") + (digits.length() == 1 ? "0" + digits : digits);
        return new RawValue(decimal.movePointLeft(exponent).toPlainString() + "e" + power);
    }
}
