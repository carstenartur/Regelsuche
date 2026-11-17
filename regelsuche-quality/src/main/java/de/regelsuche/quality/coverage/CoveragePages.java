package de.regelsuche.quality.coverage;

import static de.regelsuche.quality.coverage.CoverageRegression.COVERAGE_JSON;
import static de.regelsuche.quality.coverage.CoverageRegression.isUnnamespaced;

import com.fasterxml.jackson.databind.JsonNode;
import de.regelsuche.quality.coverage.CoverageRegression.Counter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.jacoco.core.analysis.ICoverageNode.CounterEntity;

/** Publishes standard JaCoCo pages bound to the already completed coverage gate. */
public final class CoveragePages {
    private static final String SYMPY = "regelsuche-math-sympy";
    private static final Path JACOCO = Path.of("build/reports/jacoco/test");

    private CoveragePages() { }

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("Usage: repository coverage-directory [isolated-sympy-inputs]");
        }
        publish(Path.of(args[0]), Path.of(args[1]), args.length == 3 ? Path.of(args[2]) : null);
    }

    static void publish(Path root, Path output, Path isolatedSympyInputs) throws IOException {
        Gate gate = Gate.read(root);
        Map<String, Path> inputs = new LinkedHashMap<>();
        for (String module : gate.modules().keySet()) {
            Path directory = SYMPY.equals(module) && isolatedSympyInputs != null
                ? isolatedSympyInputs : Path.of(module).resolve(JACOCO).resolve("inputs");
            inputs.put(module, root.resolve(directory));
        }
        JacocoAggregateReport.write(output, inputs);
        String percent = writeBadge(root, output, gate);
        System.out.println("Coverage: " + percent + "% lines");
    }

    static String writeBadge(Path root, Path output, Gate gate) throws IOException {
        var rendered = readXml(output.resolve("jacoco.xml"), true);
        if (!rendered.modules().keySet().equals(gate.modules().keySet())
                || !Files.isRegularFile(output.resolve("index.html"))) {
            throw new IOException("JaCoCo must render every policy module exactly once");
        }
        var totals = emptyCounters();
        for (var entry : gate.modules().entrySet()) {
            String module = entry.getKey();
            var measured = readXml(root.resolve(module).resolve(JACOCO).resolve("jacocoTestReport.xml"), false).totals();
            if (!rendered.modules().get(module).equals(measured)) {
                throw new IOException("Rendered JaCoCo counters differ from measured report: " + module);
            }
            requireGateCounts(module, measured, entry.getValue());
            measured.forEach((kind, count) -> totals.merge(kind, count, Counter::plus));
        }
        if (!rendered.totals().equals(totals)) {
            throw new IOException("JaCoCo aggregate does not equal the complete module totals");
        }
        requireGateCounts("aggregate", totals, gate.aggregate());
        String percent = new BigDecimal(totals.get(CounterEntity.LINE).percent())
            .setScale(2, RoundingMode.HALF_EVEN).toPlainString();
        var badge = COVERAGE_JSON.createObjectNode().put("schemaVersion", 1).put("label", "coverage (lines)")
            .put("message", percent + "%").put("color", "brightgreen");
        Files.writeString(output.resolve("badge.json"), COVERAGE_JSON.writeValueAsString(badge) + "\n");
        return percent;
    }

    private static void requireGateCounts(String name, Map<CounterEntity, Counter> measured, JsonNode gate)
            throws IOException {
        requireGateCounter(name, "line", measured.get(CounterEntity.LINE), gate.path("line"));
        requireGateCounter(name, "branch", measured.get(CounterEntity.BRANCH), gate.path("branch"));
    }

    private static void requireGateCounter(String name, String kind, Counter measured, JsonNode gate)
            throws IOException {
        JsonNode missed = gate.path("missed");
        JsonNode covered = gate.path("covered");
        if (!missed.isIntegralNumber() || !covered.isIntegralNumber()
                || !missed.bigIntegerValue().equals(measured.missed())
                || !covered.bigIntegerValue().equals(measured.covered())) {
            throw new IOException("Coverage gate and published " + kind + " counts differ: " + name);
        }
    }

    private static XmlCounts readXml(Path path, boolean readGroups) throws IOException {
        var modules = new TreeMap<String, Map<CounterEntity, Counter>>();
        try (var input = Files.newInputStream(path)) {
            var reader = CoverageRegression.reportXmlFactory().createXMLStreamReader(input);
            try {
                if (!nextChild(reader) || !isUnnamespaced(reader, "report")) {
                    throw new IOException("JaCoCo XML must have an unnamespaced <report> root");
                }
                var totals = readCounters(reader, readGroups ? modules : null);
                while (reader.hasNext()) reader.next();
                return new XmlCounts(totals, modules);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException failure) {
            throw new IOException("Cannot parse " + path + ": " + failure.getMessage(), failure);
        }
    }

    private static Map<CounterEntity, Counter> readCounters(XMLStreamReader reader,
            Map<String, Map<CounterEntity, Counter>> modules) throws IOException, XMLStreamException {
        var counters = new EnumMap<CounterEntity, Counter>(CounterEntity.class);
        while (nextChild(reader)) {
            if (modules != null && isUnnamespaced(reader, "group")) {
                readGroup(reader, modules);
                continue;
            }
            if (isUnnamespaced(reader, "counter")) readCounter(reader, counters);
            skipElement(reader);
        }
        complete(counters);
        return counters;
    }

    private static void readGroup(XMLStreamReader reader, Map<String, Map<CounterEntity, Counter>> modules)
            throws IOException, XMLStreamException {
        String name = reader.getAttributeValue(null, "name");
        if (name == null || modules.containsKey(name)) {
            throw new IOException("Invalid or duplicate JaCoCo group: " + name);
        }
        modules.put(name, readCounters(reader, null));
    }

    private static boolean nextChild(XMLStreamReader reader) throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) return true;
            if (event == XMLStreamConstants.END_ELEMENT) return false;
        }
        return false;
    }

    private static void skipElement(XMLStreamReader reader) throws XMLStreamException {
        int depth = 1;
        while (depth > 0) {
            switch (reader.next()) {
                case XMLStreamConstants.START_ELEMENT -> depth++;
                case XMLStreamConstants.END_ELEMENT -> depth--;
                default -> { }
            }
        }
    }

    private static void readCounter(XMLStreamReader reader, Map<CounterEntity, Counter> counters)
            throws IOException {
        String label = reader.getAttributeValue(null, "type");
        try {
            CounterEntity kind = CounterEntity.valueOf(label);
            Counter counter = Counter.read(reader, label);
            if (counters.putIfAbsent(kind, counter) != null) {
                throw new IOException("Duplicate JaCoCo counter: " + label);
            }
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IOException("Invalid JaCoCo counter: " + label, failure);
        }
    }

    private static Map<CounterEntity, Counter> emptyCounters() {
        var empty = new EnumMap<CounterEntity, Counter>(CounterEntity.class);
        complete(empty);
        return empty;
    }

    private static void complete(Map<CounterEntity, Counter> counters) {
        for (CounterEntity kind : CounterEntity.values()) counters.putIfAbsent(kind, Counter.ZERO);
    }

    record Gate(Map<String, JsonNode> modules, JsonNode aggregate) {
        static Gate read(Path root) throws IOException {
            JsonNode policy = COVERAGE_JSON.readTree(root.resolve("config/quality/coverage-policy.json").toFile());
            JsonNode gate = COVERAGE_JSON.readTree(root.resolve("build/reports/quality/coverage-report.json").toFile());
            if (policy == null || gate == null || !CoverageRegression.POLICY_SCHEMA.equals(policy.path("schema").asText())
                    || !CoverageRegression.REPORT_SCHEMA.equals(gate.path("schema").asText())
                    || !"PASSED".equals(gate.path("status").asText())
                    || !policy.path("modules").isObject() || policy.path("modules").isEmpty()
                    || !gate.path("modules").isArray()
                    || !emptyArray(gate, "missingModules") || !emptyArray(gate, "undeclaredModules")
                    || !emptyArray(gate, "violations")) {
                throw new IOException("Coverage policy and successful gate must agree");
            }
            Map<String, JsonNode> rows = new TreeMap<>();
            for (JsonNode row : gate.path("modules")) {
                String name = row.path("module").asText();
                if (!JacocoAggregateReport.validModuleName(name) || rows.putIfAbsent(name, row) != null) {
                    throw new IOException("Invalid or duplicate coverage module: " + name);
                }
            }
            JsonNode declared = policy.path("modules");
            if (rows.size() != declared.size() || !rows.keySet().stream().allMatch(declared::has)) {
                throw new IOException("Coverage policy and JaCoCo report modules must agree");
            }
            return new Gate(rows, gate.path("aggregate"));
        }

        private static boolean emptyArray(JsonNode node, String field) {
            return node.path(field).isArray() && node.path(field).isEmpty();
        }
    }

    private record XmlCounts(Map<CounterEntity, Counter> totals, Map<String, Map<CounterEntity, Counter>> modules) { }
}
