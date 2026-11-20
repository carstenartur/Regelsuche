package de.regelsuche.quality.jmh;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Validates the diagnostic 6-track control and 9 × 4 capability-matched factorization matrix. */
public final class SympyFactorizationReport {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String SPECIALIST = "de.regelsuche.math.sympy.SymPyFactorizationBenchmarks.";
    private static final String GENERAL = "de.regelsuche.math.sympy.GeneralUnivariateFactorizationBenchmarks.";
    private static final Map<String, String> SPECIALIST_TRACKS = Map.of(
        "nativeBackendWarm", "native-backend-warm", "nativeEndToEndWarm", "native-end-to-end-warm",
        "graalPyBackendWarm", "graalpy-backend-warm", "graalPyEndToEndWarm", "graalpy-end-to-end-warm",
        "graalPyEndToEndCold", "graalpy-end-to-end-cold", "cpythonOneShotEndToEnd", "cpython-one-shot-end-to-end");
    private static final Map<String, String> GENERAL_TRACKS = Map.of(
        "nativeGeneralBackendWarm", "native-general-backend-warm", "nativeGeneralEndToEndWarm", "native-general-end-to-end-warm",
        "graalPyGeneralBackendWarm", "graalpy-general-backend-warm", "graalPyGeneralEndToEndWarm", "graalpy-general-end-to-end-warm");

    private record CorpusCase(String id, String domain, int degree, String density, String coefficients,
                              String reducibility, String multiplicity) {}
    private static final List<CorpusCase> CASES = List.of(
        new CorpusCase("z-linear-pair-degree2", "Z[x]", 2, "dense", "small-integer", "reducible", "square-free"),
        new CorpusCase("z-content-mixed-degree4", "Z[x]", 4, "dense", "integer-content", "reducible", "square-free"),
        new CorpusCase("z-large-coefficient-degree4", "Z[x]", 4, "dense", "larger-integer", "reducible", "square-free"),
        new CorpusCase("z-eisenstein-irreducible-degree5", "Z[x]", 5, "sparse", "small-integer", "irreducible", "square-free"),
        new CorpusCase("z-repeated-degree6", "Z[x]", 6, "dense", "small-integer", "reducible", "repeated"),
        new CorpusCase("z-sparse-cyclotomic-degree6", "Z[x]", 6, "sparse", "small-integer", "reducible", "square-free"),
        new CorpusCase("q-linear-pair-degree2", "Q[x]", 2, "dense", "rational-content", "reducible", "square-free"),
        new CorpusCase("q-eisenstein-irreducible-degree4", "Q[x]", 4, "sparse", "rational", "irreducible", "square-free"),
        new CorpusCase("q-repeated-degree5", "Q[x]", 5, "dense", "rational-content", "reducible", "repeated"));

    private SympyFactorizationReport() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 3) throw new IllegalArgumentException("expected <JMH result> <summary JSON> <HTML report>");
        Path input = Path.of(args[0]);
        Path summary = Path.of(args[1]).toAbsolutePath().normalize();
        Path page = Path.of(args[2]).toAbsolutePath().normalize();
        distinct(input, summary);
        distinct(input, page);
        distinct(summary, page);
        var report = evaluate(JSON.readTree(input.toFile()));
        Files.createDirectories(summary.getParent());
        Files.createDirectories(page.getParent());
        Files.writeString(summary, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        Files.writeString(page, html(report));
    }

    private static void distinct(Path a, Path b) throws IOException {
        require(!a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize())
            && !(Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b)), "Aliased report path: " + b);
    }

    static ObjectNode evaluate(JsonNode raw) {
        require(raw != null && raw.isArray() && !raw.isEmpty(), "Expected a nonempty JMH array");
        var specialist = new TreeMap<String, ObjectNode>();
        var general = new TreeMap<String, Map<String, ObjectNode>>();
        CASES.forEach(c -> general.put(c.id, new TreeMap<>()));
        for (JsonNode row : raw) {
            String name = row.path("benchmark").asText("");
            boolean control = name.startsWith(SPECIALIST);
            require(control || name.startsWith(GENERAL), "Unknown benchmark " + name);
            String track = control ? SPECIALIST_TRACKS.get(name.substring(SPECIALIST.length()))
                : GENERAL_TRACKS.get(name.substring(GENERAL.length()));
            require(track != null, "Undeclared benchmark " + name);
            var tracks = control ? specialist : general.get(row.path("params").path("caseId").textValue());
            require(tracks != null && (control || row.path("params").size() == 1), "Unknown parameters for " + name);
            require(tracks.putIfAbsent(track, measurement(row, name, track, control)) == null, "Duplicate row for " + name);
        }
        require(specialist.size() == SPECIALIST_TRACKS.size(), "Incomplete specialist control");
        ObjectNode report = JSON.createObjectNode().put("schema", "regelsuche.sympy-factorization-performance/v2")
            .put("claimPolicy", "DIAGNOSTIC_TRACKS_NO_RELATIVE_WINNER_GATE");
        var control = report.putObject("specialistControl").put("classification", "SPECIALIZED_BINARY_QUARTIC_CONTROL")
            .put("sharedCase", "binary-homogeneous-quartic-A4-plus-4B4")
            .put("claimBoundary", "Operational specialist control only; not representative of general factorization.");
        control.putArray("measurements").addAll(specialist.values());
        var ratios = control.putObject("ratios");
        ratio(ratios, "graalpyWarmToNativeWarmEndToEnd", specialist, "graalpy-end-to-end-warm", "native-end-to-end-warm");
        ratio(ratios, "graalpyColdToWarmEndToEnd", specialist, "graalpy-end-to-end-cold", "graalpy-end-to-end-warm");
        ratio(ratios, "cpythonOneShotToGraalpyWarmEndToEnd", specialist, "cpython-one-shot-end-to-end", "graalpy-end-to-end-warm");
        var comparison = report.putObject("generalComparison").put("classification", "GENERAL_UNIVARIATE_CAPABILITY_MATCHED")
            .put("corpusId", "regelsuche-general-univariate-factorization-v1").put("caseCount", CASES.size())
            .put("claimBoundary", "Track-scoped engineering claims over this exact Z[x]/Q[x] corpus; no universal CAS ranking.");
        var cases = comparison.putArray("cases");
        for (var c : CASES) {
            var tracks = general.get(c.id);
            require(tracks.size() == GENERAL_TRACKS.size(), "Incomplete tracks for " + c.id);
            var item = cases.addObject().put("caseId", c.id).put("domain", c.domain).put("degree", c.degree)
                .put("density", c.density).put("coefficientClass", c.coefficients).put("reducibility", c.reducibility)
                .put("multiplicity", c.multiplicity);
            item.putArray("measurements").addAll(tracks.values());
            var r = item.putObject("ratios");
            String nb = "native-general-backend-warm", ne = "native-general-end-to-end-warm";
            String gb = "graalpy-general-backend-warm", ge = "graalpy-general-end-to-end-warm";
            ratio(r, "graalpyToNativeWarmBackend", tracks, gb, nb);
            ratio(r, "graalpyToNativeWarmEndToEnd", tracks, ge, ne);
            ratio(r, "nativeVerifierInclusiveToBackend", tracks, ne, nb);
            ratio(r, "graalpyVerifierInclusiveToBackend", tracks, ge, gb);
        }
        return report;
    }

    private static ObjectNode measurement(JsonNode row, String name, String track, boolean control) {
        require("avgt".equals(row.path("mode").textValue()) && count(row, "threads") == 1, "Expected one-thread average time");
        JsonNode metric = row.path("primaryMetric");
        require("ms/op".equals(metric.path("scoreUnit").textValue()), "Expected ms/op for " + name);
        int forks = count(row, "forks"), warmups = count(row, "warmupIterations"), measurements = count(row, "measurementIterations");
        require(control ? forks == 1 && warmups == 2 && measurements == 3 : forks >= 3 && warmups >= 3 && measurements >= 5,
            "Sampling contract changed for " + name);
        return JSON.createObjectNode().put("id", track).put("benchmark", name).put("scoreMillis", number(metric, "score"))
            .put("scoreErrorMillis", number(metric, "scoreError")).put("forks", forks).put("warmupIterations", warmups)
            .put("measurementIterations", measurements);
    }

    private static int count(JsonNode row, String key) {
        JsonNode n = row.path(key);
        require(n.isIntegralNumber() && n.canConvertToInt(), "Expected integer " + key);
        return n.intValue();
    }

    private static double number(JsonNode row, String key) {
        JsonNode n = row.path(key);
        require(n.isNumber() && Double.isFinite(n.doubleValue()) && n.doubleValue() >= 0, "Invalid " + key);
        return n.doubleValue();
    }

    private static void ratio(ObjectNode target, String field, Map<String, ObjectNode> tracks, String a, String b) {
        double denominator = tracks.get(b).get("scoreMillis").doubleValue();
        if (denominator == 0) target.putNull(field);
        else target.put(field, tracks.get(a).get("scoreMillis").doubleValue() / denominator);
    }

    private static String html(ObjectNode report) {
        var html = new StringBuilder("<!doctype html><html lang='en'><meta charset='utf-8'><title>Regelsuche factorization performance</title>"
            + "<h1>Polynomial factorization performance</h1><p>No relative winner gate.</p><h2>General capability-matched comparison</h2>");
        for (var c : report.path("generalComparison").path("cases")) {
            html.append("<h3>").append(c.path("caseId").textValue()).append("</h3>");
            table(html, c.path("measurements"));
        }
        html.append("<h2>Specialized binary-quartic control</h2>");
        table(html, report.path("specialistControl").path("measurements"));
        return html.append("</html>\n").toString();
    }

    private static void table(StringBuilder html, JsonNode rows) {
        html.append("<table><tr><th>track</th><th>ms/op</th><th>error</th><th>forks</th><th>warmups</th><th>measurements</th></tr>");
        for (JsonNode row : rows) {
            html.append("<tr>");
            for (String key : List.of("id", "scoreMillis", "scoreErrorMillis", "forks", "warmupIterations", "measurementIterations")) {
                html.append("<td>").append(row.path(key).asText()).append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</table>");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
}
