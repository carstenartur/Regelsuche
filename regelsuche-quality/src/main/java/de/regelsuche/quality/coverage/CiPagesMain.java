package de.regelsuche.quality.coverage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * Maven/Java-owned renderer for checkout-local test pages and the existing
 * verified JaCoCo coverage publisher. Gradle is only a temporary task adapter.
 * This command never runs tests, infers a successful gate, or downloads inputs.
 */
public final class CiPagesMain {
    private static final ObjectMapper JSON = new ObjectMapper();

    private CiPagesMain() { }

    public static void main(String[] args) throws Exception {
        boolean testsOnly = false;
        boolean coverageOnly = false;
        boolean mavenTests = false;
        Path sympy = null;
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--tests-only" -> testsOnly = true;
                case "--coverage-only" -> coverageOnly = true;
                case "--maven-tests" -> mavenTests = true;
                case "--sympy-inputs" -> {
                    if (++index >= args.length || sympy != null) {
                        throw new IllegalArgumentException("Expected one --sympy-inputs path");
                    }
                    sympy = Path.of(args[index]);
                }
                default -> throw new IllegalArgumentException("Unknown CI pages argument: " + args[index]);
            }
        }
        if (testsOnly && coverageOnly || testsOnly && sympy != null
                || mavenTests && !testsOnly) {
            throw new IllegalArgumentException("Conflicting CI pages rendering options");
        }
        Path root = Path.of("").toAbsolutePath().normalize();
        Path publicDir = root.resolve("public");
        if (!testsOnly) {
            CoveragePages.publish(root, publicDir.resolve("coverage"), sympy);
        }
        if (!coverageOnly) {
            TestCounts counts = publishTests(root, publicDir.resolve("tests"),
                mavenTests ? TestInput.MAVEN : TestInput.GRADLE);
            System.out.println("Tests: " + counts.tests() + " (" + counts.failed()
                + " failed, " + counts.skipped() + " skipped)");
        }
    }

    static TestCounts publishTests(Path root, Path output) throws Exception {
        return publishTests(root, output, TestInput.GRADLE);
    }

    static TestCounts publishTests(Path root, Path output, TestInput input) throws Exception {
        Files.createDirectories(output);
        // Never leave a success badge from an earlier run after a failed replay.
        Files.deleteIfExists(output.resolve("badge.json"));
        Files.deleteIfExists(output.resolve("index.html"));
        List<TestSuite> suites = new ArrayList<>();
        int tests = 0, failures = 0, errors = 0, skipped = 0;
        List<Path> reports = paths(root, (base, path) -> input == TestInput.MAVEN
            ? isMavenTestXml(base, path) : isTestXml(base, path));
        if (input == TestInput.MAVEN && reports.isEmpty()) {
            throw new IOException("No Maven Surefire/Failsafe reports; refusing a zero-test badge");
        }
        for (Path report : reports) {
            // A malformed/hostile report must not be converted into a green badge.
            Element suite = readSuite(report);
            int suiteTests = count(suite, "tests");
            int suiteFailures = count(suite, "failures");
            int suiteErrors = count(suite, "errors");
            int suiteSkipped = count(suite, "skipped");
            if (suiteFailures + suiteErrors + suiteSkipped > suiteTests) {
                throw new IOException("Inconsistent JUnit counts: " + report);
            }
            tests = Math.addExact(tests, suiteTests);
            failures = Math.addExact(failures, suiteFailures);
            errors = Math.addExact(errors, suiteErrors);
            skipped = Math.addExact(skipped, suiteSkipped);
            String name = suite.getAttribute("name");
            if (name.isEmpty()) {
                String file = report.getFileName().toString();
                name = file.substring(0, file.length() - ".xml".length());
            }
            String reportTask = input == TestInput.MAVEN
                ? report.getParent().getFileName().toString().replace("-reports", "")
                : task(root, report, "test-results");
            suites.add(new TestSuite(module(root, report), reportTask,
                name, suiteTests, suiteFailures + suiteErrors, suiteSkipped));
        }
        if (input == TestInput.MAVEN && tests == 0) {
            throw new IOException("Maven Surefire/Failsafe reports contain no executed test cases");
        }
        int failed = Math.addExact(failures, errors);
        int passed = tests - failed - skipped;
        ObjectNode badge = JSON.createObjectNode();
        badge.put("schemaVersion", 1);
        badge.put("label", "tests");
        badge.put("message", failed == 0 ? passed + " passed" : failed + "/" + tests + " failed");
        badge.put("color", failed == 0 ? "brightgreen" : "red");

        Map<String, String> links = new TreeMap<>();
        for (Path reportDir : input == TestInput.GRADLE
                ? paths(root, CiPagesMain::isTestHtmlReport) : List.<Path>of()) {
            String module = module(root, reportDir);
            String task = task(root, reportDir, "tests");
            String name = module + "-" + task;
            Path destination = output.resolve("reports").resolve(name);
            copyDirectory(reportDir, destination);
            links.put(module + "\u0000" + task, "reports/" + name + "/index.html");
        }

        List<String> rows = new ArrayList<>();
        for (TestSuite suite : suites) {
            String url = links.get(suite.module() + "\u0000" + suite.task());
            String anchor = url == null ? "" : "<a href=\"" + escape(url) + "\">HTML</a>";
            rows.add("<tr><td>" + escape(suite.module()) + "</td><td>"
                + escape(suite.task()) + "</td><td>" + escape(suite.name())
                + "</td><td>" + suite.tests() + "</td><td>" + suite.failed()
                + "</td><td>" + suite.skipped() + "</td><td>" + anchor + "</td></tr>");
        }
        String table = rows.isEmpty()
            ? "<tr><td colspan=\"7\">No JUnit XML reports were generated.</td></tr>"
            : String.join("\n", rows);
        String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
            + "<title>Regelsuche Tests</title>"
            + "<style>body{font-family:system-ui,sans-serif;margin:2rem;max-width:1200px}"
            + "table{border-collapse:collapse;width:100%}td,th{border:1px solid #ddd;"
            + "padding:.5rem;text-align:left}th{background:#f6f8fa}</style></head><body>"
            + "<h1>Regelsuche Tests</h1><p><strong>" + passed + "</strong> passed, "
            + "<strong>" + failed + "</strong> failed, <strong>" + skipped + "</strong> skipped, "
            + "<strong>" + tests + "</strong> total.</p><table><thead><tr><th>Module</th>"
            + "<th>Task</th><th>Suite</th><th>Tests</th><th>Failed</th><th>Skipped</th>"
            + "<th>Report</th></tr></thead><tbody>" + table + "</tbody></table>"
            + "</body></html>\n";
        Files.writeString(output.resolve("index.html"), html, StandardCharsets.UTF_8);
        // The badge is the final success marker after every input and retained
        // HTML report has been checked/copied.
        Files.writeString(output.resolve("badge.json"),
            JSON.writeValueAsString(badge) + "\n", StandardCharsets.UTF_8);
        return new TestCounts(tests, failed, skipped);
    }

    private static Element readSuite(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try (var input = Files.newInputStream(file)) {
            Element root = factory.newDocumentBuilder().parse(input).getDocumentElement();
            if (!"testsuite".equals(root.getTagName())) {
                throw new IOException("Not a JUnit testsuite: " + file);
            }
            return root;
        } catch (SAXException failure) {
            throw new IOException("Malformed or unsafe JUnit XML: " + file, failure);
        }
    }

    private static int count(Element suite, String attribute) throws IOException {
        String value = suite.getAttribute(attribute);
        if (value.isEmpty()) return 0;
        try {
            int number = Integer.parseInt(value);
            if (number >= 0) return number;
        } catch (NumberFormatException ignored) {
            // Fail closed below.
        }
        throw new IOException("Invalid JUnit " + attribute + ": " + value);
    }

    private static String module(Path root, Path path) {
        Path relative = root.relativize(path);
        String first = relative.getName(0).toString();
        return "build".equals(first) ? "root" : first;
    }

    private static String task(Path root, Path path, String directory) throws IOException {
        Path relative = root.relativize(path);
        for (int i = 0; i + 1 < relative.getNameCount(); i++) {
            if (directory.equals(relative.getName(i).toString())) {
                return relative.getName(i + 1).toString();
            }
        }
        throw new IOException("Missing " + directory + " task: " + path);
    }

    private static boolean isTestXml(Path root, Path path) {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)
                || !path.getFileName().toString().matches("TEST-.*\\.xml")) return false;
        Path relative = root.relativize(path);
        for (int i = 0; i + 3 < relative.getNameCount(); i++) {
            if ("build".equals(relative.getName(i).toString())
                    && "test-results".equals(relative.getName(i + 1).toString())
                    && i + 3 == relative.getNameCount() - 1) return true;
        }
        return false;
    }

    private static boolean isMavenTestXml(Path root, Path path) {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)
                || !path.getFileName().toString().matches("TEST-.*\\.xml")) return false;
        Path relative = root.relativize(path);
        int size = relative.getNameCount();
        return size >= 3 && "target".equals(relative.getName(size - 3).toString())
            && ("surefire-reports".equals(relative.getName(size - 2).toString())
                || "failsafe-reports".equals(relative.getName(size - 2).toString()));
    }

    private static boolean isTestHtmlReport(Path root, Path path) {
        if (!Files.isDirectory(path) || Files.isSymbolicLink(path)
                || !Files.isRegularFile(path.resolve("index.html"))) return false;
        Path relative = root.relativize(path);
        for (int i = 0; i + 3 < relative.getNameCount(); i++) {
            if ("build".equals(relative.getName(i).toString())
                    && "reports".equals(relative.getName(i + 1).toString())
                    && "tests".equals(relative.getName(i + 2).toString())
                    && i + 3 == relative.getNameCount() - 1) return true;
        }
        return false;
    }

    private static List<Path> paths(Path root, java.util.function.BiPredicate<Path, Path> predicate)
            throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(p -> predicate.test(root, p))
                .sorted(Comparator.comparing(Path::toString)).toList();
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.sorted().toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Symlink in retained JUnit report: " + path);
                }
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(path)) {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#x27;");
    }

    enum TestInput { GRADLE, MAVEN }
    record TestCounts(int tests, int failed, int skipped) { }
    private record TestSuite(String module, String task, String name, int tests,
            int failed, int skipped) { }
}
