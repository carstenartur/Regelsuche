package de.regelsuche.quality.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

class JacocoAggregateReportTest {
    @TempDir Path temporary;

    // The same class appears in two modules with opposite probe values.
    // A global merged execution store would incorrectly cover both modules.
    static final class Sample {
        int value(int input) {
            return input > 0 ? 1 : -1;
        }
    }

    @Test
    void standardReportKeepsMeasurementsSeparateAndLinksHighlightedSources() throws Exception {
        Path left = measuredModule("left", true);
        Path right = measuredModule("right", false);
        Path output = temporary.resolve("report");
        Files.createDirectories(output);
        Files.writeString(output.resolve("obsolete.html"), "stale");
        Map<String, Path> inputs = new LinkedHashMap<>();
        inputs.put("left", left);
        inputs.put("right", right);
        JacocoAggregateReport.write(output, inputs);

        Element report = xml(output.resolve("jacoco.xml"));
        var groups = report.getElementsByTagName("group");
        assertEquals(2, groups.getLength());
        Element covered = (Element) groups.item(0);
        Element uncovered = (Element) groups.item(1);
        assertEquals("left", covered.getAttribute("name"));
        assertEquals("right", uncovered.getAttribute("name"));
        for (String kind : new String[]{"INSTRUCTION", "BRANCH", "LINE", "COMPLEXITY", "METHOD", "CLASS"}) {
            assertTrue(count(covered, kind, "covered") > 0, kind);
            assertEquals(0, count(uncovered, kind, "covered"), kind);
            assertEquals(count(covered, kind, "covered"), count(report, kind, "covered"), kind);
            assertEquals(count(uncovered, kind, "missed"), count(report, kind, "missed"), kind);
        }
        String index = Files.readString(output.resolve("index.html"));
        assertTrue(index.contains("Missed Instructions"));
        assertTrue(index.contains("Missed Branches"));
        assertTrue(index.contains("sort.js"));
        assertTrue(index.contains("left/index.html"));
        assertTrue(index.contains("right/index.html"));
        assertFalse(Files.exists(output.resolve("obsolete.html")));
        String packagePath = "left/de.regelsuche.quality.coverage/";
        assertTrue(Files.isRegularFile(output.resolve(packagePath + "index.html")));
        assertTrue(Files.isRegularFile(output.resolve(packagePath + "JacocoAggregateReportTest$Sample.html")));
        String source = Files.readString(output.resolve(packagePath + "JacocoAggregateReportTest.java.html"));
        assertTrue(source.contains("Sample"));
        assertTrue(source.contains("class=\"fc\""), "covered source lines must retain JaCoCo highlighting");
    }

    @Test
    void missingExecutionDataCannotProduceAnApparentlyCompleteReport() throws Exception {
        Path input = measuredModule("missing", true);
        Files.delete(input.resolve("execution-data/test.exec"));
        Path output = temporary.resolve("report");
        assertThrows(IOException.class, () -> JacocoAggregateReport.write(output, Map.of("missing", input)));
        assertFalse(Files.exists(output));
    }

    @Test
    void missingClassesOrSourcesFailBeforePublication() throws Exception {
        Path input = temporary.resolve("empty");
        Files.createDirectories(input);
        assertThrows(IOException.class,
            () -> JacocoAggregateReport.write(temporary.resolve("report"), Map.of("empty", input)));
    }

    @Test
    void emptyAndAmbiguousModuleInventoriesAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> JacocoAggregateReport.write(temporary.resolve("report"), Map.of()));
        assertThrows(IllegalArgumentException.class,
            () -> JacocoAggregateReport.main(new String[]{"out", "one", "first", "one", "second"}));
        assertThrows(IllegalArgumentException.class,
            () -> JacocoAggregateReport.main(new String[]{"out", "../escape", "inputs"}));
        assertThrows(IllegalArgumentException.class,
            () -> JacocoAggregateReport.main(new String[]{"out", "one"}));
    }

    private Path measuredModule(String name, boolean covered) throws Exception {
        Path input = temporary.resolve(name);
        String className = Sample.class.getName().replace('.', '/');
        byte[] bytes;
        try (var resource = Sample.class.getResourceAsStream("/" + className + ".class")) {
            assertNotNull(resource);
            bytes = resource.readAllBytes();
        }
        AtomicReference<ExecutionData> captured = new AtomicReference<>();
        var accessor = new OfflineInstrumentationAccessGenerator();
        new Instrumenter((id, binaryName, probeCount, visitor) -> {
            captured.set(new ExecutionData(id, binaryName, probeCount));
            return accessor.generateDataAccessor(id, binaryName, probeCount, visitor);
        }).instrument(bytes, className);
        ExecutionData data = captured.get();
        assertNotNull(data);
        Arrays.fill(data.getProbes(), covered);
        Path execution = input.resolve("execution-data/test.exec");
        Files.createDirectories(execution.getParent());
        try (var stream = Files.newOutputStream(execution)) {
            var writer = new ExecutionDataWriter(stream);
            writer.visitSessionInfo(new SessionInfo(name, 0, 1));
            writer.visitClassExecution(data);
        }
        Path classes = input.resolve("classes/" + className + ".class");
        Files.createDirectories(classes.getParent());
        Files.write(classes, bytes);
        Path source = input.resolve("sources/de/regelsuche/quality/coverage/JacocoAggregateReportTest.java");
        Files.createDirectories(source.getParent());
        Path repository = Path.of(System.getProperty("regelsuche.repositoryRoot"));
        Files.copy(repository.resolve("regelsuche-quality/src/test/java/de/regelsuche/quality/coverage/JacocoAggregateReportTest.java"), source);
        return input;
    }

    private static Element xml(Path file) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
    }

    private static long count(Element element, String kind, String attribute) {
        for (var child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element counter && counter.getTagName().equals("counter")
                    && counter.getAttribute("type").equals(kind)) {
                return Long.parseLong(counter.getAttribute(attribute));
            }
        }
        return 0;
    }
}
