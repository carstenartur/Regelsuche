package de.regelsuche.quality.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Parses checkout-owned JUnit XML and emits deterministic timing evidence. */
public final class SlowTestReportGenerator {
    public static final String SCHEMA = "regelsuche.quality.slow-tests/v1";
    public static final int DEFAULT_LIMIT = 100;
    public static final double DEFAULT_SLOW_SECONDS = 5.0d;
    public static final Path DEFAULT_JSON_OUTPUT = Path.of(
        "build/reports/quality/slow-tests.json"
    );
    public static final Path DEFAULT_MARKDOWN_OUTPUT = Path.of(
        "build/reports/quality/slow-tests.md"
    );

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Comparator<SlowTestReport.TestCaseEntry>
        TEST_ORDER = Comparator
            .comparingDouble(SlowTestReport.TestCaseEntry::seconds)
            .reversed()
            .thenComparing(SlowTestReport.TestCaseEntry::module)
            .thenComparing(SlowTestReport.TestCaseEntry::className)
            .thenComparing(SlowTestReport.TestCaseEntry::testName);
    private static final Comparator<SlowTestReport.TestClassEntry>
        CLASS_ORDER = Comparator
            .comparingDouble(SlowTestReport.TestClassEntry::seconds)
            .reversed()
            .thenComparing(SlowTestReport.TestClassEntry::module)
            .thenComparing(SlowTestReport.TestClassEntry::className);

    /**
     * Generates the transitional report from Gradle JUnit XML.
     *
     * @param repositoryRoot checkout root
     * @param limit maximum number of test and class rows
     * @param slowSeconds inclusive slow-test threshold
     * @return deterministic report
     * @throws IOException when the checkout cannot be inspected
     */
    public SlowTestReport generate(
        Path repositoryRoot,
        int limit,
        double slowSeconds
    ) throws IOException {
        Path root = requireRoot(repositoryRoot);
        validateLimits(limit, slowSeconds);
        return generateReport(
            root,
            limit,
            slowSeconds,
            gradleJUnitXmlReports(root)
        );
    }

    /**
     * Generates the authoritative Maven-reactor report from the declared
     * module set only.
     *
     * @param repositoryRoot checkout root
     * @param limit maximum number of test and class rows
     * @param slowSeconds inclusive slow-test threshold
     * @param modules exact top-level Maven modules in the active reactor
     * @return deterministic report
     * @throws IOException when the checkout cannot be inspected
     */
    public SlowTestReport generateMaven(
        Path repositoryRoot,
        int limit,
        double slowSeconds,
        Set<String> modules
    ) throws IOException {
        Path root = requireRoot(repositoryRoot);
        validateLimits(limit, slowSeconds);
        return generateReport(
            root,
            limit,
            slowSeconds,
            mavenJUnitXmlReports(root, requireMavenModules(modules))
        );
    }

    public SlowTestReport write(
        Path repositoryRoot,
        int limit,
        double slowSeconds,
        Path jsonOutput,
        Path markdownOutput
    ) throws IOException {
        Path root = requireRoot(repositoryRoot);
        return writeOutputs(
            root,
            generate(root, limit, slowSeconds),
            jsonOutput,
            markdownOutput
        );
    }

    public SlowTestReport writeMaven(
        Path repositoryRoot,
        int limit,
        double slowSeconds,
        Set<String> modules,
        Path jsonOutput,
        Path markdownOutput
    ) throws IOException {
        Path root = requireRoot(repositoryRoot);
        return writeOutputs(
            root,
            generateMaven(root, limit, slowSeconds, modules),
            jsonOutput,
            markdownOutput
        );
    }

    private SlowTestReport writeOutputs(
        Path root,
        SlowTestReport report,
        Path jsonOutput,
        Path markdownOutput
    ) throws IOException {
        Path json = resolveOutput(root, jsonOutput, "JSON output");
        Path markdown = resolveOutput(
            root,
            markdownOutput,
            "Markdown output"
        );
        writeUtf8(json, renderJson(report));
        writeUtf8(markdown, renderMarkdown(report));
        return report;
    }

    private SlowTestReport generateReport(
        Path root,
        int limit,
        double slowSeconds,
        List<Path> reports
    ) {
        List<SlowTestReport.TestCaseEntry> cases = new ArrayList<>();
        int suiteCount = 0;
        for (Path report : reports) {
            List<SlowTestReport.TestCaseEntry> parsed = parseSuite(
                root,
                report
            );
            if (parsed == null) {
                continue;
            }
            suiteCount++;
            cases.addAll(parsed);
        }
        if (cases.isEmpty()) {
            throw invalid("no JUnit test cases found");
        }

        List<SlowTestReport.TestCaseEntry> orderedTests = cases.stream()
            .sorted(TEST_ORDER)
            .limit(limit)
            .toList();
        List<SlowTestReport.TestClassEntry> orderedClasses = aggregateClasses(
            cases
        ).stream()
            .sorted(CLASS_ORDER)
            .limit(limit)
            .map(entry -> new SlowTestReport.TestClassEntry(
                entry.module(),
                entry.className(),
                roundSix(entry.seconds()),
                entry.testCount()
            ))
            .toList();

        double totalSeconds = 0.0d;
        int slowTestCount = 0;
        for (SlowTestReport.TestCaseEntry testCase : cases) {
            totalSeconds += testCase.seconds();
            if (testCase.seconds() >= slowSeconds) {
                slowTestCount++;
            }
        }
        return new SlowTestReport(
            suiteCount,
            cases.size(),
            roundSix(totalSeconds),
            slowSeconds,
            slowTestCount,
            orderedTests,
            orderedClasses
        );
    }

    String renderJson(SlowTestReport report) throws IOException {
        ObjectNode document = JSON.createObjectNode();
        document.put("schema", SCHEMA);
        document.put("slowTestCount", report.slowTestCount());
        document.put(
            "slowThresholdSeconds",
            report.slowThresholdSeconds()
        );

        ArrayNode classes = document.putArray("slowestClasses");
        for (SlowTestReport.TestClassEntry entry
                : report.slowestClasses()) {
            ObjectNode item = classes.addObject();
            item.put("className", entry.className());
            item.put("module", entry.module());
            item.put("seconds", entry.seconds());
            item.put("testCount", entry.testCount());
        }

        ArrayNode tests = document.putArray("slowestTests");
        for (SlowTestReport.TestCaseEntry entry : report.slowestTests()) {
            ObjectNode item = tests.addObject();
            item.put("className", entry.className());
            item.put("failed", entry.failed());
            item.put("module", entry.module());
            item.put("seconds", entry.seconds());
            item.put("testName", entry.testName());
        }
        document.put("suiteCount", report.suiteCount());
        document.put("testCount", report.testCount());
        document.put("totalTestSeconds", report.totalTestSeconds());
        return JSON.writerWithDefaultPrettyPrinter()
            .writeValueAsString(document) + "\n";
    }

    String renderMarkdown(SlowTestReport report) {
        StringBuilder output = new StringBuilder();
        output.append("# Slow-test report\n\n")
            .append("Parsed **")
            .append(report.testCount())
            .append("** tests from **")
            .append(report.suiteCount())
            .append("** JUnit suites. Tests at or above **")
            .append(String.format(
                Locale.ROOT,
                "%.1f",
                report.slowThresholdSeconds()
            ))
            .append("s**: **")
            .append(report.slowTestCount())
            .append("**.\n\n")
            .append("## Slowest tests\n\n")
            .append("| Seconds | Module | Test |\n")
            .append("| ---: | --- | --- |\n");
        for (SlowTestReport.TestCaseEntry entry : report.slowestTests()) {
            output.append("| ")
                .append(String.format(
                    Locale.ROOT,
                    "%.3f",
                    entry.seconds()
                ))
                .append(" | `")
                .append(entry.module())
                .append("` | `")
                .append(entry.className())
                .append('.')
                .append(entry.testName())
                .append("` |\n");
        }
        output.append("\n## Slowest test classes\n\n")
            .append("| Seconds | Tests | Module | Class |\n")
            .append("| ---: | ---: | --- | --- |\n");
        for (SlowTestReport.TestClassEntry entry
                : report.slowestClasses()) {
            output.append("| ")
                .append(String.format(
                    Locale.ROOT,
                    "%.3f",
                    entry.seconds()
                ))
                .append(" | ")
                .append(entry.testCount())
                .append(" | `")
                .append(entry.module())
                .append("` | `")
                .append(entry.className())
                .append("` |\n");
        }
        return output.toString();
    }

    private List<Path> gradleJUnitXmlReports(Path root)
            throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                .filter(path -> Files.isRegularFile(
                    path,
                    LinkOption.NOFOLLOW_LINKS
                ))
                .filter(path -> isGradleJUnitXml(root, path))
                .sorted(Comparator.comparing(path -> normalizedRelative(
                    root,
                    path
                )))
                .toList();
        }
    }

    private List<Path> mavenJUnitXmlReports(
        Path root,
        List<String> modules
    ) throws IOException {
        List<Path> reports = new ArrayList<>();
        for (String module : modules) {
            Path moduleRoot = root.resolve(module);
            if (!Files.isDirectory(
                    moduleRoot,
                    LinkOption.NOFOLLOW_LINKS)) {
                throw invalid(
                    "configured Maven module is not a directory: "
                        + module
                );
            }
            if (!Files.isRegularFile(
                    moduleRoot.resolve("pom.xml"),
                    LinkOption.NOFOLLOW_LINKS)) {
                throw invalid(
                    "configured Maven module has no pom.xml: " + module
                );
            }
            collectMavenReports(
                root,
                moduleRoot.resolve("target/surefire-reports"),
                reports
            );
            collectMavenReports(
                root,
                moduleRoot.resolve("target/failsafe-reports"),
                reports
            );
        }
        return reports.stream()
            .sorted(Comparator.comparing(path -> normalizedRelative(
                root,
                path
            )))
            .toList();
    }

    private void collectMavenReports(
        Path root,
        Path reportDirectory,
        List<Path> reports
    ) throws IOException {
        if (!Files.exists(
                reportDirectory,
                LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(
                reportDirectory,
                LinkOption.NOFOLLOW_LINKS)) {
            throw invalid(
                "Maven report path is not a directory: "
                    + normalizedRelative(root, reportDirectory)
            );
        }
        try (Stream<Path> children = Files.list(reportDirectory)) {
            children
                .filter(path -> Files.isRegularFile(
                    path,
                    LinkOption.NOFOLLOW_LINKS
                ))
                .filter(SlowTestReportGenerator::isMavenJUnitXml)
                .forEach(reports::add);
        }
    }

    private boolean isGradleJUnitXml(Path root, Path path) {
        Path relative = root.relativize(path);
        if (!path.getFileName().toString().endsWith(".xml")) {
            return false;
        }
        boolean underTestResults = false;
        for (int index = 0; index < relative.getNameCount(); index++) {
            String component = relative.getName(index).toString();
            if ("binary".equals(component)) {
                return false;
            }
            if (index + 1 < relative.getNameCount()
                    && "build".equals(component)
                    && "test-results".equals(
                        relative.getName(index + 1).toString()
                    )) {
                underTestResults = true;
            }
        }
        return underTestResults;
    }

    private static boolean isMavenJUnitXml(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.startsWith("TEST-")
            && fileName.endsWith(".xml");
    }

    private List<SlowTestReport.TestCaseEntry> parseSuite(
        Path root,
        Path path
    ) {
        try (InputStream input = Files.newInputStream(path);
                ReaderHandle handle = new ReaderHandle(
                    xmlFactory().createXMLStreamReader(input)
                )) {
            XMLStreamReader reader = handle.reader();
            List<SlowTestReport.TestCaseEntry> cases = new ArrayList<>();
            String module = module(root, path);
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT
                        && "testcase".equals(reader.getLocalName())) {
                    cases.add(readTestCase(reader, module));
                }
            }
            return List.copyOf(cases);
        } catch (IOException | XMLStreamException exception) {
            return null;
        }
    }

    private SlowTestReport.TestCaseEntry readTestCase(
        XMLStreamReader reader,
        String module
    ) throws XMLStreamException {
        String className = attribute(reader, "classname");
        String testName = attribute(reader, "name");
        double seconds = parseSeconds(attribute(reader, "time"));
        boolean failed = false;
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if (depth == 1
                        && ("failure".equals(reader.getLocalName())
                            || "error".equals(reader.getLocalName()))) {
                    failed = true;
                }
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        if (depth != 0) {
            throw new XMLStreamException("unterminated testcase");
        }
        return new SlowTestReport.TestCaseEntry(
            module,
            className,
            testName,
            seconds,
            failed
        );
    }

    private List<SlowTestReport.TestClassEntry> aggregateClasses(
        List<SlowTestReport.TestCaseEntry> cases
    ) {
        Map<ClassKey, ClassAccumulator> aggregates = new HashMap<>();
        for (SlowTestReport.TestCaseEntry testCase : cases) {
            ClassKey key = new ClassKey(
                testCase.module(),
                testCase.className()
            );
            aggregates.computeIfAbsent(
                key,
                ignored -> new ClassAccumulator()
            ).add(testCase.seconds());
        }
        List<SlowTestReport.TestClassEntry> result = new ArrayList<>();
        for (Map.Entry<ClassKey, ClassAccumulator> entry
                : aggregates.entrySet()) {
            result.add(new SlowTestReport.TestClassEntry(
                entry.getKey().module(),
                entry.getKey().className(),
                entry.getValue().seconds,
                entry.getValue().count
            ));
        }
        return List.copyOf(result);
    }

    private static XMLInputFactory xmlFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(
            XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,
            false
        );
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        return factory;
    }

    private static String attribute(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        return value == null ? "" : value;
    }

    private static double parseSeconds(String value) {
        if (value == null || value.isBlank()) {
            return 0.0d;
        }
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? roundSix(parsed) : 0.0d;
        } catch (NumberFormatException exception) {
            return 0.0d;
        }
    }

    private static double roundSix(double value) {
        return BigDecimal.valueOf(value)
            .setScale(6, RoundingMode.HALF_EVEN)
            .doubleValue();
    }

    private static String module(Path root, Path report) {
        Path relative = root.relativize(report);
        return relative.getNameCount() == 0
            ? ""
            : relative.getName(0).toString();
    }

    private static String normalizedRelative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static List<String> requireMavenModules(Set<String> values) {
        if (values == null || values.isEmpty()) {
            throw invalid("Maven module set is required");
        }
        TreeSet<String> modules = new TreeSet<>();
        for (String value : values) {
            if (value == null
                    || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                throw invalid("invalid Maven module path: " + value);
            }
            modules.add(value);
        }
        return List.copyOf(modules);
    }

    private static Path requireRoot(Path value) {
        if (value == null) {
            throw invalid("repository root is required");
        }
        Path root = value.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid("repository root is not a directory: " + root);
        }
        return root;
    }

    private static void validateLimits(int limit, double slowSeconds) {
        if (limit < 1
                || !Double.isFinite(slowSeconds)
                || slowSeconds < 0.0d) {
            throw invalid("invalid reporting limits");
        }
    }

    private static Path resolveOutput(
        Path root,
        Path value,
        String description
    ) {
        if (value == null) {
            throw invalid(description + " is required");
        }
        return (value.isAbsolute() ? value : root.resolve(value))
            .toAbsolutePath()
            .normalize();
    }

    private static void writeUtf8(Path path, String content)
            throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            throw invalid("output has no parent: " + path);
        }
        Files.createDirectories(parent);
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(
            "slow-test report failed: " + message
        );
    }

    private record ClassKey(String module, String className) { }

    private record ReaderHandle(XMLStreamReader reader)
            implements AutoCloseable {
        @Override
        public void close() throws XMLStreamException {
            reader.close();
        }
    }

    private static final class ClassAccumulator {
        private double seconds;
        private int count;

        private void add(double value) {
            seconds += value;
            count++;
        }
    }
}
