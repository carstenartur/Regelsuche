package de.regelsuche.quality.jmh;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JmhPublicationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of(System.getProperty("regelsuche.repositoryRoot"));
    static final String BASELINE = "config/quality/jmh-baseline-more-warmup-v1.json";
    @TempDir Path temp;

    // Same six-warmup rows used by the former Python publisher's policy test.
    private ArrayNode measurements() throws IOException {
        var historical = JSON.readTree(ROOT.resolve("config/quality/jmh-regression-policy-v2.json").toFile());
        var rows = JSON.createArrayNode();
        for (var reference : historical.required("benchmarks")) {
            var row = rows.addObject().put("benchmark", reference.required("benchmark").textValue())
                .put("mode", "avgt").put("forks", 1).put("threads", 1).put("warmupIterations", 6)
                .put("measurementIterations", 3).put("warmupBatchSize", 1).put("measurementBatchSize", 1)
                .put("warmupTime", "1 s").put("measurementTime", "1 s");
            double score = reference.required("baselineScore").doubleValue();
            row.putObject("primaryMetric").put("score", score / 2).put("scoreError", score / 100)
                .put("scoreUnit", reference.required("unit").textValue());
        }
        return rows;
    }

    private void publish(ArrayNode rows) throws IOException {
        Path input = temp.resolve("result.json");
        JSON.writeValue(input.toFile(), rows);
        JmhPublicationMain.main(new String[] {input.toString(), ROOT.resolve(BASELINE).toString(),
            temp.resolve("badge.json").toString(), temp.resolve("index.html").toString()});
    }

    private String sha256(String name) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(temp.resolve(name))));
    }

    @Test
    void sameTwentyNineRowsProduceByteIdenticalLegacyBadgeAndPage() throws Exception {
        publish(measurements());
        assertEquals("9bb9c81daef963805eed5f5c4ee63947ececbe84edccffe023f2aecbab8d311f", sha256("badge.json"));
        assertEquals("afad5ebcba33b42c790d8871f1963c3e4b3613bf423d39cc016cd1ee3142ec81", sha256("index.html"));
    }

    static Stream<Consumer<ObjectNode>> invalidRows() {
        return Stream.of(r -> r.put("warmupIterations", 2), r -> r.put("measurementIterations", 2),
            r -> r.put("warmupTime", "2 s"), r -> r.put("measurementTime", "2 s"), r -> r.put("mode", "thrpt"),
            r -> r.put("threads", 2), r -> r.put("forks", 2), r -> r.put("warmupBatchSize", 2),
            r -> r.put("measurementBatchSize", 2), r -> r.put("benchmark", "unknown"), r -> r.putObject("params").put("p", "1"),
            r -> r.withObjectProperty("primaryMetric").put("scoreUnit", "ns/op"),
            r -> r.withObjectProperty("primaryMetric").put("score", -1),
            r -> r.withObjectProperty("primaryMetric").put("scoreError", "NaN"),
            r -> r.withObjectProperty("primaryMetric").put("score", 1e100));
    }

    @ParameterizedTest
    @MethodSource("invalidRows")
    void invalidMeasurementsFailBeforePublishing(Consumer<ObjectNode> change) throws IOException {
        var rows = measurements();
        change.accept((ObjectNode) rows.get(0));
        assertThrows(IllegalArgumentException.class, () -> publish(rows));
        assertFalse(Files.exists(temp.resolve("badge.json")));
        assertFalse(Files.exists(temp.resolve("index.html")));
    }

    @Test
    void preservesScientificNotationAndSignedZeroInThePublishedPage() throws IOException {
        var rows = measurements();
        ((ObjectNode) rows.get(0)).withObjectProperty("primaryMetric").put("score", -0.0d).put("scoreError", 1e-5);
        publish(rows);
        String page = Files.readString(temp.resolve("index.html"));
        assertTrue(page.contains("<td>-0 ± 1e-05</td>"));
        assertTrue(page.contains("<td>-0.000×</td>"));
    }

    @Test
    void requiresCompleteUniqueInventory() throws IOException {
        var rows = measurements();
        var removed = rows.remove(0);
        assertThrows(IllegalArgumentException.class, () -> publish(rows));
        rows.add(removed).add(removed);
        assertThrows(IllegalArgumentException.class, () -> publish(rows));
    }

    @Test
    void confidenceLowerBoundEqualToCeilingPassesAndNextDoubleFails() throws IOException {
        var baseline = JSON.readTree(ROOT.resolve(BASELINE).toFile());
        var rows = measurements();
        var row = (ObjectNode) rows.get(0);
        double mean = baseline.required("benchmarks").required(row.required("benchmark").textValue()).required("baselineScore").doubleValue();
        double ceiling = mean * baseline.required("measurementPolicy").required("materialRegressionRatio").doubleValue();
        var metric = row.withObjectProperty("primaryMetric");
        metric.put("score", 2 * ceiling).put("scoreError", ceiling);
        publish(rows);
        metric.put("score", Math.nextUp(2 * ceiling));
        assertThrows(IllegalArgumentException.class, () -> publish(rows));
    }

    @Test
    void rejectsAliasedOutputsAndMalformedPolicy() throws IOException {
        var input = temp.resolve("result.json");
        JSON.writeValue(input.toFile(), measurements());
        var policy = temp.resolve("policy.json");
        Files.copy(ROOT.resolve(BASELINE), policy);
        var args = new String[] {input.toString(), policy.toString(), policy.toString(), temp.resolve("index.html").toString()};
        assertThrows(IllegalArgumentException.class, () -> JmhPublicationMain.main(args));
        args[2] = temp.resolve("badge.json").toString();
        var invalid = (ObjectNode) JSON.readTree(policy.toFile());
        invalid.withObjectProperty("measurementPolicy").put("materialRegressionRatio", 1.0);
        JSON.writeValue(policy.toFile(), invalid);
        assertThrows(IllegalArgumentException.class, () -> JmhPublicationMain.main(args));
        assertFalse(Files.exists(temp.resolve("badge.json")));
    }
}
