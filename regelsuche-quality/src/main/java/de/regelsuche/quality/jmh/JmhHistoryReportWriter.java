package de.regelsuche.quality.jmh;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class JmhHistoryReportWriter {
    static final String OUTPUT_SCHEMA =
        "regelsuche.quality.jmh-history/v1";

    private final ObjectMapper mapper;

    JmhHistoryReportWriter() {
        mapper = new ObjectMapper();
        mapper.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    void write(JmhHistory history, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path charts = outputDirectory.resolve("charts");
        Files.createDirectories(charts);
        Map<String, String> chartPaths = writeCharts(history, charts);
        writeJson(history, chartPaths, outputDirectory.resolve("history.json"));
        writeMarkdown(
            history,
            chartPaths,
            outputDirectory.resolve("history.md")
        );
    }

    private Map<String, String> writeCharts(
        JmhHistory history,
        Path charts
    ) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (String benchmark : history.benchmarks().keySet().stream()
                .sorted().toList()) {
            String fileName = safeFileName(benchmark) + ".svg";
            writeSvg(history, benchmark, charts.resolve(fileName));
            result.put(benchmark, "charts/" + fileName);
        }
        return Map.copyOf(result);
    }

    private void writeJson(
        JmhHistory history,
        Map<String, String> chartPaths,
        Path destination
    ) throws IOException {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schema", OUTPUT_SCHEMA);
        document.put("status", "PASSED");
        document.put("claimBoundary", history.claimBoundary());
        document.put("normalizedUnit", "ms/op");
        document.put("lowerIsBetter", true);
        document.put("historyPolicyDigest", history.historyPolicyDigest());
        document.put("regressionPolicyDigest", history.regressionPolicyDigest());
        document.put("snapshotCount", history.snapshots().size());
        document.put("benchmarkCount", history.benchmarks().size());
        document.put("snapshots", snapshotDocuments(history));
        document.put("benchmarks", benchmarkDocuments(history, chartPaths));
        byte[] json = mapper.writerWithDefaultPrettyPrinter()
            .writeValueAsBytes(document);
        Files.write(destination, newlineTerminated(json));
    }

    private List<Map<String, Object>> snapshotDocuments(JmhHistory history) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JmhHistory.Snapshot snapshot : history.snapshots()) {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("label", snapshot.label());
            document.put("recordedAt", snapshot.recordedAt());
            document.put("sourceRevision", snapshot.sourceRevision());
            document.put(
                "sourceArtifactDigest",
                snapshot.sourceArtifactDigest()
            );
            document.put("snapshotPath", snapshot.snapshotPath());
            document.put("snapshotDigest", snapshot.snapshotDigest());
            result.add(document);
        }
        return List.copyOf(result);
    }

    private List<Map<String, Object>> benchmarkDocuments(
        JmhHistory history,
        Map<String, String> chartPaths
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String benchmark : history.benchmarks().keySet().stream()
                .sorted().toList()) {
            JmhHistory.BenchmarkContract contract =
                history.benchmarks().get(benchmark);
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("benchmark", benchmark);
            document.put("family", contract.family());
            document.put("sourceUnit", contract.sourceUnit());
            document.put("chart", chartPaths.get(benchmark));
            document.put("points", points(history, benchmark));
            result.add(document);
        }
        return List.copyOf(result);
    }

    private List<Map<String, Object>> points(
        JmhHistory history,
        String benchmark
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JmhHistory.Snapshot snapshot : history.snapshots()) {
            JmhHistory.Measurement measurement =
                snapshot.measurements().get(benchmark);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("label", snapshot.label());
            point.put("recordedAt", snapshot.recordedAt());
            point.put("sourceRevision", snapshot.sourceRevision());
            point.put("scoreMsPerOp", measurement.scoreMsPerOp());
            point.put(
                "scoreErrorMsPerOp",
                measurement.scoreErrorMsPerOp()
            );
            result.add(point);
        }
        return List.copyOf(result);
    }

    private void writeMarkdown(
        JmhHistory history,
        Map<String, String> chartPaths,
        Path destination
    ) throws IOException {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# JMH benchmark history\n\n")
            .append(history.claimBoundary()).append("\n\n")
            .append("All values use **ms/op**. Lower values and lower chart ")
            .append("points are faster/better. JMH `scoreError` remains ")
            .append("visible in the SVG error bars.\n\n")
            .append("## Retained snapshots\n\n")
            .append("| Snapshot | Recorded at | Source revision | Snapshot digest |\n")
            .append("| --- | --- | --- | --- |\n");
        for (JmhHistory.Snapshot snapshot : history.snapshots()) {
            markdown.append("| ").append(snapshot.label()).append(" | ")
                .append(snapshot.recordedAt()).append(" | `")
                .append(snapshot.sourceRevision()).append("` | `")
                .append(snapshot.snapshotDigest()).append("` |\n");
        }
        for (String family : families(history)) {
            appendFamily(markdown, history, chartPaths, family);
        }
        Files.writeString(
            destination,
            markdown.toString(),
            StandardCharsets.UTF_8
        );
    }

    private void appendFamily(
        StringBuilder markdown,
        JmhHistory history,
        Map<String, String> chartPaths,
        String family
    ) {
        markdown.append("\n## ").append(family).append("\n\n| Benchmark |");
        for (JmhHistory.Snapshot snapshot : history.snapshots()) {
            markdown.append(' ').append(snapshot.label()).append(" |");
        }
        markdown.append(" Change vs first | Chart |\n| --- |");
        history.snapshots().forEach(ignored -> markdown.append(" ---: |"));
        markdown.append(" ---: | --- |\n");
        for (String benchmark : benchmarksInFamily(history, family)) {
            appendBenchmarkRow(markdown, history, chartPaths, benchmark);
        }
    }

    private void appendBenchmarkRow(
        StringBuilder markdown,
        JmhHistory history,
        Map<String, String> chartPaths,
        String benchmark
    ) {
        List<Double> values = history.snapshots().stream()
            .map(snapshot -> snapshot.measurements().get(benchmark)
                .scoreMsPerOp())
            .toList();
        double first = values.getFirst();
        double last = values.getLast();
        double change = first == 0.0d
            ? 0.0d
            : (last / first - 1.0d) * 100.0d;
        markdown.append("| `").append(benchmark).append("` |");
        values.forEach(value -> markdown.append(' ')
            .append(format(value)).append(" |"));
        markdown.append(' ')
            .append(String.format(Locale.ROOT, "%+.2f%%", change))
            .append(" | [SVG](").append(chartPaths.get(benchmark))
            .append(") |\n");
    }

    private void writeSvg(
        JmhHistory history,
        String benchmark,
        Path destination
    ) throws IOException {
        List<JmhHistory.Measurement> values = history.snapshots().stream()
            .map(snapshot -> snapshot.measurements().get(benchmark))
            .toList();
        SvgGeometry geometry = SvgGeometry.forMeasurements(values);
        StringBuilder svg = svgHeader(benchmark, geometry);
        appendGrid(svg, geometry);
        appendSeries(svg, history, values, geometry);
        svg.append("</svg>\n");
        Files.writeString(destination, svg.toString(), StandardCharsets.UTF_8);
    }

    private StringBuilder svgHeader(
        String benchmark,
        SvgGeometry geometry
    ) {
        return new StringBuilder()
            .append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
            .append("viewBox=\"0 0 960 360\" role=\"img\" ")
            .append("aria-labelledby=\"title description\">\n")
            .append("<style>text{font-family:system-ui,sans-serif;")
            .append("fill:currentColor}.axis{font-size:12px}")
            .append(".title{font-size:17px;font-weight:600}")
            .append(".point{fill:currentColor}</style>\n")
            .append("<title id=\"title\">").append(xml(benchmark))
            .append("</title>\n")
            .append("<desc id=\"description\">Historical mean runtime in ")
            .append("milliseconds per operation. Lower points are faster and ")
            .append("better.</desc>\n")
            .append("<text class=\"title\" x=\"100\" y=\"27\">")
            .append(xml(benchmark)).append("</text>\n")
            .append("<text x=\"100\" y=\"49\">ms/op — lower is ")
            .append("faster/better; error bars show JMH scoreError</text>\n")
            .append(line(
                geometry.left(),
                geometry.top(),
                geometry.left(),
                geometry.bottom(),
                1.0d
            ))
            .append(line(
                geometry.left(),
                geometry.bottom(),
                geometry.right(),
                geometry.bottom(),
                1.0d
            ));
    }

    private void appendGrid(StringBuilder svg, SvgGeometry geometry) {
        for (int tick = 0; tick < 5; tick++) {
            double value = geometry.maximum() * tick / 4.0d;
            double y = geometry.y(value);
            svg.append(line(
                geometry.left() - 5.0d,
                y,
                geometry.right(),
                y,
                0.18d
            )).append("<text class=\"axis\" x=\"91\" y=\"")
                .append(two(y + 4.0d))
                .append("\" text-anchor=\"end\">")
                .append(format(value)).append("</text>\n");
        }
    }

    private void appendSeries(
        StringBuilder svg,
        JmhHistory history,
        List<JmhHistory.Measurement> values,
        SvgGeometry geometry
    ) {
        svg.append("<polyline points=\"");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                svg.append(' ');
            }
            svg.append(two(geometry.x(index, values.size())))
                .append(',')
                .append(two(geometry.y(values.get(index).scoreMsPerOp())));
        }
        svg.append("\" fill=\"none\" stroke=\"currentColor\" ")
            .append("stroke-width=\"2\"/>\n");
        for (int index = 0; index < values.size(); index++) {
            appendPoint(
                svg,
                history.snapshots().get(index),
                values.get(index),
                geometry.x(index, values.size()),
                geometry
            );
        }
    }

    private void appendPoint(
        StringBuilder svg,
        JmhHistory.Snapshot snapshot,
        JmhHistory.Measurement value,
        double x,
        SvgGeometry geometry
    ) {
        double center = geometry.y(value.scoreMsPerOp());
        double upper = geometry.y(
            value.scoreMsPerOp() + value.scoreErrorMsPerOp()
        );
        double lower = geometry.y(Math.max(
            0.0d,
            value.scoreMsPerOp() - value.scoreErrorMsPerOp()
        ));
        svg.append(line(x, upper, x, lower, 1.0d))
            .append(line(x - 5.0d, upper, x + 5.0d, upper, 1.0d))
            .append(line(x - 5.0d, lower, x + 5.0d, lower, 1.0d))
            .append("<circle class=\"point\" cx=\"").append(two(x))
            .append("\" cy=\"").append(two(center))
            .append("\" r=\"4\"/>\n")
            .append("<text class=\"axis\" x=\"").append(two(x))
            .append("\" y=\"307\" text-anchor=\"middle\">")
            .append(snapshot.recordedAt(), 0, 10)
            .append("</text>\n")
            .append("<text class=\"axis\" x=\"").append(two(x))
            .append("\" y=\"325\" text-anchor=\"middle\">")
            .append(format(value.scoreMsPerOp()))
            .append("</text>\n");
    }

    private static List<String> families(JmhHistory history) {
        return history.benchmarks().values().stream()
            .map(JmhHistory.BenchmarkContract::family)
            .distinct()
            .sorted()
            .toList();
    }

    private static List<String> benchmarksInFamily(
        JmhHistory history,
        String family
    ) {
        return history.benchmarks().entrySet().stream()
            .filter(entry -> family.equals(entry.getValue().family()))
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    }

    private static byte[] newlineTerminated(byte[] value) {
        byte[] result = new byte[value.length + 1];
        System.arraycopy(value, 0, result, 0, value.length);
        result[result.length - 1] = '\n';
        return result;
    }

    private static String safeFileName(String value) {
        return value.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+|-+$)", "");
    }

    private static String line(
        double x1,
        double y1,
        double x2,
        double y2,
        double opacity
    ) {
        return "<line x1=\"" + two(x1) + "\" y1=\"" + two(y1)
            + "\" x2=\"" + two(x2) + "\" y2=\"" + two(y2)
            + "\" stroke=\"currentColor\" stroke-width=\"1\" "
            + "stroke-opacity=\"" + two(opacity) + "\"/>\n";
    }

    private static String two(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String format(double value) {
        double absolute = Math.abs(value);
        if (value == 0.0d) {
            return "0";
        }
        if (absolute < 0.001d || absolute >= 10_000.0d) {
            return String.format(Locale.ROOT, "%.4e", value);
        }
        int decimals = absolute < 0.1d ? 6 : absolute < 10.0d ? 4 : 3;
        return strip(String.format(Locale.ROOT, "%." + decimals + "f", value));
    }

    private static String strip(String value) {
        return value.indexOf('.') < 0
            ? value
            : value.replaceFirst("0+$", "").replaceFirst("\\.$", "");
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }

    private record SvgGeometry(
        double left,
        double top,
        double width,
        double height,
        double maximum
    ) {
        static SvgGeometry forMeasurements(
            List<JmhHistory.Measurement> measurements
        ) {
            double maximum = measurements.stream()
                .mapToDouble(value -> value.scoreMsPerOp()
                    + value.scoreErrorMsPerOp())
                .max()
                .orElse(1.0d);
            if (maximum <= 0.0d) {
                maximum = 1.0d;
            }
            return new SvgGeometry(100.0d, 70.0d, 830.0d, 215.0d,
                maximum * 1.10d);
        }

        double right() {
            return left + width;
        }

        double bottom() {
            return top + height;
        }

        double x(int index, int count) {
            return count == 1
                ? left + width / 2.0d
                : left + width * index / (count - 1.0d);
        }

        double y(double value) {
            double bounded = Math.max(0.0d, Math.min(value, maximum));
            return top + height * (1.0d - bounded / maximum);
        }
    }
}
