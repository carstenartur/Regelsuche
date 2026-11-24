package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.math.algorithms.iteration.AffineReductionPlan;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

class AlphaAffineComparisonTest {
    private static final Set<String> ADMITTED = Set.of("prefix_sum", "prefix_max", "increasing_max_filter",
        "example_3d_sum", "example_3d_max", "working_example");

    @Test
    void coversSixUnchangedForeignSourcesWithExactlyCheckedValuesThroughTheNativeWorker() throws Exception {
        var panel = ExternalPolyhedralComparison.protocol(checkout());
        var json = new ObjectMapper();
        var admitted = new HashMap<String, Integer>();
        for (var entry : panel.path("cases")) {
            if (!entry.path("origin").asText().equals("UPSTREAM_COMPLETE_INVENTORY")) continue;
            String name = entry.path("id").asText();
            String source = entry.path("source").asText();
            var response = json.valueToTree(AffineAlphaExperiment.nativeRun(source, panel));
            if (!ADMITTED.contains(name)) {
                assertEquals("UNSUPPORTED", response.path("status").asText(), response.toString());
                continue;
            }
            assertEquals("CANDIDATE", response.path("status").asText(), response.toString());
            var imported = AffineAlphaExperiment.importSource(source, true).orElseThrow();
            int outputs = 0;
            for (var tuple : response.path("outputs")) {
                var expected = imported.oracle(tuple.path("rows").intValue(), tuple.path("seed").intValue())
                    .stream().map(java.math.BigInteger::toString).toList();
                assertEquals(json.valueToTree(expected), tuple.path("values"), name);
                outputs += expected.size();
            }
            admitted.put(name, outputs);
            assertTrue(response.path("totalNativeWork").longValue() > 0);
        }
        assertEquals(ADMITTED, admitted.keySet());
        assertEquals(4_416, admitted.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void enumeratesThePublishedTriangleWithoutDroppingOutputCoordinates() throws Exception {
        var panel = ExternalPolyhedralComparison.protocol(checkout());
        var limits = new IterationLimits(100_000, 128, 5_000);
        for (var entry : panel.path("cases")) {
            if (!Set.of("example_3d_sum", "example_3d_max").contains(entry.path("id").asText())) continue;
            var source = AlphaAffineReductionParser.parse(entry.path("source").asText()).source();
            for (int n : new int[]{1, 2, 7, 19}) {
                var expected = new ArrayList<List<Integer>>();
                for (int i = 0; i <= 2 * n; i++) for (int j = 0; j <= 2 * n; j++) {
                    if (j >= n - i && j >= 2 * i - 2 * n && 2 * j <= 2 * n + i)
                        expected.add(List.of(i, j));
                }
                var shape = AffineReductionPlan.prepareShape(source, n, limits, () -> false).shape();
                assertEquals(n + 1, shape.inputSize());
                assertEquals(expected, shape.outputCoordinates());
            }
        }
    }

    @Test
    void rejectsOversizedMessagesBeforeLoadingTheSubprocessRequest(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("source.alpha");
        Path request = directory.resolve("request.json");
        Path response = directory.resolve("response.json");
        Files.writeString(request, " ".repeat(65_537));
        var arguments = new String[]{"--native-affine", source.toString(), request.toString(), response.toString()};
        assertEquals("AFFINE_SUBPROCESS_MESSAGE_LIMIT", assertThrows(IllegalArgumentException.class,
            () -> ExternalPolyhedralComparison.main(arguments)).getMessage());
    }

    @Test
    @EnabledIfSystemProperty(named = "externalAffinePolyhedralHome", matches = ".+")
    void jointlyChecksAllSixCasesAgainstThePinnedOriginalCompiler() throws Exception {
        var output = Path.of(System.getProperty("externalAffinePolyhedralOutput"));
        var report = ExternalPolyhedralComparison.runAffine(checkout(),
            Path.of(System.getProperty("externalAffinePolyhedralHome")), output);
        var rows = (List<?>) report.get("rows");
        assertEquals(20, rows.size());
        var matrix = new ObjectMapper().valueToTree(rows);
        for (String name : ADMITTED) {
            int verified = 0;
            for (var row : matrix) {
                if (name.equals(row.path("case").asText())) {
                    assertEquals("VERIFIED_FINITE_OUTPUTS", row.path("judgment").asText(), row.toString());
                    verified++;
                }
            }
            assertEquals(2, verified, name);
        }
        ExternalPolyhedralComparison.audit(checkout(), output);
    }

    private static Path checkout() {
        return Path.of(System.getProperty("regelsuche.repositoryRoot", ".."));
    }
}
