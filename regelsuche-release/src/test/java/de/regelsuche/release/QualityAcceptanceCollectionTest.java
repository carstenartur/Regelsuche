package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Comparator;
import org.junit.jupiter.api.io.TempDir;

class QualityAcceptanceCollectionTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    void assemble() throws Exception {
        Path checkout = Path.of("..").toAbsolutePath();
        try (var zip = new ZipFile(Path.of(getClass().getResource("/quality-collection/synthetic-reports.zip").toURI()).toFile())) {
            var entries = JSON.readTree(zip.getInputStream(zip.getEntry("inputs.json")));
            for (var input : entries.properties()) {
                Path destination = root.resolve(input.getKey());
                Files.createDirectories(destination.getParent());
                var ref = input.getValue();
                if (ref.has("source")) Files.copy(checkout.resolve(ref.required("source").textValue()), destination);
                else try (var bytes = zip.getInputStream(zip.getEntry("reports/" + ref.required("fixture").textValue()))) {
                    Files.copy(bytes, destination);
                }
            }
        }
    }

    @Test void retainsAllOriginalPolicyAndResultBytesWithoutReevaluatingTheDecisions() throws Exception {
        assemble();
        Path output = Path.of("build/reports/quality/captured");
        var index = QualityAcceptanceCollection.collect(root, output, identity());
        assertEquals("COMPLETE", index.required("completeness").asText(), index.required("problems")::toString);
        for (var entry : index.required("files")) {
            assertEquals(-1, Files.mismatch(root.resolve(entry.required("sourcePath").asText()),
                root.resolve(output).resolve(entry.required("path").asText())));
        }
    }

    @TestFactory List<DynamicTest> replaysAllHistoricalCollectionControls() throws Exception {
        assemble();
        List<DynamicTest> tests = new ArrayList<>();
        Path cases = Path.of(getClass().getResource("/quality-collection/historical-collection-controls.zip").toURI());
        try (var zip = new ZipFile(cases.toFile())) {
            for (var control : JSON.readTree(zip.getInputStream(zip.getEntry("cases.json")))) {
                tests.add(DynamicTest.dynamicTest(control.required("name").asText(), () -> {
                    Path destination = Path.of("build/reports/quality/native-control-output");
                    Map<Path,byte[]> originals = new HashMap<>();
                    try (var changes = new ZipFile(cases.toFile())) {
                        for (var name : control.required("missing")) {
                            Path path = root.resolve(name.textValue());
                            originals.put(path, Files.readAllBytes(path));
                            Files.delete(path);
                        }
                        for (var name : control.required("redirected")) {
                            Path path = root.resolve(name.textValue());
                            originals.put(path, Files.readAllBytes(path));
                            Files.delete(path);
                            Files.createSymbolicLink(path, root.getParent().resolve("unavailable-source"));
                        }
                        for (var entry : control.required("changed").properties()) {
                            Path path = root.resolve(entry.getKey());
                            originals.put(path, Files.readAllBytes(path));
                            try (var bytes = changes.getInputStream(changes.getEntry("changes/" + entry.getValue().textValue()))) {
                                Files.write(path, bytes.readAllBytes());
                            }
                        }
                        var result = QualityAcceptanceCollection.collect(root, destination, identity());
                        assertEquals(control.required("completeness"), result.required("completeness"), result::toString);
                        for (var entry : control.required("sections").properties())
                            assertEquals(entry.getValue(), result.required("sections").required(entry.getKey()).required("availability"), entry.getKey());
                        var outcomes = JSON.createObjectNode();
                        for (var entry : result.required("sections").properties()) {
                            for (var outcome : entry.getValue().required("outcomes"))
                                outcomes.withArray(entry.getKey()).add(outcome.required("value").textValue());
                        }
                        assertEquals(control.required("outcomes"), outcomes);
                    } finally {
                        for (var entry : originals.entrySet()) {
                            Files.deleteIfExists(entry.getKey());
                            Files.write(entry.getKey(), entry.getValue());
                        }
                        if (Files.exists(root.resolve(destination))) {
                            try (var files = Files.walk(root.resolve(destination))) {
                                for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
                            }
                        }
                    }
                }));
            }
        }
        return tests;
    }

    @Test void existingDestinationsAndSymbolicOutputParentsAreRejected() throws Exception {
        Path target = Path.of("build/reports/quality/captured");
        Files.createDirectories(root.resolve(target));
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
            () -> QualityAcceptanceCollection.collect(root, target, identity()));
        Path link = target.resolveSibling("link");
        Files.createSymbolicLink(root.resolve(link), root.getParent());
        assertThrows(IllegalArgumentException.class, () -> QualityAcceptanceCollection.collect(root, link.resolve("escape"), identity()));
    }

    @Test void rejectsAHistoryBenchmarkCountThatWrapsToTheActualCount() throws Exception {
        assemble();
        Path path = root.resolve("build/reports/quality/jmh-history/history.json");
        var history = (ObjectNode) JSON.readTree(path.toFile());
        history.put("benchmarkCount", wrappedCount(history, "benchmarkCount"));
        JSON.writeValue(path.toFile(), history);

        assertUnavailableSection("history");
    }

    @Test void rejectsAVisualBaselineLengthThatWrapsToTheRealLength() throws Exception {
        assemble();
        Path path = root.resolve("app/src/e2eTest/resources/screenshots/visual-regression-policy.json");
        var policy = JSON.readTree(path.toFile());
        var baseline = (ObjectNode) policy.required("baselines").elements().next();
        baseline.put("byteLength", wrappedCount(baseline, "byteLength"));
        JSON.writeValue(path.toFile(), policy);

        assertUnavailableSection("visual");
    }

    private void assertUnavailableSection(String section) throws Exception {
        var captured = QualityAcceptanceCollection.collect(root, Path.of("build/reports/quality/wrapped-count"), identity());
        assertEquals("INCOMPLETE", captured.required("completeness").asText());
        assertEquals("UNAVAILABLE", captured.required("sections").required(section).required("availability").asText());
    }

    private static BigInteger wrappedCount(ObjectNode object, String field) {
        return object.required(field).bigIntegerValue().add(BigInteger.ONE.shiftLeft(Long.SIZE));
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode identity() {
        return JSON.createObjectNode().put("revision", "a".repeat(40)).put("tree", "b".repeat(40)).put("trackedChangesPresent", false);
    }
}
