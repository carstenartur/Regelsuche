package de.regelsuche.quality.jmh;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Independent Java candidate for the existing Python JMH-v3 decision.
 *
 * <p>This does not replace the live Gradle gate until the reports and all
 * negative controls have been compared. It intentionally reads the immutable
 * v2 thresholds, rather than defining another baseline or tolerance.
 */
public final class JmhRegressionV3Verifier {
    static final String DECISION_SCHEMA =
        "regelsuche.quality.jmh-regression-decision-policy/v3";
    static final String THRESHOLD_SCHEMA =
        "regelsuche.quality.jmh-regression-policy/v2";
    static final String REPORT_SCHEMA =
        "regelsuche.quality.jmh-regression-report/v3";
    static final String STATISTIC = "max(0, currentScore - currentScoreError)";
    static final String FAILURE =
        "decisionScore > maximumAllowedScore";
    static final String INCONCLUSIVE =
        "currentScore > maximumAllowedScore && decisionScore <= maximumAllowedScore";
    static final String PRECISION =
        "currentScoreError >= currentScore";
    static final Set<String> FAMILIES =
        Set.of("CORE", "REWRITE_PROGRAM", "END_TO_END_SEARCH");
    static final Set<String> UNITS = Set.of("us/op", "ms/op");
    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
    );

    public Result verify(
        Path repositoryRoot,
        Path resultPath,
        Path thresholdPath,
        Path decisionPath
    ) throws IOException {
        Path root = repositoryRoot.toRealPath();
        ObjectNode decision = object(read(decisionPath), "decision policy");
        equal(DECISION_SCHEMA, text(decision, "schema"), "decision policy schema");
        equal(THRESHOLD_SCHEMA, text(decision, "thresholdPolicySchema"),
            "threshold policy schema");
        equal(STATISTIC, text(decision, "decisionStatistic"), "decision statistic");
        equal(FAILURE, text(decision, "failureCondition"), "failure condition");
        equal(INCONCLUSIVE, text(decision, "inconclusiveCondition"),
            "inconclusive condition");
        equal("inclusive-pass", text(decision, "boundaryPolicy"),
            "boundary policy");
        equal(PRECISION, text(decision, "lowPrecisionDiagnostic"),
            "low precision diagnostic");
        String claim = text(decision, "claimBoundary");
        String expectedBlob = text(decision, "thresholdPolicyGitBlobSha1");
        require(expectedBlob.matches("[0-9a-f]{40}"), "invalid threshold Git blob");
        Path declared = root.resolve(text(decision, "thresholdPolicyPath"))
            .normalize();
        require(declared.startsWith(root), "threshold path escapes checkout");
        Path supplied = thresholdPath.toRealPath();
        require(supplied.equals(declared.toRealPath()),
            "threshold path differs from declared checkout path");
        require(Files.isRegularFile(supplied, LinkOption.NOFOLLOW_LINKS),
            "threshold path must be a regular file");
        String actualBlob = gitBlobSha1(Files.readAllBytes(supplied));
        equal(expectedBlob, actualBlob, "threshold policy content differs");

        ObjectNode thresholds = object(read(supplied), "threshold policy");
        equal(THRESHOLD_SCHEMA, text(thresholds, "schema"),
            "threshold policy schema");
        ObjectNode execution = object(thresholds.path("execution"),
            "execution policy");
        ArrayNode configured = array(thresholds.path("benchmarks"),
            "threshold benchmarks");
        require(!configured.isEmpty(), "threshold benchmarks are empty");
        Map<String, JsonNode> byName = new TreeMap<>();
        for (JsonNode item : configured) {
            ObjectNode row = object(item, "threshold row");
            String name = text(row, "benchmark");
            require(FAMILIES.contains(text(row, "family")),
                name + ": unknown family");
            require(UNITS.contains(text(row, "unit")),
                name + ": unknown unit");
            double baseline = positive(row.path("baselineScore"),
                name + " baselineScore");
            nonnegative(row.path("baselineScoreError"), name + " baselineScoreError");
            double maximum = positive(row.path("maximumAllowedScore"),
                name + " maximumAllowedScore");
            double multiplier = positive(row.path("maximumMultiplier"),
                name + " maximumMultiplier");
            require(maximum + 1e-12 >= baseline
                && Math.abs(maximum - baseline * multiplier)
                   <= Math.max(1e-9, maximum * 1e-9),
                name + ": inconsistent finite threshold");
            require(byName.putIfAbsent(name, row) == null,
                "duplicate threshold: " + name);
        }

        ArrayNode results = array(read(resultPath), "JMH result");
        Map<String, JsonNode> current = new TreeMap<>();
        for (JsonNode item : results) {
            ObjectNode row = object(item, "JMH row");
            String name = text(row, "benchmark");
            require(current.putIfAbsent(name, row) == null,
                "duplicate result: " + name);
        }
        Set<String> missing = new TreeSet<>(byName.keySet());
        missing.removeAll(current.keySet());
        Set<String> unexpected = new TreeSet<>(current.keySet());
        unexpected.removeAll(byName.keySet());

        List<String> violations = new ArrayList<>();
        List<String> inconclusiveReasons = new ArrayList<>();
        List<String> lowPrecision = new ArrayList<>();
        List<String> inconclusiveNames = new ArrayList<>();
        if (!missing.isEmpty()) {
            violations.add("missing benchmarks: " + String.join(", ", missing));
        }
        if (!unexpected.isEmpty()) {
            violations.add("undeclared benchmarks: "
                + String.join(", ", unexpected));
        }

        JsonNode jdk = execution.path("jdkMajor");
        require(jdk.isIntegralNumber(), "execution.jdkMajor must be integer");
        String jdkMajor = jdk.asText();
        ArrayNode rows = JSON.createArrayNode();
        StringBuilder markdown = new StringBuilder();
        markdown.append("# JMH regression gate v3\n\n");
        for (String name : byName.keySet()) {
            if (!current.containsKey(name)) {
                continue;
            }
            JsonNode policy = byName.get(name);
            JsonNode row = current.get(name);
            JsonNode metric = object(row.path("primaryMetric"),
                name + ": primaryMetric");
            double score = positive(metric.path("score"), name + " score");
            double error = nonnegative(metric.path("scoreError"),
                name + " scoreError");
            String unit = text(metric, "scoreUnit");
            double maximum = policy.path("maximumAllowedScore").doubleValue();
            double baseline = policy.path("baselineScore").doubleValue();
            double decisionScore = Math.max(0, score - error);
            double tolerance = Math.max(1e-12, maximum * 1e-12);
            boolean imprecise = error >= score;
            if (imprecise) {
                lowPrecision.add(name);
            }
            List<String> rowFailures = new ArrayList<>();
            List<String> rowUnknown = new ArrayList<>();
            check(rowFailures, row, execution, "jmhVersion", "JMH version");
            check(rowFailures, row, execution, "mode", "mode");
            check(rowFailures, row, execution, "forks", "forks");
            check(rowFailures, row, execution,
                "warmupIterations", "warmup iterations");
            check(rowFailures, row, execution,
                "measurementIterations", "measurement iterations");
            if (!unit.equals(text(policy, "unit"))) {
                rowFailures.add("unit differs: expected " + text(policy, "unit")
                    + ", found " + unit);
            }
            String actualJdk = row.path("jdkVersion").isMissingNode()
                ? "" : row.path("jdkVersion").asText();
            if (!actualJdk.equals(jdkMajor)
                && !actualJdk.startsWith(jdkMajor + ".")) {
                rowFailures.add("JDK major differs: expected " + jdkMajor
                    + ", found " + actualJdk);
            }
            if (decisionScore > maximum + tolerance) {
                rowFailures.add(String.format(java.util.Locale.ROOT,
                    "decision score %.9f %s (score %.9f - scoreError %.9f) "
                        + "exceeds %.9f %s",
                    decisionScore, unit, score, error, maximum, unit));
            } else if (score > maximum + tolerance) {
                String reason = String.format(java.util.Locale.ROOT,
                    "point estimate %.9f %s exceeds %.9f %s, but decision "
                        + "score %.9f %s does not; measurement is inconclusive "
                        + "and requires fresh evidence",
                    score, unit, maximum, unit, decisionScore, unit);
                rowUnknown.add(reason);
                inconclusiveNames.add(name);
                inconclusiveReasons.add(name + ": " + reason);
            }
            for (String failure : rowFailures) {
                violations.add(name + ": " + failure);
            }
            String status = !rowFailures.isEmpty() ? "FAILED"
                : !rowUnknown.isEmpty() ? "INCONCLUSIVE" : "PASSED";
            ObjectNode result = rows.addObject();
            result.put("benchmark", name);
            result.put("family", text(policy, "family"));
            result.put("unit", unit);
            result.put("baselineScore", baseline);
            result.put("baselineScoreError",
                policy.path("baselineScoreError").doubleValue());
            result.put("currentScore", score);
            result.put("currentScoreError", error);
            result.put("decisionScore", decisionScore);
            result.put("maximumAllowedScore", maximum);
            result.put("rawRegressionPercent", round6(100 * (score / baseline - 1)));
            result.put("decisionRegressionPercent",
                round6(100 * (decisionScore / baseline - 1)));
            result.put("precisionStatus", imprecise ? "LOW_PRECISION" : "MEASURED");
            result.put("status", status);
            setArray(result.putArray("violations"), rowFailures);
            setArray(result.putArray("inconclusiveReasons"), rowUnknown);
        }

        String status = !violations.isEmpty() ? "FAILED"
            : !inconclusiveReasons.isEmpty() ? "INCONCLUSIVE" : "PASSED";
        ObjectNode report = JSON.createObjectNode();
        report.put("schema", REPORT_SCHEMA);
        report.put("decisionPolicy", decisionPath.toString());
        report.put("decisionPolicySchema", DECISION_SCHEMA);
        report.put("thresholdPolicy", thresholdPath.toString());
        report.put("thresholdPolicySchema", THRESHOLD_SCHEMA);
        report.put("thresholdPolicyGitBlobSha1", actualBlob);
        report.set("baselineRevision", thresholds.path("baselineRevision"));
        report.set("baselineArtifactDigest",
            thresholds.path("baselineArtifactDigest"));
        report.put("claimBoundary", claim);
        report.put("decisionStatistic", STATISTIC);
        report.put("failureCondition", FAILURE);
        report.put("inconclusiveCondition", INCONCLUSIVE);
        report.put("boundaryPolicy", "inclusive-pass");
        report.put("lowPrecisionDiagnostic", PRECISION);
        report.put("status", status);
        report.put("benchmarkCount", rows.size());
        report.put("lowPrecisionCount", lowPrecision.size());
        setArray(report.putArray("lowPrecisionBenchmarks"), lowPrecision);
        report.put("inconclusiveCount", inconclusiveNames.size());
        setArray(report.putArray("inconclusiveBenchmarks"), inconclusiveNames);
        setArray(report.putArray("missingBenchmarks"), missing);
        setArray(report.putArray("unexpectedBenchmarks"), unexpected);
        report.set("benchmarks", rows);
        setArray(report.putArray("violations"), violations);
        setArray(report.putArray("inconclusiveReasons"), inconclusiveReasons);

        markdown.append("Status: **").append(status).append("**\n\n")
            .append(claim).append("\n\n")
            .append("The frozen v2 benchmark inventory and finite thresholds "
                + "remain unchanged and content-bound.\n\n")
            .append("Decision statistic: `").append(STATISTIC).append("`.\n")
            .append(" Failure condition: `").append(FAILURE)
                .append("`; the exact boundary passes.\n")
            .append(" Inconclusive condition: `").append(INCONCLUSIVE)
                .append("`; inconclusive evidence fails closed.\n")
            .append(" Low precision diagnostic: `").append(PRECISION)
                .append("`.\n\n")
            .append("Low-precision measurements: **").append(lowPrecision.size())
                .append("**.\n")
            .append("Inconclusive measurements: **").append(inconclusiveNames.size())
                .append("**.\n\n")
            .append("| Benchmark | Unit | Baseline | Current ± error | Decision "
                + "| Maximum | Raw change | Precision | Status |\n")
            .append("| --- | --- | ---: | ---: | ---: | ---: | ---: | --- | --- |\n");
        for (JsonNode row : rows) {
            markdown.append(String.format(java.util.Locale.ROOT,
                "| `%s` | `%s` | %.6f | %.6f ± %.6f | %.6f | %.6f "
                    + "| %+.2f%% | %s | %s |\n",
                row.path("benchmark").asText(),
                row.path("unit").asText(),
                row.path("baselineScore").doubleValue(),
                row.path("currentScore").doubleValue(),
                row.path("currentScoreError").doubleValue(),
                row.path("decisionScore").doubleValue(),
                row.path("maximumAllowedScore").doubleValue(),
                row.path("rawRegressionPercent").doubleValue(),
                row.path("precisionStatus").asText(),
                row.path("status").asText()));
        }
        appendReasons(markdown, "Inconclusive measurements", inconclusiveReasons);
        appendReasons(markdown, "Violations", violations);
        return new Result(status, report, markdown.toString());
    }

    public record Result(String status, ObjectNode json, String markdown) {
        public void write(Path jsonPath, Path markdownPath) throws IOException {
            Files.createDirectories(jsonPath.toAbsolutePath().getParent());
            Files.createDirectories(markdownPath.toAbsolutePath().getParent());
            Files.writeString(jsonPath,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(json)
                    + "\n", StandardCharsets.UTF_8);
            Files.writeString(markdownPath, markdown, StandardCharsets.UTF_8);
        }
    }

    private static void appendReasons(StringBuilder text, String title,
                                      List<String> reasons) {
        if (!reasons.isEmpty()) {
            text.append("\n## ").append(title).append("\n\n");
            for (String reason : reasons) {
                text.append("- ").append(reason).append("\n");
            }
        }
    }

    private static void setArray(ArrayNode into, Iterable<String> items) {
        for (String item : items) {
            into.add(item);
        }
    }

    private static double round6(double value) {
        return Math.rint(value * 1_000_000d) / 1_000_000d;
    }

    private static void check(List<String> failures, JsonNode row,
                              JsonNode expected, String field, String label) {
        JsonNode actual = row.path(field);
        JsonNode required = expected.path(field);
        if (!actual.equals(required)) {
            failures.add(label + " differs: expected " + required.asText()
                + ", found " + (actual.isMissingNode() ? "None" : actual.asText()));
        }
    }

    private static ObjectNode object(JsonNode value, String label) {
        require(value.isObject(), label + " must be an object");
        return (ObjectNode) value;
    }

    private static ArrayNode array(JsonNode value, String label) {
        require(value.isArray(), label + " must be an array");
        return (ArrayNode) value;
    }

    private static String text(JsonNode value, String name) {
        JsonNode field = value.path(name);
        require(field.isTextual() && !field.asText().isBlank(),
            name + " must be nonempty text");
        return field.asText();
    }

    private static double positive(JsonNode value, String label) {
        double number = nonnegative(value, label);
        require(number > 0, label + " must be positive");
        return number;
    }

    private static double nonnegative(JsonNode value, String label) {
        require(value.isNumber(), label + " must be numeric");
        double number = value.doubleValue();
        require(Double.isFinite(number) && number >= 0,
            label + " must be finite and nonnegative");
        return number;
    }

    private static JsonNode read(Path path) throws IOException {
        try (JsonParser parser = JSON.getFactory().createParser(path.toFile())) {
            JsonNode value = JSON.readTree(parser);
            require(value != null, "empty JSON: " + path);
            require(parser.nextToken() == null, "trailing JSON: " + path);
            return value;
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(
                "invalid JSON " + path + ": " + failure.getOriginalMessage(),
                failure
            );
        }
    }

    private static String gitBlobSha1(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0")
                .getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void equal(Object expected, Object actual, String label) {
        require(expected.equals(actual), label + ": expected " + expected
            + ", found " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("JMH v3 Java gate: " + message);
        }
    }
}
