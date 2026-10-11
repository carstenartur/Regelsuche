package de.regelsuche.quality.coverage;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.w3c.dom.Element;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Converts exactly the unchanged coverage authority's canonical source-file records. */
public final class JacocoCoberturaExporter {
    private static final String CANONICAL = "build/reports/jacoco/test/jacocoTestReport.xml";
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private JacocoCoberturaExporter() { }

    public record Result(Path report, Path manifest, long coveredLines,
                         long validLines, String reportSha256) { }

    /** Writes deterministic XML and byte bindings; it never recomputes or changes policy floors. */
    public static Result export(Path root, Path policy, Path aggregate,
                                Path outputDirectory) throws IOException {
        Path checkout = root.toAbsolutePath().normalize().toRealPath();
        Path policyPath = inside(checkout, policy);
        Path aggregatePath = inside(checkout, aggregate);
        Path output = inside(checkout, outputDirectory);
        byte[] policyBytes = read(policyPath);
        byte[] aggregateBytes = read(aggregatePath);
        JsonNode policyJson = json(policyBytes);
        require("regelsuche.quality.coverage-policy/v1".equals(policyJson.path("schema").asText()), "policy schema");
        JsonNode declared = policyJson.path("modules");
        require(declared.isObject() && !declared.isEmpty(), "policy modules missing");
        Set<String> names = new TreeSet<>();
        declared.fieldNames().forEachRemaining(names::add);
        for (String name : names) require(name.matches("[A-Za-z0-9_-]+"), "invalid module name");
        Set<String> present = new TreeSet<>();
        try (var children = Files.list(checkout)) {
            for (Path child : children.toList()) {
                if (Files.exists(child.resolve(CANONICAL), LinkOption.NOFOLLOW_LINKS)) {
                    present.add(child.getFileName().toString());
                }
            }
        }
        require(!Files.exists(checkout.resolve(CANONICAL), LinkOption.NOFOLLOW_LINKS), "unexpected root module report");
        require(names.equals(present), "canonical module set disagrees with policy: " + present);
        List<Module> modules = new ArrayList<>();
        for (String name : names) modules.add(module(checkout, name));
        Counts total = Counts.ZERO;
        long complexity = 0;
        for (Module module : modules) {
            total = total.plus(module.counts());
            complexity = add(complexity, module.complexity());
        }
        validateAggregate(json(aggregateBytes), modules, total);
        byte[] xml = cobertura(modules, total, complexity).getBytes(StandardCharsets.UTF_8);
        String reportSha = sha256(xml);
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("schema", "regelsuche.quality.cobertura-export/v1");
        manifest.put("reportSha256", reportSha);
        manifest.put("coveredLines", total.coveredLines()).put("validLines", total.validLines());
        manifest.put("coveredBranches", total.coveredBranches()).put("validBranches", total.validBranches());
        manifest.put("hitSemantics", "binary line coverage indicator, not execution count");
        manifest.put("complexity", complexity);
        manifest.put("complexitySemantics", "JaCoCo covered+missed; absent only for zero executable lines, emitted as unavailable zero");
        manifest.put("policy", relative(checkout, policyPath)).put("policySha256", sha256(policyBytes));
        manifest.put("aggregate", relative(checkout, aggregatePath)).put("aggregateSha256", sha256(aggregateBytes));
        var inputs = manifest.putArray("modules");
        var sources = manifest.putArray("sources");
        for (Module module : modules) {
            ObjectNode input = inputs.addObject().put("module", module.name()).put("path", module.path())
                .put("sha256", module.sha256()).put("complexity", module.complexity());
            module.counts().write(input);
            for (Source source : module.sources()) {
                ObjectNode entry = sources.addObject().put("filename", source.filename()).put("sha256", source.sha256())
                    .put("complexity", source.complexity());
                source.counts().write(entry);
            }
        }
        // Validate all inputs before creating output; reject existing symlink destinations too.
        Path report = inside(checkout, output.resolve("coverage.cobertura.xml"));
        Path receipt = inside(checkout, output.resolve("coverage-manifest.json"));
        Files.createDirectories(output);
        Files.write(report, xml);
        Files.writeString(receipt, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n");
        return new Result(report, receipt, total.coveredLines(), total.validLines(), reportSha);
    }

    private static Module module(Path root, String name) throws IOException {
        Path path = inside(root, root.resolve(name).resolve(CANONICAL));
        byte[] bytes = read(path);
        Element report = xml(bytes);
        require(report.getTagName().equals("report"), "JaCoCo report root missing");
        require(children(report, "group").isEmpty(), "nested group is not a canonical module report");
        require(children(report, "report").isEmpty(), "nested report is not a canonical module report");
        List<Source> sources = new ArrayList<>();
        Set<String> filenames = new TreeSet<>();
        Set<String> packages = new TreeSet<>();
        for (Element pkg : children(report, "package")) {
            String packageName = pkg.getAttribute("name");
            require(packages.add(packageName), "duplicate source package");
            for (Element file : children(pkg, "sourcefile")) {
                String filename = file.getAttribute("name");
                require(part(filename) && (packageName.isEmpty() ||
                    packagePath(packageName)), "invalid source path");
                Path sourcePath = inside(root, root.resolve(name).resolve("src/main/java")
                    .resolve(packageName).resolve(filename));
                String relative = relative(root, sourcePath);
                require(filenames.add(relative), "duplicate source file");
                byte[] sourceBytes = read(sourcePath);
                long sourceLines = new String(sourceBytes, StandardCharsets.UTF_8).lines().count();
                TreeMap<Long, Line> lines = new TreeMap<>();
                Counts measured = Counts.ZERO;
                for (Element item : children(file, "line")) {
                    long nr = number(item.getAttribute("nr"));
                    require(nr > 0 && nr <= sourceLines, "source line out of range: " + relative);
                    long missed = number(item.getAttribute("mi"));
                    long covered = number(item.getAttribute("ci"));
                    require(add(missed, covered) > 0, "line contains no instructions");
                    long mb = number(item.getAttribute("mb"));
                    long cb = number(item.getAttribute("cb"));
                    Line line = new Line(nr, covered > 0, cb, add(mb, cb));
                    require(lines.putIfAbsent(nr, line) == null, "duplicate line in " + relative);
                    measured = measured.plus(new Counts(covered > 0 ? 1 : 0, 1, cb, line.branches()));
                }
                compare(measured, counters(file, false), "source " + relative);
                sources.add(new Source(relative, packageName, filename, sha256(sourceBytes),
                    List.copyOf(lines.values()), measured, complexity(file, measured.validLines() > 0)));
            }
        }
        sources.sort(Comparator.comparing(Source::filename));
        Counts measured = Counts.ZERO;
        long complexity = 0;
        for (Source source : sources) {
            measured = measured.plus(source.counts());
            complexity = add(complexity, source.complexity());
        }
        compare(measured, counters(report, true), "module " + name);
        require(complexity == complexity(report, measured.validLines() > 0), "module " + name + " COMPLEXITY counters disagree");
        return new Module(name, relative(root, path), sha256(bytes), List.copyOf(sources), measured, complexity);
    }

    private static long complexity(Element parent, boolean executable) throws IOException {
        Long total = null;
        for (Element counter : children(parent, "counter")) {
            if (!counter.getAttribute("type").equals("COMPLEXITY")) continue;
            require(total == null, "duplicate COMPLEXITY counter");
            total = add(number(counter.getAttribute("covered")), number(counter.getAttribute("missed")));
        }
        require(total != null || !executable, "executable source/module COMPLEXITY counter missing");
        return total == null ? 0 : total;
    }

    private static void validateAggregate(JsonNode value, List<Module> modules, Counts total) throws IOException {
        require("regelsuche.quality.coverage-report/v1".equals(value.path("schema").asText()), "aggregate schema");
        require("PASSED".equals(value.path("status").asText()), "existing coverage gate must be PASSED");
        for (String field : List.of("violations", "missingModules", "undeclaredModules")) {
            if (value.has(field)) require(value.path(field).isArray() && value.path(field).isEmpty(), "aggregate " + field);
        }
        require(value.path("modules").isArray(), "aggregate modules missing");
        TreeMap<String, Counts> expected = new TreeMap<>();
        for (JsonNode row : value.path("modules")) {
            require(row.path("module").isTextual() && expected.putIfAbsent(row.path("module").asText(),
                jsonCounts(row)) == null, "duplicate or missing aggregate module");
        }
        require(expected.size() == modules.size(), "aggregate module set mismatch");
        for (Module module : modules) {
            require(expected.containsKey(module.name()), "aggregate module missing: " + module.name());
            compare(module.counts(), expected.get(module.name()), "aggregate module " + module.name());
        }
        compare(total, jsonCounts(value.path("aggregate")), "aggregate total");
    }

    private static Counts jsonCounts(JsonNode row) throws IOException {
        long lines = jsonNumber(row.path("line").path("covered"));
        long branches = jsonNumber(row.path("branch").path("covered"));
        return new Counts(lines, add(lines, jsonNumber(row.path("line").path("missed"))),
            branches, add(branches, jsonNumber(row.path("branch").path("missed"))));
    }

    private static long jsonNumber(JsonNode value) throws IOException {
        require(value.isIntegralNumber() && value.canConvertToLong(), "invalid aggregate number");
        return number(value.asText());
    }

    private static Counts counters(Element parent, boolean required) throws IOException {
        long[] values = new long[4];
        Set<String> seen = new TreeSet<>();
        for (Element counter : children(parent, "counter")) {
            String kind = counter.getAttribute("type");
            if (!kind.equals("LINE") && !kind.equals("BRANCH")) continue;
            require(seen.add(kind), "duplicate " + kind + " counter");
            int index = kind.equals("LINE") ? 0 : 2;
            values[index] = number(counter.getAttribute("covered"));
            values[index + 1] = add(values[index], number(counter.getAttribute("missed")));
        }
        require(!required || seen.equals(Set.of("LINE", "BRANCH")), "module LINE/BRANCH counters missing");
        return new Counts(values[0], values[1], values[2], values[3]);
    }

    private static void compare(Counts actual, Counts expected, String context) throws IOException {
        require(actual.coveredLines() == expected.coveredLines() && actual.validLines() == expected.validLines(),
            context + " LINE counters disagree");
        require(actual.coveredBranches() == expected.coveredBranches() && actual.validBranches() == expected.validBranches(),
            context + " BRANCH counters disagree");
    }

    private static Element xml(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> { throw new org.xml.sax.SAXException("external XML access refused"); });
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(SAXParseException failure) throws SAXParseException { throw failure; }
                @Override public void fatalError(SAXParseException failure) throws SAXParseException { throw failure; }
            });
            var document = builder.parse(new ByteArrayInputStream(bytes));
            require(document.getDoctype() == null || document.getDoctype().getInternalSubset() == null,
                "XML internal DTD/entity declarations refused");
            return document.getDocumentElement();
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("invalid JaCoCo XML", failure);
        }
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && element.getTagName().equals(tag)) result.add(element);
        }
        return result;
    }

    private static String cobertura(List<Module> modules, Counts total, long complexity) throws IOException {
        StringWriter output = new StringWriter();
        try {
            XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
            try {
                writer.writeStartDocument("UTF-8", "1.0");
                start(writer, "coverage", "lines-covered", total.coveredLines(), "lines-valid", total.validLines(),
                    "branches-covered", total.coveredBranches(), "branches-valid", total.validBranches(),
                    "line-rate", rate(total.coveredLines(), total.validLines()),
                    "branch-rate", rate(total.coveredBranches(), total.validBranches()), "complexity", complexity,
                    "version", "JaCoCo-sourcefile", "timestamp", 0);
                start(writer, "sources"); start(writer, "source"); writer.writeCharacters(".");
                writer.writeEndElement(); writer.writeEndElement(); start(writer, "packages");
                for (Module module : modules) {
                    start(writer, "package", "name", module.name(), "line-rate", rate(module.counts().coveredLines(), module.counts().validLines()),
                        "branch-rate", rate(module.counts().coveredBranches(), module.counts().validBranches()), "complexity", module.complexity());
                    start(writer, "classes");
                    for (Source source : module.sources()) {
                        String label = source.name().replaceFirst("\\.java$", "");
                        start(writer, "class", "name", (source.packageName().isEmpty() ? "" : source.packageName().replace('/', '.') + ".") + label,
                            "filename", source.filename(), "line-rate", rate(source.counts().coveredLines(), source.counts().validLines()),
                            "branch-rate", rate(source.counts().coveredBranches(), source.counts().validBranches()), "complexity", source.complexity());
                        writer.writeEmptyElement("methods"); start(writer, "lines");
                        for (Line line : source.lines()) {
                            start(writer, "line", "number", line.nr(), "hits", line.covered() ? 1 : 0, "branch", line.branches() > 0);
                            if (line.branches() > 0) writer.writeAttribute("condition-coverage",
                                ratePercent(line.coveredBranches(), line.branches()) + "% (" + line.coveredBranches() + "/" + line.branches() + ")");
                            writer.writeEndElement();
                        }
                        writer.writeEndElement(); writer.writeEndElement();
                    }
                    writer.writeEndElement(); writer.writeEndElement();
                }
                writer.writeEndElement(); writer.writeEndElement(); writer.writeEndDocument();
            } finally { writer.close(); }
            return output + "\n";
        } catch (XMLStreamException failure) { throw new IOException("cannot serialize Cobertura", failure); }
    }

    private static void start(XMLStreamWriter writer, String name, Object... attributes) throws XMLStreamException {
        writer.writeStartElement(name);
        for (int index = 0; index < attributes.length; index += 2) {
            writer.writeAttribute(attributes[index].toString(), attributes[index + 1].toString());
        }
    }

    private static String rate(long covered, long valid) {
        return valid == 0 ? "1" : BigDecimal.valueOf(covered).divide(BigDecimal.valueOf(valid), 12, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString();
    }

    private static String ratePercent(long covered, long valid) {
        return BigDecimal.valueOf(covered).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(valid), 6, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString();
    }

    private static JsonNode json(byte[] bytes) throws IOException {
        try {
            JsonNode value = JSON.readTree(bytes);
            require(value != null && value.isObject(), "JSON object required");
            return value;
        } catch (IOException failure) { throw new IOException("invalid coverage JSON", failure); }
    }

    private static Path inside(Path root, Path supplied) throws IOException {
        Path path = (supplied.isAbsolute() ? supplied : root.resolve(supplied)).toAbsolutePath().normalize();
        require(path.startsWith(root), "path escapes checkout");
        Path current = root;
        for (Path part : root.relativize(path)) {
            current = current.resolve(part);
            require(!Files.isSymbolicLink(current), "symlink path refused: " + current);
        }
        return path;
    }

    private static boolean part(String value) {
        return !value.isEmpty() && !value.equals(".") && !value.equals("..") &&
            value.chars().noneMatch(c -> c == '/' || c == '\\' || c == ':' || Character.isISOControl(c));
    }

    private static boolean packagePath(String value) {
        for (String part : value.split("/", -1)) if (!part(part)) return false;
        return true;
    }

    private static byte[] read(Path file) throws IOException {
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "regular input file missing: " + file);
        return Files.readAllBytes(file);
    }

    private static String relative(Path root, Path path) { return root.relativize(path).toString().replace('\\', '/'); }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private static long number(String value) throws IOException {
        try {
            long parsed = Long.parseLong(value);
            require(parsed >= 0, "counter must be nonnegative");
            return parsed;
        } catch (NumberFormatException failure) { throw new IOException("invalid counter number", failure); }
    }

    private static long add(long left, long right) throws IOException {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException failure) { throw new IOException("counter number overflow", failure); }
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException("coverage export failed: " + message);
    }

    private record Line(long nr, boolean covered, long coveredBranches, long branches) { }
    private record Source(String filename, String packageName, String name, String sha256, List<Line> lines, Counts counts, long complexity) { }
    private record Module(String name, String path, String sha256, List<Source> sources, Counts counts, long complexity) { }
    private record Counts(long coveredLines, long validLines, long coveredBranches, long validBranches) {
        private static final Counts ZERO = new Counts(0, 0, 0, 0);
        Counts plus(Counts other) throws IOException {
            return new Counts(add(coveredLines, other.coveredLines), add(validLines, other.validLines),
                add(coveredBranches, other.coveredBranches), add(validBranches, other.validBranches));
        }
        void write(ObjectNode node) {
            node.put("coveredLines", coveredLines).put("validLines", validLines)
                .put("coveredBranches", coveredBranches).put("validBranches", validBranches);
        }
    }
}
