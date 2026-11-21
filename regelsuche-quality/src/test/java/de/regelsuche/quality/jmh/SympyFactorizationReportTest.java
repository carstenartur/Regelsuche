package de.regelsuche.quality.jmh;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SympyFactorizationReportTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURES = Path.of(System.getProperty("regelsuche.repositoryRoot"),
        "regelsuche-quality/src/test/resources/jmh-ci/44712656489");
    @TempDir Path output;

    @Test
    void entireCapturedPythonSummaryIsReproducedIncludingAllCasesAndRatios() throws Exception {
        var summary = output.resolve("summary.json");
        var html = output.resolve("page.html");
        SympyFactorizationReport.main(new String[]{FIXTURES.resolve("sympy-raw.json").toString(),
            summary.toString(), html.toString()});
        assertEquals(JSON.readTree(FIXTURES.resolve("sympy-report.json").toFile()), JSON.readTree(summary.toFile()));
        String page = Files.readString(html);
        assertTrue(page.contains("z-repeated-degree6"));
        assertTrue(page.contains("cpython-one-shot-end-to-end"));
    }

    @Test
    void missingDuplicateWrongSamplingAndNonNumericScoresAreRejected() throws Exception {
        var original = (ArrayNode) JSON.readTree(FIXTURES.resolve("sympy-raw.json").toFile());
        var missing = original.deepCopy();
        missing.remove(0);
        assertThrows(IllegalArgumentException.class, () -> SympyFactorizationReport.evaluate(missing));
        var duplicate = original.deepCopy().add(original.get(0));
        assertThrows(IllegalArgumentException.class, () -> SympyFactorizationReport.evaluate(duplicate));
        var sampling = original.deepCopy();
        ((ObjectNode) sampling.get(0)).put("forks", 0);
        assertThrows(IllegalArgumentException.class, () -> SympyFactorizationReport.evaluate(sampling));
        var malformed = original.deepCopy();
        ((ObjectNode) malformed.get(0).get("primaryMetric")).put("score", "NaN");
        assertThrows(IllegalArgumentException.class, () -> SympyFactorizationReport.evaluate(malformed));
    }
}
