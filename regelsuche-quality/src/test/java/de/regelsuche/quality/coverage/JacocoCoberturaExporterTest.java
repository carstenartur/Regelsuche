package de.regelsuche.quality.coverage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

class JacocoCoberturaExporterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void exportsSourceLinesOnceWithIndependentTotals(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        // A has two covered/one missed line, B one covered/one missed line.
        // A has one covered/three missed branches, B two covered/no missed branches.
        var result = assertDoesNotThrow(() -> fixture.export(root.resolve("output")));
        Element coverage = validateCobertura(result.report());
        assertEquals("3", coverage.getAttribute("lines-covered"));
        assertEquals("5", coverage.getAttribute("lines-valid"));
        assertEquals("0.6", coverage.getAttribute("line-rate"));
        assertEquals("3", coverage.getAttribute("branches-covered"));
        assertEquals("6", coverage.getAttribute("branches-valid"));
        assertEquals("0.5", coverage.getAttribute("branch-rate"));
        // Independent complexity totals: A=2 covered+3 missed, B=3 covered+1 missed.
        assertEquals("9", coverage.getAttribute("complexity"));
        var packages = coverage.getElementsByTagName("package");
        assertEquals("5", ((Element) packages.item(0)).getAttribute("complexity"));
        assertEquals("4", ((Element) packages.item(1)).getAttribute("complexity"));
        var classes = coverage.getElementsByTagName("class");
        assertEquals(2, classes.getLength(), "nested class counters must not duplicate source lines");
        assertEquals("module-a/src/main/java/p/A.java", ((Element) classes.item(0)).getAttribute("filename"));
        assertEquals("module-b/src/main/java/p/A.java", ((Element) classes.item(1)).getAttribute("filename"));
        assertEquals("5", ((Element) classes.item(0)).getAttribute("complexity"));
        assertEquals("4", ((Element) classes.item(1)).getAttribute("complexity"));
        var lines = coverage.getElementsByTagName("line");
        assertEquals(5, lines.getLength());
        int[] numbers = {1, 2, 4, 2, 3};
        String[] hits = {"1", "1", "0", "1", "0"};
        for (int index = 0; index < numbers.length; index++) {
            Element line = (Element) lines.item(index);
            assertEquals(Integer.toString(numbers[index]), line.getAttribute("number"));
            assertEquals(hits[index], line.getAttribute("hits"), "hits are binary coverage, not instruction counts");
        }
        assertEquals("50% (1/2)", ((Element) lines.item(1)).getAttribute("condition-coverage"));
        assertEquals("0% (0/2)", ((Element) lines.item(2)).getAttribute("condition-coverage"));
        assertEquals(3, result.coveredLines());
        assertEquals(5, result.validLines());
        assertEquals(sha256(Files.readAllBytes(result.report())), result.reportSha256());
        var manifest = JSON.readTree(result.manifest().toFile());
        assertEquals(result.reportSha256(), manifest.path("reportSha256").asText());
        assertEquals(2, manifest.path("modules").size());
        assertEquals(2, manifest.path("sources").size());
        assertEquals(9, manifest.path("complexity").asLong());
        assertEquals(5, manifest.path("sources").get(0).path("complexity").asLong());
        assertEquals(sha256(Files.readAllBytes(fixture.source("module-a"))),
            manifest.path("sources").get(0).path("sha256").asText());
        assertEquals(sha256(Files.readAllBytes(fixture.report("module-a"))),
            manifest.path("modules").get(0).path("sha256").asText());
        var repeated = fixture.export(root.resolve("second-output"));
        assertEquals(Files.readString(result.report()), Files.readString(repeated.report()));
        assertEquals(Files.readString(result.manifest()), Files.readString(repeated.manifest()));
    }

    @Test
    void absentComplexityIsZeroOnlyForNoExecutableSource(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        write(fixture.report("module-a"), report("module-a", "",
            "<counter type=\"LINE\" missed=\"0\" covered=\"0\"/><counter type=\"BRANCH\" missed=\"0\" covered=\"0\"/>"));
        ObjectNode aggregate = (ObjectNode) JSON.readTree(fixture.aggregate().toFile());
        aggregate.withObject("/aggregate/line").put("covered", 1).put("missed", 1);
        aggregate.withObject("/aggregate/branch").put("covered", 2).put("missed", 0);
        ObjectNode module = (ObjectNode) aggregate.withArray("modules").get(1);
        module.withObject("/line").put("covered", 0).put("missed", 0);
        module.withObject("/branch").put("covered", 0).put("missed", 0);
        write(fixture.aggregate(), aggregate.toString());
        Element coverage = validateCobertura(fixture.export(root.resolve("output")).report());
        assertEquals("4", coverage.getAttribute("complexity"));
        assertEquals("0", ((Element) coverage.getElementsByTagName("class").item(0)).getAttribute("complexity"));
    }

    @Test
    void ignoresNestedCopiesRatherThanDoubleCountingAuthorities(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        write(root.resolve("build/authority-evidence/copy/module-a/build/reports/jacoco/test/jacocoTestReport.xml"),
            Files.readString(fixture.report("module-a")));
        assertEquals(5, fixture.export(root.resolve("output")).validLines());
    }

    @Test
    void rejectsExternalEntitiesWithoutRevealingTheirContents(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        Path secret = root.resolve("secret.txt");
        write(secret, "NEVER-READ-ENTITY-CONTENTS");
        rewrite(fixture.report("module-a"), "<!DOCTYPE report SYSTEM \"file:///unavailable-jacoco.dtd\">",
            "<!DOCTYPE report [<!ENTITY secret SYSTEM \"" + secret.toUri() + "\">]>");
        IOException failure = assertThrows(IOException.class, () -> fixture.export(root.resolve("output")));
        assertFalse(failure.toString().contains("NEVER-READ-ENTITY-CONTENTS"));
        assertFalse(Files.exists(root.resolve("output")));
    }

    @Test
    void rejectsSymlinkedSourcesReportsAndOutputDirectories(@TempDir Path root) throws Exception {
        for (String kind : new String[]{"source", "report", "output"}) {
            Path repository = Files.createDirectory(root.resolve(kind));
            Fixture fixture = fixture(repository);
            Path target = kind.equals("source") ? fixture.source("module-a") : fixture.report("module-a");
            Path output = repository.resolve("output");
            if (kind.equals("output")) {
                Files.createSymbolicLink(output, Files.createDirectory(root.resolve("elsewhere")));
            } else {
                Path original = root.resolve(kind + ".original");
                Files.move(target, original);
                Files.createSymbolicLink(target, original);
            }
            assertThrows(IOException.class, () -> fixture.export(output), kind);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidInputs")
    void failsClosedBeforeWritingReports(BadInput input, @TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        input.change().accept(fixture);
        IOException failure = assertThrows(IOException.class, () -> fixture.export(root.resolve("output")));
        assertTrue(failure.getMessage().contains(input.reason()), failure.getMessage());
        assertFalse(Files.exists(root.resolve("output")));
    }

    private static Stream<BadInput> invalidInputs() {
        return Stream.of(
            bad("missing authority", "module", f -> Files.delete(f.report("module-a"))),
            bad("undeclared authority", "module", f -> write(f.report("module-extra"), Files.readString(f.report("module-a")))),
            bad("duplicate module policy", "JSON", f -> rewrite(f.policy(), "\"module-a\": {}", "\"module-a\": {}, \"module-a\": {}")),
            bad("duplicate module aggregate", "module", f -> {
                ObjectNode value = (ObjectNode) JSON.readTree(f.aggregate().toFile());
                value.withArray("modules").add(value.withArray("modules").get(0).deepCopy());
                write(f.aggregate(), value.toString());
            }),
            bad("duplicate source line", "duplicate line", f -> rewrite(f.report("module-a"),
                "<line nr=\"1\" mi=\"0\" ci=\"4\" mb=\"0\" cb=\"0\"/>",
                "<line nr=\"1\" mi=\"0\" ci=\"4\" mb=\"0\" cb=\"0\"/><line nr=\"1\" mi=\"0\" ci=\"4\" mb=\"0\" cb=\"0\"/>")),
            bad("source LINE mismatch", "LINE", f -> rewrite(f.report("module-a"), "type=\"LINE\" missed=\"1\" covered=\"2\"", "type=\"LINE\" missed=\"0\" covered=\"3\"")),
            bad("BRANCH mismatch", "BRANCH", f -> rewrite(f.report("module-a"), "type=\"BRANCH\" missed=\"3\" covered=\"1\"", "type=\"BRANCH\" missed=\"2\" covered=\"2\"")),
            bad("module-only LINE mismatch", "module", f -> rewrite(f.report("module-a"),
                "</package><counter type=\"LINE\" missed=\"1\" covered=\"2\"/>",
                "</package><counter type=\"LINE\" missed=\"0\" covered=\"3\"/>")),
            bad("negative instructions", "nonnegative", f -> rewrite(f.report("module-a"), "ci=\"4\"", "ci=\"-1\"")),
            bad("malformed counter", "number", f -> rewrite(f.report("module-a"), "covered=\"2\"", "covered=\"bad\"")),
            bad("missing source", "file", f -> Files.delete(f.source("module-a"))),
            bad("out-of-range line", "range", f -> rewrite(f.report("module-a"), "nr=\"4\"", "nr=\"99\"")),
            bad("source traversal", "source path", f -> rewrite(f.report("module-a"), "sourcefile name=\"A.java\"", "sourcefile name=\"../A.java\"")),
            bad("package traversal", "source path", f -> rewrite(f.report("module-a"), "package name=\"p\"", "package name=\"../p\"")),
            bad("absolute source", "source path", f -> rewrite(f.report("module-a"), "sourcefile name=\"A.java\"", "sourcefile name=\"/A.java\"")),
            bad("aggregate disagreement", "aggregate", f -> rewrite(f.aggregate(), "\"covered\":3", "\"covered\":4")),
            bad("failed existing gate", "PASSED", f -> rewrite(f.aggregate(), "PASSED", "FAILED")),
            bad("missing executable complexity", "COMPLEXITY", f -> rewrite(f.report("module-a"),
                "<counter type=\"COMPLEXITY\" missed=\"3\" covered=\"2\"/>", "")),
            bad("duplicate complexity", "duplicate COMPLEXITY", f -> rewrite(f.report("module-a"),
                "<counter type=\"COMPLEXITY\" missed=\"3\" covered=\"2\"/>",
                "<counter type=\"COMPLEXITY\" missed=\"3\" covered=\"2\"/><counter type=\"COMPLEXITY\" missed=\"3\" covered=\"2\"/>")),
            bad("module complexity mismatch", "COMPLEXITY", f -> rewrite(f.report("module-a"),
                "<counter type=\"COMPLEXITY\" missed=\"3\" covered=\"2\"/></report>",
                "<counter type=\"COMPLEXITY\" missed=\"4\" covered=\"2\"/></report>")),
            bad("missing module complexity", "COMPLEXITY", f -> rewrite(f.report("module-a"),
                "<counter type=\"COMPLEXITY\" missed=\"3\" covered=\"2\"/></report>", "</report>")),
            bad("complexity overflow", "overflow", f -> rewrite(f.report("module-a"),
                "type=\"COMPLEXITY\" missed=\"3\" covered=\"2\"", "type=\"COMPLEXITY\" missed=\"1\" covered=\"9223372036854775807\"")),
            bad("counter overflow", "overflow", f -> rewrite(f.report("module-a"), "mi=\"0\" ci=\"4\"", "mi=\"1\" ci=\"9223372036854775807\"")),
            bad("nested aggregate XML", "group", f -> rewrite(f.report("module-a"), "<report name=\"module-a\">", "<report name=\"module-a\"><group name=\"nested\"/>")),
            bad("nested report XML", "report", f -> rewrite(f.report("module-a"), "<report name=\"module-a\">", "<report name=\"module-a\"><report name=\"duplicate\"/>"))
        );
    }

    private static BadInput bad(String name, String reason, Change change) {
        return new BadInput(name, reason, fixture -> {
            try { change.apply(fixture); } catch (Exception failure) { throw new AssertionError(failure); }
        });
    }

    @FunctionalInterface
    private interface Change { void apply(Fixture fixture) throws Exception; }

    private record BadInput(String name, String reason, Consumer<Fixture> change) {
        @Override public String toString() { return name; }
    }

    private static Fixture fixture(Path root) throws Exception {
        Fixture fixture = new Fixture(root);
        write(fixture.policy(), """
            {"schema":"regelsuche.quality.coverage-policy/v1","modules":{"module-b": {}, "module-a": {}}}
            """);
        write(fixture.aggregate(), """
            {"schema":"regelsuche.quality.coverage-report/v1","status":"PASSED","violations":[],
             "aggregate":{"line":{"missed":2,"covered":3},"branch":{"missed":3,"covered":3}},
             "modules":[{"module":"module-b","line":{"missed":1,"covered":1},"branch":{"missed":0,"covered":2}},
                        {"module":"module-a","line":{"missed":1,"covered":2},"branch":{"missed":3,"covered":1}}]}
            """);
        for (String module : new String[]{"module-a", "module-b"}) {
            write(fixture.source(module), "package p;\nclass A {\n  void a() {}\n  void b() {}\n}\n");
        }
        write(fixture.report("module-a"), report("module-a", """
            <line nr="4" mi="5" ci="0" mb="2" cb="0"/>
            <line nr="1" mi="0" ci="4" mb="0" cb="0"/>
            <line nr="2" mi="2" ci="3" mb="1" cb="1"/>
            """, "<counter type=\"LINE\" missed=\"1\" covered=\"2\"/><counter type=\"BRANCH\" missed=\"3\" covered=\"1\"/><counter type=\"COMPLEXITY\" missed=\"3\" covered=\"2\"/>"));
        write(fixture.report("module-b"), report("module-b", """
            <line nr="3" mi="2" ci="0" mb="0" cb="0"/>
            <line nr="2" mi="0" ci="1" mb="0" cb="2"/>
            """, "<counter type=\"LINE\" missed=\"1\" covered=\"1\"/><counter type=\"BRANCH\" missed=\"0\" covered=\"2\"/><counter type=\"COMPLEXITY\" missed=\"1\" covered=\"3\"/>"));
        return fixture;
    }

    private static String report(String module, String lines, String counters) {
        return "<!DOCTYPE report SYSTEM \"file:///unavailable-jacoco.dtd\"><report name=\"" + module
            + "\"><package name=\"p\"><class name=\"p/A\" sourcefilename=\"A.java\"/>"
            + "<class name=\"p/A$Nested\" sourcefilename=\"A.java\"/><sourcefile name=\"A.java\">"
            + lines + counters + "</sourcefile></package>" + counters + "</report>";
    }

    private record Fixture(Path root) {
        Path policy() { return root.resolve("config/quality/coverage-policy.json"); }
        Path aggregate() { return root.resolve("build/reports/quality/coverage-report.json"); }
        Path source(String module) { return root.resolve(module + "/src/main/java/p/A.java"); }
        Path report(String module) { return root.resolve(module + "/build/reports/jacoco/test/jacocoTestReport.xml"); }
        JacocoCoberturaExporter.Result export(Path output) throws IOException {
            return JacocoCoberturaExporter.export(root, policy(), aggregate(), output);
        }
    }

    private static void write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value, StandardCharsets.UTF_8);
    }

    private static void rewrite(Path path, String before, String after) throws IOException {
        String original = Files.readString(path);
        assertTrue(original.contains(before), "fixture mutation must affect its intended input");
        Files.writeString(path, original.replace(before, after));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Element validateCobertura(Path report) throws Exception {
        // https://github.com/cobertura/cobertura/blob/master/cobertura/src/site/htdocs/xml/coverage-04.dtd
        // blob 412b9f8c242dcdf13cd39c62ad93da1ccdf9b20f; original license notice retained.
        byte[] dtd;
        try (var input = Objects.requireNonNull(JacocoCoberturaExporterTest.class.getResourceAsStream("coverage-04.dtd"))) {
            dtd = input.readAllBytes();
        }
        assertEquals("e467cb8252e20feb794fc2dedfbea7b4957921669c758f0282d525df88897917", sha256(dtd));
        var factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new ByteArrayInputStream(dtd)));
        builder.setErrorHandler(new DefaultHandler() {
            @Override public void error(SAXParseException failure) throws SAXParseException { throw failure; }
            @Override public void fatalError(SAXParseException failure) throws SAXParseException { throw failure; }
        });
        String xml = Files.readString(report).replace("?>", "?><!DOCTYPE coverage SYSTEM \"coverage-04.dtd\">");
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    }
}
