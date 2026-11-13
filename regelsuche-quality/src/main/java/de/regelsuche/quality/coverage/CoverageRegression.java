package de.regelsuche.quality.coverage;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Evaluates the checkout policy against canonical JaCoCo XML measurements. */
final class CoverageRegression {
    private static final Path REPORT = Path.of("build/reports/jacoco/test/jacocoTestReport.xml");
    private static final String POLICY_SCHEMA = "regelsuche.quality.coverage-policy/v1";
    private static final String REPORT_SCHEMA = "regelsuche.quality.coverage-report/v1";
    private static final JsonMapper POLICY_JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    private CoverageRegression() { }

    static Result evaluate(Path repository, Path policyPath, List<Path> reports) throws IOException {
        Policy policy = readPolicy(policyPath);
        Map<String, Counters> measured = measure(repository, reports);
        List<String> missing = policy.modules().keySet().stream()
            .filter(module -> !measured.containsKey(module)).toList();
        List<String> undeclared = measured.keySet().stream()
            .filter(module -> !policy.modules().containsKey(module)).toList();
        List<String> violations = new ArrayList<>();
        if (!missing.isEmpty()) {
            violations.add("missing covered modules: " + String.join(", ", missing));
        }
        if (!undeclared.isEmpty()) {
            violations.add("undeclared covered modules: " + String.join(", ", undeclared));
        }

        Counters aggregate = new Counters(Counter.ZERO, Counter.ZERO);
        List<Row> rows = new ArrayList<>();
        for (var entry : measured.entrySet()) {
            String module = entry.getKey();
            Counters counters = entry.getValue();
            aggregate = aggregate.plus(counters);
            Floors floors = policy.modules().getOrDefault(module, Floors.UNDECLARED);
            Row row = new Row(module, counters, floors);
            row.check(violations);
            rows.add(row);
        }
        Row total = new Row("aggregate", aggregate, policy.aggregate());
        total.check(violations);
        return new Result(repository.relativize(policyPath).toString(), policy.ratchet(), total,
            List.copyOf(rows), missing, undeclared, List.copyOf(violations));
    }

    private static Policy readPolicy(Path path) throws IOException {
        JsonNode root;
        try {
            root = POLICY_JSON.readTree(path.toFile());
        } catch (IOException error) {
            throw new IOException("cannot read policy " + path + ": " + error.getMessage(), error);
        }
        if (root == null || !POLICY_SCHEMA.equals(root.path("schema").asText())) {
            throw new IOException("unsupported coverage policy schema");
        }
        JsonNode modules = root.path("modules");
        if (!modules.isObject() || modules.isEmpty()) {
            throw new IOException("policy must declare at least one module");
        }
        JsonNode ratchet = root.path("ratchetPolicy");
        if (!ratchet.isTextual()) {
            throw new IOException("policy ratchetPolicy must be a string");
        }
        Map<String, Floors> floors = new TreeMap<>();
        for (var entry : modules.properties()) {
            floors.put(entry.getKey(), Floors.parse(entry.getValue(), entry.getKey()));
        }
        return new Policy(ratchet.textValue(), Floors.parse(root.path("aggregate"), "aggregate"), floors);
    }

    private static Map<String, Counters> measure(Path repository, List<Path> reports) throws IOException {
        if (reports.isEmpty()) {
            throw new IOException("no JaCoCo XML reports found");
        }
        Map<String, Counters> result = new TreeMap<>();
        for (Path report : reports) {
            String module = repository.relativize(report).getName(0).toString();
            if (result.containsKey(module)) {
                throw new IOException("duplicate JaCoCo report for module " + module);
            }
            result.put(module, readReport(report));
        }
        return result;
    }

    static List<Path> reportPaths(Path repository) throws IOException {
        List<Path> reports;
        try (var paths = Files.list(repository)) {
            reports = new ArrayList<>(paths.sorted().map(path -> path.resolve(REPORT))
                .filter(Files::exists).toList());
        }
        Path rootReport = repository.resolve(REPORT);
        if (Files.exists(rootReport)) {
            reports.add(rootReport);
        }
        return reports;
    }

    private static Counters readReport(Path path) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        Map<String, Counter> counters = new TreeMap<>();
        try (var input = Files.newInputStream(path)) {
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            try {
                readTopLevelCounters(reader, counters);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException error) {
            throw new IOException("cannot parse " + path + ": " + error.getMessage(), error);
        }
        for (String kind : List.of("LINE", "BRANCH")) {
            if (!counters.containsKey(kind)) {
                throw missingCounter(kind);
            }
        }
        return new Counters(counters.get("LINE"), counters.get("BRANCH"));
    }

    private static void readTopLevelCounters(XMLStreamReader reader, Map<String, Counter> counters)
            throws IOException, XMLStreamException {
        int depth = 0;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            } else if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                if (depth == 1 && !isUnnamespaced(reader, "report")) {
                    throw new IOException("JaCoCo XML must have an unnamespaced <report> root");
                }
                if (depth == 2 && isUnnamespaced(reader, "counter")) {
                    String kind = reader.getAttributeValue(XMLConstants.NULL_NS_URI, "type");
                    if ("LINE".equals(kind) || "BRANCH".equals(kind)) {
                        if (counters.containsKey(kind)) {
                            throw missingCounter(kind);
                        }
                        counters.put(kind, Counter.read(reader, kind));
                    }
                }
            }
        }
    }

    private static boolean isUnnamespaced(XMLStreamReader reader, String name) {
        String namespace = reader.getNamespaceURI();
        return (namespace == null || namespace.isEmpty()) && name.equals(reader.getLocalName());
    }

    private static IOException missingCounter(String kind) {
        return new IOException("JaCoCo report must contain one top-level " + kind + " counter");
    }

    private static BigDecimal rounded(double value, int scale) {
        // The decision uses the unrounded ratios. Round only the retained display values.
        return new BigDecimal(value).setScale(scale, RoundingMode.HALF_EVEN);
    }

    private record Counter(BigInteger missed, BigInteger covered) {
        private static final Counter ZERO = new Counter(BigInteger.ZERO, BigInteger.ZERO);

        private static Counter read(XMLStreamReader reader, String kind) throws IOException {
            try {
                BigInteger missed = count(reader, "missed");
                BigInteger covered = count(reader, "covered");
                if (missed.signum() < 0 || covered.signum() < 0) {
                    throw new NumberFormatException("negative count");
                }
                return new Counter(missed, covered);
            } catch (NumberFormatException error) {
                throw new IOException("invalid " + kind + " counter: " + error.getMessage(), error);
            }
        }

        private static BigInteger count(XMLStreamReader reader, String name) {
            String value = reader.getAttributeValue(XMLConstants.NULL_NS_URI, name);
            if (value == null) {
                throw new NumberFormatException("missing " + name + " attribute");
            }
            return new BigInteger(value.strip());
        }

        private Counter plus(Counter other) {
            return new Counter(missed.add(other.missed), covered.add(other.covered));
        }

        private double percent() {
            BigInteger total = missed.add(covered);
            return total.signum() == 0 ? 100.0 : 100.0 * covered.doubleValue() / total.doubleValue();
        }

        private BigDecimal reportPercent() {
            BigDecimal value = rounded(percent() + 1e-12, 4).stripTrailingZeros();
            return value.scale() < 1 ? value.setScale(1) : value;
        }
    }

    private record Counters(Counter line, Counter branch) {
        private Counters plus(Counters other) {
            return new Counters(line.plus(other.line), branch.plus(other.branch));
        }
    }

    private record Policy(String ratchet, Floors aggregate, Map<String, Floors> modules) { }

    private record Floors(JsonNode line, JsonNode branch) {
        private static final Floors UNDECLARED = new Floors(null, null);

        private static Floors parse(JsonNode node, String scope) throws IOException {
            return new Floors(floor(node, scope, "lineMinimumPercent"),
                floor(node, scope, "branchMinimumPercent"));
        }

        private static JsonNode floor(JsonNode node, String scope, String key) throws IOException {
            JsonNode value = node.path(key);
            if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
                throw new IOException(scope + " policy " + key + " must be a finite number");
            }
            return value;
        }
    }

    private record Row(String module, Counters counters, Floors floors) {
        private void check(List<String> violations) {
            check("line", counters.line(), floors.line(), violations);
            check("branch", counters.branch(), floors.branch(), violations);
        }

        private void check(String kind, Counter counter, JsonNode floor, List<String> violations) {
            if (floor != null && counter.percent() + 1e-9 < floor.doubleValue()) {
                violations.add(String.format(Locale.ROOT, "%s %s coverage %s%% is below %s%%",
                    module, kind, rounded(counter.percent(), 4), rounded(floor.doubleValue(), 4)));
            }
        }

        private Map<String, Object> data(boolean includeModule) {
            Map<String, Object> data = new TreeMap<>();
            data.put("line", data(counters.line(), floors.line()));
            data.put("branch", data(counters.branch(), floors.branch()));
            if (includeModule) {
                data.put("module", module);
            }
            return data;
        }

        private static Map<String, Object> data(Counter counter, JsonNode floor) {
            Map<String, Object> data = new TreeMap<>();
            data.put("missed", counter.missed());
            data.put("covered", counter.covered());
            data.put("percent", counter.reportPercent());
            data.put("minimumPercent", floor);
            return data;
        }

        private String markdown() {
            return String.format(Locale.ROOT, "| `%s` | %s%% | %s%% | %s%% | %s%% |", module,
                rounded(counters.line().reportPercent().doubleValue(), 2), floorText(floors.line()),
                rounded(counters.branch().reportPercent().doubleValue(), 2), floorText(floors.branch()));
        }

        private static String floorText(JsonNode floor) {
            return floor == null ? "None" : floor.asText();
        }
    }

    record Result(String policy, String ratchet, Row aggregate, List<Row> modules,
                  List<String> missing, List<String> undeclared, List<String> violations) {
        String status() {
            return violations.isEmpty() ? "PASSED" : "FAILED";
        }

        BigDecimal linePercent() {
            return rounded(aggregate.counters().line().percent(), 4);
        }

        BigDecimal branchPercent() {
            return rounded(aggregate.counters().branch().percent(), 4);
        }

        Map<String, Object> data() {
            return new TreeMap<>(Map.of(
                "schema", REPORT_SCHEMA, "policy", policy, "ratchetPolicy", ratchet, "status", status(),
                "aggregate", aggregate.data(false), "modules", modules.stream().map(row -> row.data(true)).toList(),
                "missingModules", missing, "undeclaredModules", undeclared, "violations", violations));
        }

        String markdown() {
            List<String> lines = new ArrayList<>(List.of("# Coverage regression gate", "",
                "Status: **" + status() + "**", "", "| Module | Lines | Floor | Branches | Floor |",
                "| --- | ---: | ---: | ---: | ---: |"));
            modules.stream().map(Row::markdown).forEach(lines::add);
            lines.addAll(List.of("", String.format(Locale.ROOT, "Aggregate line coverage: **%s%%** (floor %s%%).",
                rounded(aggregate.counters().line().percent(), 2), aggregate.floors().line().asText()),
                String.format(Locale.ROOT, "Aggregate branch coverage: **%s%%** (floor %s%%).",
                    rounded(aggregate.counters().branch().percent(), 2), aggregate.floors().branch().asText()),
                "", "## Ratchet policy", "", ratchet));
            if (!violations.isEmpty()) {
                lines.addAll(List.of("", "## Violations", ""));
                violations.stream().map(violation -> "- " + violation).forEach(lines::add);
            }
            return String.join("\n", lines) + "\n";
        }
    }
}
