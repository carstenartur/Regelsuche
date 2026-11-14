package de.regelsuche.quality.jmh;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Publishes the badge and HTML table only after the complete evidence has passed validation. */
public final class JmhPublicationMain {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String HEADER = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        + "<title>Regelsuche JMH Benchmarks</title>"
        + "<style>body{font-family:system-ui,sans-serif;margin:2rem;max-width:1400px}"
        + "table{border-collapse:collapse;width:100%}td,th{border:1px solid #ddd;"
        + "padding:.5rem;text-align:left}th{background:#f6f8fa}code{font-size:.9em}"
        + "</style></head><body><h1>Regelsuche JMH Benchmarks</h1>";
    private static final String COLUMNS = "<table><thead><tr><th>Benchmark</th><th>Mode</th><th>Current</th>"
        + "<th>Unit</th><th>Baseline</th><th>Ratio</th><th>Status</th></tr></thead><tbody>";

    private JmhPublicationMain() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 4) throw new IllegalArgumentException("expected <result> <baseline> <badge> <HTML report>");
        Path input = Path.of(args[0]);
        Path baseline = Path.of(args[1]);
        Path badge = Path.of(args[2]).toAbsolutePath().normalize();
        Path page = Path.of(args[3]).toAbsolutePath().normalize();
        for (Path source : List.of(input, baseline)) {
            requireDistinct(source, badge);
            requireDistinct(source, page);
        }
        requireDistinct(badge, page);
        var report = JmhPublication.verify(read(baseline), read(input));
        String html = HEADER + "<p>" + report.rows().size() + " unit-aware benchmark identities. Baseline revision "
            + "<code>" + escape(report.revision()) + "</code>.</p>" + COLUMNS
            + report.rows().stream().map(JmhPublicationMain::render).collect(Collectors.joining("\n"))
            + "</tbody></table></body></html>\n";
        var status = JSON.createObjectNode().put("schemaVersion", 1).put("label", "performance")
            .put("message", report.rows().size() + " benchmarks stable").put("color", "brightgreen");
        Files.createDirectories(page.getParent());
        Files.createDirectories(badge.getParent());
        Files.writeString(page, html);
        Files.writeString(badge, JSON.writeValueAsString(status) + "\n");
        System.out.println("jmhResult=" + input);
        System.out.println("jmhBaseline=" + baseline);
        System.out.println("benchmarkCount=" + report.rows().size());
        System.out.println("performanceBadge=" + badge);
        System.out.println("performanceReport=" + page);
        System.out.println("jmh-benchmark-contract=valid");
    }

    private static JsonNode read(Path source) throws IOException {
        var node = JSON.readTree(source.toFile());
        if (node == null || node.isMissingNode()) throw new IllegalArgumentException("empty JSON: " + source);
        return node;
    }

    private static void requireDistinct(Path left, Path right) throws IOException {
        if (left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize())
                || (Files.exists(left) && Files.exists(right) && Files.isSameFile(left, right))) {
            throw new IllegalArgumentException("publication paths alias: " + left + ", " + right);
        }
    }

    private static String render(JmhPublication.Row row) {
        double ratio = row.baseline() == 0 ? 0 : row.score() / row.baseline();
        String displayedRatio = fixedRatio(ratio);
        return "<tr><td><code>" + escape(row.benchmark()) + "</code></td><td>avgt</td><td>"
            + significant(row.score(), 6) + " ± " + significant(row.error(), 3) + "</td><td>"
            + escape(row.unit()) + "</td><td>" + significant(row.baseline(), 6) + "</td><td>"
            + displayedRatio + "×</td><td>PASS</td></tr>";
    }

    private static String fixedRatio(double ratio) {
        if (Double.doubleToRawLongBits(ratio) == Long.MIN_VALUE) return "-0.000";
        if (Double.isInfinite(ratio)) return "inf";
        return new BigDecimal(ratio).setScale(3, RoundingMode.HALF_EVEN).toPlainString();
    }

    // Match the original general-format precision, including round-to-even and exponent width.
    private static String significant(double value, int precision) {
        if (Double.doubleToRawLongBits(value) == Long.MIN_VALUE) return "-0";
        BigDecimal rounded = new BigDecimal(value).round(new MathContext(precision, RoundingMode.HALF_EVEN)).stripTrailingZeros();
        int exponent = rounded.precision() - rounded.scale() - 1;
        if (exponent < -4 || exponent >= precision) {
            return rounded.movePointLeft(exponent).stripTrailingZeros().toPlainString()
                + String.format(Locale.ROOT, "e%+03d", exponent);
        }
        return rounded.toPlainString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#x27;");
    }
}
