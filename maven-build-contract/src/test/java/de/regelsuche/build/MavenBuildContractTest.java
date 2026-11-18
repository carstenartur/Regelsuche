package de.regelsuche.build;

import static de.regelsuche.build.MavenPomTestSupport.directChild;
import static de.regelsuche.build.MavenPomTestSupport.directChildText;
import static de.regelsuche.build.MavenPomTestSupport.directChildTexts;
import static de.regelsuche.build.MavenPomTestSupport.parse;
import static de.regelsuche.build.MavenPomTestSupport.repositoryRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class MavenBuildContractTest {
    private static final List<String> EXPECTED_MODULES = List.of(
        "regelsuche-core",
        "regelsuche-egraph",
        "regelsuche-search",
        "regelsuche-validation",
        "regelsuche-math-algorithms",
        "regelsuche-optimization-sdk",
        "regelsuche-math-jas",
        "regelsuche-math-sympy",
        "regelsuche-persistence",
        "regelsuche-solver-ir",
        "regelsuche-solver-portfolio",
        "regelsuche-learning",
        "regelsuche-discovery",
        "regelsuche-discovery-sdk",
        "regelsuche-plugin-api",
        "regelsuche-extension-api",
        "regelsuche-extension-runtime",
        "regelsuche-bom",
        "regelsuche-experiments",
        "regelsuche-cli",
        "regelsuche-quality",
        "maven-build-contract"
    );
    private static final List<String> SOURCE_ARCHIVE_MODULES = List.of(
        "regelsuche-core",
        "regelsuche-egraph",
        "regelsuche-search",
        "regelsuche-validation",
        "regelsuche-math-algorithms"
    );
    // Failsafe requires actual container-backed execution, not just a reachable daemon.
    // These are pinned Postgres/Neo4j JUnit/Testcontainers suites in the full profile.
    private static final List<String> SOURCE_ARCHIVE_CONTAINER_SUITES = List.of(
        "DockerAvailabilityIT",
        "PostgresDiscoveryPersistenceTest",
        "Neo4jExpressionGraphStoreTest"
    );
    private static final Set<String> FORBIDDEN_BUILD_PLUGINS = Set.of(
        "org.codehaus.mojo:exec-maven-plugin",
        "com.github.eirslett:frontend-maven-plugin"
    );
    private static final Set<String> FORBIDDEN_HOST_RUNTIMES = Set.of(
        "python", "python3", "bash", "sh", "zsh", "perl", "node",
        "npm", "npx", "gradle", "gradlew", "cmd", "powershell", "pwsh"
    );
    private static final List<String> COMMAND_ELEMENT_NAMES = List.of(
        "executable", "argument", "command", "commandlineArgs",
        "commandLineArgs"
    );
    private static final Set<String> COMMAND_ATTRIBUTE_NAMES = Set.of(
        "executable", "interpreter", "shell", "command",
        "commandlineArgs", "commandLineArgs"
    );
    private static final Set<String> EXECUTABLE_ATTRIBUTE_NAMES = Set.of(
        "executable", "interpreter", "shell", "command"
    );
    private static final Set<String> DECLARED_JDK_EXECUTABLES = Set.of(
        "java", "javac"
    );
    private static final Map<String, String> REQUIRED_PROPERTIES = Map.ofEntries(
        Map.entry("maven.compiler.release", "25"),
        Map.entry("maven.minimum.version", "3.9.9"),
        Map.entry("maven.maximum.exclusive.version", "4.0.0"),
        Map.entry("junit.version", "6.1.3"),
        Map.entry("graalpy.version", "25.5.5.0.3"),
        Map.entry("maven.clean.plugin.version", "3.5.0"),
        Map.entry("maven.resources.plugin.version", "3.5.0"),
        Map.entry("maven.compiler.plugin.version", "3.15.0"),
        Map.entry("maven.surefire.plugin.version", "3.5.4"),
        Map.entry("maven.failsafe.plugin.version", "3.5.4"),
        Map.entry("maven.jar.plugin.version", "3.5.1"),
        Map.entry("maven.install.plugin.version", "3.1.4"),
        Map.entry("maven.deploy.plugin.version", "3.1.4"),
        Map.entry("maven.site.plugin.version", "3.22.0")
    );
    private static final Map<String, String> REQUIRED_PLUGIN_VERSIONS =
        Map.ofEntries(
            Map.entry(
                "org.apache.maven.plugins:maven-clean-plugin",
                "${maven.clean.plugin.version}"),
            Map.entry(
                "org.apache.maven.plugins:maven-resources-plugin",
                "${maven.resources.plugin.version}"),
            Map.entry(
                "org.apache.maven.plugins:maven-compiler-plugin",
                "${maven.compiler.plugin.version}"),
            Map.entry(
                "org.apache.maven.plugins:maven-surefire-plugin",
                "${maven.surefire.plugin.version}"),
            Map.entry(
                "org.apache.maven.plugins:maven-failsafe-plugin",
                "${maven.failsafe.plugin.version}"),
            Map.entry(
                "org.apache.maven.plugins:maven-jar-plugin",
                "${maven.jar.plugin.version}"),
            Map.entry(
                "org.apache.maven.plugins:maven-install-plugin",
                "${maven.install.plugin.version}"),
            Map.entry(
                "org.apache.maven.plugins:maven-deploy-plugin",
                "${maven.deploy.plugin.version}"),
            Map.entry(
                "org.apache.maven.plugins:maven-site-plugin",
                "${maven.site.plugin.version}"),
            Map.entry(
                "org.apache.maven.plugins:maven-enforcer-plugin",
                "${maven.enforcer.plugin.version}"),
            Map.entry(
                "org.jacoco:jacoco-maven-plugin",
                "${jacoco.version}"),
            Map.entry(
                "org.graalvm.python:graalpy-maven-plugin",
                "${graalpy.version}")
        );

    @Test
    void explicitSourceArchiveReplayHasNoGitMetadata() {
        if (Boolean.getBoolean("regelsuche.sourceArchiveMode")) {
            assertNoGitMetadata(repositoryRoot());
            System.out.println("sourceArchive=VERIFIED gitMetadata=absent");
        }
    }

    @Test
    void archiveGuardRejectsGitDirectoryAndGitFile(
            @TempDir Path temporary) throws Exception {
        Path gitDirectory = Files.createDirectory(temporary.resolve(".git"));
        assertThrows(AssertionError.class, () -> assertNoGitMetadata(temporary));
        Files.delete(gitDirectory);

        Files.writeString(temporary.resolve(".git"), "gitdir: elsewhere");
        assertThrows(AssertionError.class, () -> assertNoGitMetadata(temporary));
        Files.delete(temporary.resolve(".git"));

        assertNoGitMetadata(temporary);
    }

    private static void assertNoGitMetadata(Path root) {
        assertFalse(
            Files.exists(root.resolve(".git")),
            () -> "source archive unexpectedly contains .git metadata: " + root
        );
    }

    @Test
    void sourceArchiveReplayRunsFirstMavenSliceUnitTests() throws Exception {
        if (Boolean.getBoolean("regelsuche.sourceArchiveMode")) {
            Map<String, Long> executedByModule = executedFirstSliceSurefireTests(repositoryRoot());
            for (var entry : executedByModule.entrySet()) {
                System.out.println("sourceArchive=VERIFIED module=" + entry.getKey()
                    + " executedTests=" + entry.getValue());
                if ("regelsuche-core".equals(entry.getKey())) {
                    // Retain the established marker for existing CI consumers.
                    System.out.println("sourceArchive=VERIFIED coreExecutedTests=" + entry.getValue());
                }
            }
        }
    }

    @Test
    void archiveReplayRejectsPartialFirstSliceEvidence(@TempDir Path temporary) throws Exception {
        Path reports = temporary.resolve("regelsuche-core/target/surefire-reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("TEST-synthetic.xml"),
            "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"/>");
        assertThrows(AssertionError.class, () -> executedFirstSliceSurefireTests(temporary));
    }

    @Test
    void gitFreeFullProfileReplayRequiresExecutedContainerSuites() throws Exception {
        if (Boolean.getBoolean("regelsuche.sourceArchiveContainerMode")) {
            Path root = repositoryRoot();
            assertNoGitMetadata(root);
            Map<String, Long> suites = requiredSourceArchiveContainerTests(root);
            for (var suite : suites.entrySet()) {
                System.out.println("sourceArchive=VERIFIED containerSuite=" + suite.getKey()
                    + " executedTests=" + suite.getValue());
            }
        }
    }

    @Test
    void containerReplayRejectsPartialSkippedFailedAndUnsafeEvidence(
            @TempDir Path temporary) throws Exception {
        Path reports = temporary.resolve("regelsuche-integration-tests/target/failsafe-reports");
        Files.createDirectories(reports);
        assertThrows(AssertionError.class,
            () -> requiredSourceArchiveContainerTests(temporary));

        for (String suite : SOURCE_ARCHIVE_CONTAINER_SUITES) {
            Files.writeString(reports.resolve("TEST-de.regelsuche.dockere2e." + suite + ".xml"),
                "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"/>");
        }
        assertEquals(SOURCE_ARCHIVE_CONTAINER_SUITES.size(),
            requiredSourceArchiveContainerTests(temporary).size());

        Path postgres = reports.resolve("TEST-de.regelsuche.dockere2e.PostgresDiscoveryPersistenceTest.xml");
        Files.writeString(postgres,
            "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"1\"/>");
        assertThrows(AssertionError.class,
            () -> requiredSourceArchiveContainerTests(temporary));

        Files.writeString(postgres,
            "<testsuite tests=\"1\" failures=\"1\" errors=\"0\" skipped=\"0\"/>");
        assertThrows(AssertionError.class,
            () -> requiredSourceArchiveContainerTests(temporary));

        Files.writeString(postgres,
            "<!DOCTYPE testsuite [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<testsuite tests=\"1\" failures=\"0\" errors=\"0\""
                + " skipped=\"0\">&xxe;</testsuite>");
        assertThrows(org.xml.sax.SAXException.class,
            () -> requiredSourceArchiveContainerTests(temporary));
    }

    private static Map<String, Long> requiredSourceArchiveContainerTests(Path root)
            throws Exception {
        Path reports = root.resolve("regelsuche-integration-tests/target/failsafe-reports");
        assertTrue(Files.isDirectory(reports),
            () -> "missing Git-free Maven Failsafe reports: " + reports);
        Map<String, Long> executed = new LinkedHashMap<>();
        for (String suite : SOURCE_ARCHIVE_CONTAINER_SUITES) {
            Path report = reports.resolve("TEST-de.regelsuche.dockere2e." + suite + ".xml");
            assertTrue(Files.isRegularFile(report),
                () -> "missing container-backed Failsafe suite: " + report);
            Element xml = parse(report).getDocumentElement();
            assertEquals("testsuite", xml.getLocalName(),
                () -> "invalid Failsafe suite in " + report);
            int tests = Integer.parseInt(xml.getAttribute("tests"));
            int failures = Integer.parseInt(xml.getAttribute("failures"));
            int errors = Integer.parseInt(xml.getAttribute("errors"));
            int skipped = Integer.parseInt(xml.getAttribute("skipped"));
            assertTrue(tests > 0 && failures >= 0 && errors >= 0 && skipped >= 0
                && failures + errors + skipped <= tests,
                () -> "invalid Failsafe counters in " + report);
            assertEquals(0, failures, () -> "failed container-backed test in " + report);
            assertEquals(0, errors, () -> "errored container-backed test in " + report);
            long run = tests - skipped;
            assertTrue(run > 0,
                () -> "no executed container-backed tests (skipped/empty): " + report);
            executed.put(suite, run);
        }
        return executed;
    }

    private static Map<String, Long> executedFirstSliceSurefireTests(Path root) throws Exception {
        Map<String, Long> executedByModule = new LinkedHashMap<>();
        for (String module : SOURCE_ARCHIVE_MODULES) {
            executedByModule.put(module, executedModuleSurefireTests(
                root.resolve(module).resolve("target/surefire-reports"), module));
        }
        return executedByModule;
    }

    @Test
    void coreReportGuardRejectsMissingEmptySkippedAndFailedRuns(
            @TempDir Path temporary) throws Exception {
        assertThrows(AssertionError.class, () ->
            executedCoreSurefireTests(temporary.resolve("missing")));
        Path reports = Files.createDirectory(temporary.resolve("reports"));
        assertThrows(AssertionError.class, () ->
            executedCoreSurefireTests(reports));

        Path report = reports.resolve("TEST-synthetic.xml");
        Files.writeString(report,
            "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"1\"/>");
        assertThrows(AssertionError.class, () ->
            executedCoreSurefireTests(reports));

        Files.writeString(report,
            "<testsuite tests=\"1\" failures=\"1\" errors=\"0\" skipped=\"0\"/>");
        assertThrows(AssertionError.class, () ->
            executedCoreSurefireTests(reports));

        Files.writeString(report,
            "<testsuite tests=\"1\" failures=\"0\" errors=\"1\" skipped=\"0\"/>");
        assertThrows(AssertionError.class, () ->
            executedCoreSurefireTests(reports));

        Files.writeString(report,
            "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"/>");
        assertEquals(1L, executedCoreSurefireTests(reports));
    }

    @Test
    void coreReportGuardRejectsInvalidCountersAndUnsafeXml(
            @TempDir Path temporary) throws Exception {
        Path reports = Files.createDirectory(temporary.resolve("reports"));
        Path first = reports.resolve("TEST-first.xml");

        Files.writeString(first,
            "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"2\"/>");
        assertThrows(AssertionError.class, () ->
            executedCoreSurefireTests(reports));

        Files.writeString(first,
            "<testsuite tests=\"invalid\" failures=\"0\" errors=\"0\" skipped=\"0\"/>");
        assertThrows(NumberFormatException.class, () ->
            executedCoreSurefireTests(reports));

        Files.writeString(first,
            "<!DOCTYPE testsuite [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<testsuite tests=\"1\" failures=\"0\" errors=\"0\""
                + " skipped=\"0\">&xxe;</testsuite>");
        assertThrows(org.xml.sax.SAXException.class, () ->
            executedCoreSurefireTests(reports));

        Files.writeString(first,
            "<testsuite tests=\"2\" failures=\"0\" errors=\"0\" skipped=\"1\"/>");
        Files.writeString(reports.resolve("TEST-second.xml"),
            "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"/>");
        assertEquals(2L, executedCoreSurefireTests(reports));
    }

    private static long executedCoreSurefireTests(Path reports) throws Exception {
        return executedModuleSurefireTests(reports, "regelsuche-core");
    }

    private static long executedModuleSurefireTests(Path reports, String module) throws Exception {
        assertTrue(Files.isDirectory(reports),
            () -> "missing " + module + " Surefire reports in source archive: " + reports);
        List<Path> suites;
        try (var entries = Files.list(reports)) {
            suites = entries.filter(path -> {
                String name = path.getFileName().toString();
                return Files.isRegularFile(path)
                    && name.startsWith("TEST-") && name.endsWith(".xml");
            }).sorted().toList();
        }
        assertFalse(suites.isEmpty(),
            () -> "empty " + module + " Surefire report inventory: " + reports);
        long executed = 0;
        for (Path suite : suites) {
            Element xml = parse(suite).getDocumentElement();
            assertEquals("testsuite", xml.getLocalName(),
                () -> "invalid Surefire suite in " + suite);
            int tests = Integer.parseInt(xml.getAttribute("tests"));
            int failures = Integer.parseInt(xml.getAttribute("failures"));
            int errors = Integer.parseInt(xml.getAttribute("errors"));
            int skipped = Integer.parseInt(xml.getAttribute("skipped"));
            assertTrue(tests >= 0 && skipped >= 0 && skipped <= tests,
                () -> "invalid " + module + " Surefire test counts in " + suite);
            assertEquals(0, failures, () -> module + " tests failed in " + suite);
            assertEquals(0, errors, () -> module + " tests errored in " + suite);
            executed += tests - skipped;
        }
        assertTrue(executed > 0,
            () -> "no executed " + module + " tests in source archive: " + reports);
        return executed;
    }

    @Test
    void reactorContainsTheDeclaredJavaModuleSlice() throws Exception {
        Path root = repositoryRoot();
        Document parent = parse(root.resolve("pom.xml"));
        Element parentProject = parent.getDocumentElement();
        String parentVersion = directChildText(parentProject, "version");
        assertNotNull(parentVersion, "root POM has no version");
        List<String> modules = directChildTexts(
            directChild(parentProject, "modules"),
            "module");
        assertEquals(EXPECTED_MODULES, modules);
        for (String module : modules) {
            Path pom = root.resolve(module).resolve("pom.xml");
            assertTrue(
                Files.isRegularFile(pom),
                () -> "missing module POM: " + pom
            );
            Element declaredParent = directChild(
                parse(pom).getDocumentElement(),
                "parent");
            assertNotNull(
                declaredParent,
                () -> "module has no parent: " + module
            );
            assertEquals(
                "io.github.carstenartur",
                directChildText(declaredParent, "groupId")
            );
            assertEquals(
                "regelsuche-parent",
                directChildText(declaredParent, "artifactId")
            );
            assertEquals(
                parentVersion,
                directChildText(declaredParent, "version"),
                () -> "module parent version differs from root POM: " + module
            );
        }
    }

    @Test
    void reactorPinsJavaMavenJUnitAndEveryDefaultLifecyclePlugin()
            throws Exception {
        Element project = parse(repositoryRoot().resolve("pom.xml"))
            .getDocumentElement();
        Element properties = directChild(project, "properties");
        REQUIRED_PROPERTIES.forEach((name, expected) -> assertEquals(
            expected,
            directChildText(properties, name),
            () -> "unexpected parent property " + name
        ));

        Map<String, String> actual = buildPluginVersions(project);
        assertTrue(
            actual.entrySet().containsAll(REQUIRED_PLUGIN_VERSIONS.entrySet()),
            () -> "required lifecycle plugin versions are missing: expected="
                + REQUIRED_PLUGIN_VERSIONS + ", actual=" + actual
        );
    }

    @Test
    void reactorDoesNotInvokeHostScriptingRuntimes() throws Exception {
        Path root = repositoryRoot();
        List<Path> poms = new ArrayList<>();
        poms.add(root.resolve("pom.xml"));
        EXPECTED_MODULES.forEach(module ->
            poms.add(root.resolve(module).resolve("pom.xml")));
        for (Path pom : poms) {
            Document document = parse(pom);
            assertNoForbiddenCommandElements(document, pom);
            assertNoForbiddenPlugins(document, pom);
        }
    }

    @Test
    void commandScannerRejectsWhitespaceAndPathWrappedRuntime(
            @TempDir Path temporary) throws Exception {
        Path pom = temporary.resolve("pom.xml");
        Files.writeString(pom, syntheticPom(
            "<configuration><executable> /usr/bin/python3 </executable>"
                + "</configuration>"));
        AssertionError failure = assertThrows(
            AssertionError.class,
            () -> assertNoForbiddenCommandElements(parse(pom), pom));
        assertTrue(failure.getMessage().contains("python3"));
    }

    @Test
    void commandScannerRejectsRuntimeHiddenInXmlAttributes(
            @TempDir Path temporary) throws Exception {
        // Maven's Ant integration represents an executable as an XML
        // attribute, not the element text inspected by the original guard.
        for (String invocation : List.of(
                "<exec executable=\"/usr/bin/python3\"/>",
                "<exec executable=\"node.exe\"/>",
                "<script interpreter=\"/opt/bin/bash\"/>",
                "<tool command=\"env python3 verify.py\"/>",
                "<exec executable=\"${host.runner}\"/>",
                "<exec executable=\"git\"/>")) {
            Path pom = temporary.resolve("pom.xml");
            Files.writeString(pom, syntheticPom(
                "<configuration><target>" + invocation
                    + "</target></configuration>"));
            AssertionError failure = assertThrows(
                AssertionError.class,
                () -> assertNoForbiddenCommandElements(parse(pom), pom),
                invocation);
            assertTrue(failure.getMessage().contains("host runtime"),
                failure::getMessage);
        }
    }

    @Test
    void commandScannerAllowsOrdinaryXmlAttributes(
            @TempDir Path temporary) throws Exception {
        Path pom = temporary.resolve("pom.xml");
        Files.writeString(pom, syntheticPom(
            "<configuration><target><exec executable=\"java\"/>"
                + "</target></configuration>"));
        assertNoForbiddenCommandElements(parse(pom), pom);
    }

    @Test
    void pluginScannerRejectsUnversionedExecPlugin(
            @TempDir Path temporary) throws Exception {
        Path pom = temporary.resolve("pom.xml");
        Files.writeString(pom, syntheticPom(""));
        AssertionError failure = assertThrows(
            AssertionError.class,
            () -> assertNoForbiddenPlugins(parse(pom), pom));
        assertTrue(failure.getMessage().contains("exec-maven-plugin"));
    }

    private static String syntheticPom(String configuration) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <build><plugins><plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                %s
              </plugin></plugins></build>
            </project>
            """.formatted(configuration);
    }

    private static void assertNoForbiddenCommandElements(
            Document document,
            Path pom) {
        for (String elementName : COMMAND_ELEMENT_NAMES) {
            NodeList elements = document.getElementsByTagNameNS(
                "*",
                elementName
            );
            for (int index = 0; index < elements.getLength(); index++) {
                assertNoHostRuntime(
                    elements.item(index).getTextContent(),
                    pom,
                    "<" + elementName + ">"
                );
            }
        }

        // Ant-style plugin configurations use attributes such as
        // <exec executable="python3"/>. A text-only scan misses them.
        NodeList allElements = document.getElementsByTagName("*");
        for (int index = 0; index < allElements.getLength(); index++) {
            org.w3c.dom.Element element =
                (org.w3c.dom.Element) allElements.item(index);
            for (String attribute : COMMAND_ATTRIBUTE_NAMES) {
                if (element.hasAttribute(attribute)) {
                    String value = element.getAttribute(attribute);
                    String source = "<" + element.getTagName() + " @"
                        + attribute + ">";
                    assertNoHostRuntime(value, pom, source);
                    // No dynamically resolved or undeclared host executable:
                    // otherwise a Maven POM could silently add a new required
                    // runtime without changing the explicit denylist.
                    if (EXECUTABLE_ATTRIBUTE_NAMES.contains(attribute)) {
                        assertTrue(
                            DECLARED_JDK_EXECUTABLES.contains(value.strip()),
                            () -> pom + " invokes undeclared host runtime "
                                + value + " through " + source
                        );
                    }
                }
            }
        }
    }

    private static void assertNoHostRuntime(
            String command,
            Path pom,
            String source) {
        for (String token : commandTokens(command)) {
            assertFalse(
                FORBIDDEN_HOST_RUNTIMES.contains(token),
                () -> pom + " invokes forbidden host runtime " + token
                    + " through " + source
            );
        }
    }

    private static void assertNoForbiddenPlugins(Document document, Path pom) {
        for (String coordinate : buildPluginCoordinates(
                document.getDocumentElement())) {
            assertFalse(
                FORBIDDEN_BUILD_PLUGINS.contains(coordinate),
                () -> pom + " activates forbidden build plugin " + coordinate
            );
        }
    }

    private static Set<String> commandTokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return tokens;
        }
        for (String candidate : value.toLowerCase(Locale.ROOT)
                .split("[^a-z0-9_./\\\\${}-]+")) {
            if (candidate.isBlank()) {
                continue;
            }
            String token = candidate.replace('\\', '/');
            token = token.substring(token.lastIndexOf('/') + 1)
                .replace("${", "")
                .replace("}", "");
            if (token.endsWith(".exe")) {
                token = token.substring(0, token.length() - 4);
            }
            int extension = token.indexOf('.');
            if (extension > 0) {
                tokens.add(token.substring(0, extension));
            }
            tokens.add(token);
        }
        return tokens;
    }

    private static Set<String> buildPluginCoordinates(Element project) {
        return Set.copyOf(buildPlugins(project).keySet());
    }

    private static Map<String, String> buildPluginVersions(Element project) {
        Map<String, String> versions = new LinkedHashMap<>();
        buildPlugins(project).forEach((coordinate, plugin) -> {
            String version = directChildText(plugin, "version");
            if (version != null) {
                versions.put(coordinate, version);
            }
        });
        return Map.copyOf(versions);
    }

    private static Map<String, Element> buildPlugins(Element project) {
        Map<String, Element> plugins = new LinkedHashMap<>();
        NodeList nodes = project.getElementsByTagNameNS("*", "plugin");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element plugin = (Element) nodes.item(index);
            String artifactId = directChildText(plugin, "artifactId");
            if (artifactId == null || artifactId.isBlank()) {
                continue;
            }
            String groupId = directChildText(plugin, "groupId");
            if (groupId == null || groupId.isBlank()) {
                groupId = "org.apache.maven.plugins";
            }
            plugins.putIfAbsent(groupId + ":" + artifactId, plugin);
        }
        return plugins;
    }
}
