package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

class MavenBuildContractTest {

    private static final List<String> EXPECTED_MODULES = List.of(
        "regelsuche-core",
        "regelsuche-egraph",
        "regelsuche-search",
        "regelsuche-validation",
        "regelsuche-math-algorithms",
        "regelsuche-math-jas",
        "regelsuche-persistence",
        "regelsuche-solver-ir",
        "regelsuche-learning",
        "regelsuche-discovery",
        "regelsuche-experiments",
        "regelsuche-cli",
        "maven-build-contract"
    );

    private static final Set<String> FORBIDDEN_BUILD_PLUGINS = Set.of(
        "org.codehaus.mojo:exec-maven-plugin",
        "com.github.eirslett:frontend-maven-plugin"
    );

    private static final Map<String, String> REQUIRED_PLUGIN_VERSIONS = Map.ofEntries(
        Map.entry("org.apache.maven.plugins:maven-clean-plugin", "${maven.clean.plugin.version}"),
        Map.entry("org.apache.maven.plugins:maven-resources-plugin", "${maven.resources.plugin.version}"),
        Map.entry("org.apache.maven.plugins:maven-compiler-plugin", "${maven.compiler.plugin.version}"),
        Map.entry("org.apache.maven.plugins:maven-surefire-plugin", "${maven.surefire.plugin.version}"),
        Map.entry("org.apache.maven.plugins:maven-failsafe-plugin", "${maven.failsafe.plugin.version}"),
        Map.entry("org.apache.maven.plugins:maven-jar-plugin", "${maven.jar.plugin.version}"),
        Map.entry("org.apache.maven.plugins:maven-install-plugin", "${maven.install.plugin.version}"),
        Map.entry("org.apache.maven.plugins:maven-deploy-plugin", "${maven.deploy.plugin.version}"),
        Map.entry("org.apache.maven.plugins:maven-site-plugin", "${maven.site.plugin.version}"),
        Map.entry("org.apache.maven.plugins:maven-enforcer-plugin", "${maven.enforcer.plugin.version}"),
        Map.entry("org.jacoco:jacoco-maven-plugin", "${jacoco.version}")
    );

    private static final Set<String> FORBIDDEN_HOST_RUNTIMES = Set.of(
        "python",
        "python3",
        "bash",
        "sh",
        "zsh",
        "perl",
        "node",
        "npm",
        "npx",
        "gradle",
        "gradlew",
        "cmd",
        "powershell",
        "pwsh"
    );

    private static final List<String> COMMAND_ELEMENT_NAMES = List.of(
        "executable",
        "argument",
        "command",
        "commandlineArgs",
        "commandLineArgs"
    );

    @Test
    void reactorContainsTheDeclaredJavaModuleSlice() throws Exception {
        Path root = repositoryRoot();
        Document parent = parse(root.resolve("pom.xml"));

        List<String> modules = directChildTexts(
            directChild(parent.getDocumentElement(), "modules"),
            "module"
        );
        assertEquals(EXPECTED_MODULES, modules);

        for (String module : modules) {
            Path modulePom = root.resolve(module).resolve("pom.xml");
            assertTrue(Files.isRegularFile(modulePom), () -> "missing module POM: " + modulePom);
            Document child = parse(modulePom);
            Element parentElement = directChild(child.getDocumentElement(), "parent");
            assertNotNull(parentElement, () -> "module has no parent: " + module);
            assertEquals("de.regelsuche", directChildText(parentElement, "groupId"));
            assertEquals("regelsuche-parent", directChildText(parentElement, "artifactId"));
            assertEquals("0.2.0-SNAPSHOT", directChildText(parentElement, "version"));
        }
    }

    @Test
    void reactorPinsJavaMavenJUnitAndEveryDefaultLifecyclePlugin() throws Exception {
        Document parent = parse(repositoryRoot().resolve("pom.xml"));
        Element project = parent.getDocumentElement();
        Element properties = directChild(project, "properties");

        assertEquals("21", directChildText(properties, "maven.compiler.release"));
        assertEquals("3.9.9", directChildText(properties, "maven.minimum.version"));
        assertEquals("4.0.0", directChildText(properties, "maven.maximum.exclusive.version"));
        assertEquals("6.1.3", directChildText(properties, "junit.version"));
        assertEquals("3.5.0", directChildText(properties, "maven.clean.plugin.version"));
        assertEquals("3.5.0", directChildText(properties, "maven.resources.plugin.version"));
        assertEquals("3.15.0", directChildText(properties, "maven.compiler.plugin.version"));
        assertEquals("3.5.4", directChildText(properties, "maven.surefire.plugin.version"));
        assertEquals("3.5.4", directChildText(properties, "maven.failsafe.plugin.version"));
        assertEquals("3.5.1", directChildText(properties, "maven.jar.plugin.version"));
        assertEquals("3.1.4", directChildText(properties, "maven.install.plugin.version"));
        assertEquals("3.1.4", directChildText(properties, "maven.deploy.plugin.version"));
        assertEquals("3.22.0", directChildText(properties, "maven.site.plugin.version"));

        assertEquals(REQUIRED_PLUGIN_VERSIONS, buildPluginVersions(project));
    }

    @Test
    void reactorDoesNotInvokeHostScriptingRuntimes() throws Exception {
        Path root = repositoryRoot();
        List<Path> poms = new ArrayList<>();
        poms.add(root.resolve("pom.xml"));
        for (String module : EXPECTED_MODULES) {
            poms.add(root.resolve(module).resolve("pom.xml"));
        }

        for (Path pom : poms) {
            Document document = parse(pom);
            assertNoForbiddenCommandElements(document, pom);
            assertNoForbiddenPlugins(document, pom);
        }
    }

    @Test
    void commandScannerRejectsWhitespaceAndPathWrappedRuntime(@TempDir Path temporary)
            throws Exception {
        Path syntheticPom = temporary.resolve("pom.xml");
        Files.writeString(syntheticPom, """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <build>
                <plugins>
                  <plugin>
                    <groupId>example</groupId>
                    <artifactId>example-plugin</artifactId>
                    <configuration>
                      <executable>
                        /usr/bin/python3
                      </executable>
                    </configuration>
                  </plugin>
                </plugins>
              </build>
            </project>
            """);

        AssertionError failure = assertThrows(
            AssertionError.class,
            () -> assertNoForbiddenCommandElements(parse(syntheticPom), syntheticPom)
        );
        assertTrue(failure.getMessage().contains("python3"));
    }

    @Test
    void pluginScannerRejectsUnversionedExecPlugin(@TempDir Path temporary)
            throws Exception {
        Path syntheticPom = temporary.resolve("pom.xml");
        Files.writeString(syntheticPom, """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <build>
                <plugins>
                  <plugin>
                    <groupId>org.codehaus.mojo</groupId>
                    <artifactId>exec-maven-plugin</artifactId>
                  </plugin>
                </plugins>
              </build>
            </project>
            """);

        AssertionError failure = assertThrows(
            AssertionError.class,
            () -> assertNoForbiddenPlugins(parse(syntheticPom), syntheticPom)
        );
        assertTrue(failure.getMessage().contains("exec-maven-plugin"));
    }

    private static void assertNoForbiddenCommandElements(Document document, Path pom) {
        for (String elementName : COMMAND_ELEMENT_NAMES) {
            NodeList elements = document.getElementsByTagNameNS("*", elementName);
            for (int index = 0; index < elements.getLength(); index++) {
                String value = elements.item(index).getTextContent();
                for (String token : commandTokens(value)) {
                    assertFalse(
                        FORBIDDEN_HOST_RUNTIMES.contains(token),
                        () -> pom + " invokes forbidden host runtime " + token
                            + " through <" + elementName + ">"
                    );
                }
            }
        }
    }

    private static void assertNoForbiddenPlugins(Document document, Path pom) {
        for (String coordinate : buildPluginCoordinates(document.getDocumentElement())) {
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
        for (String candidate : value.toLowerCase(Locale.ROOT).split("[^a-z0-9_./\\\\${}-]+")) {
            if (candidate.isBlank()) {
                continue;
            }
            String token = candidate.replace('\\', '/');
            int slash = token.lastIndexOf('/');
            if (slash >= 0) {
                token = token.substring(slash + 1);
            }
            token = token.replace("${", "").replace("}", "");
            if (token.endsWith(".exe")) {
                token = token.substring(0, token.length() - 4);
            }
            if (token.contains(".")) {
                tokens.add(token.substring(0, token.indexOf('.')));
            }
            tokens.add(token);
        }
        return tokens;
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty("regelsuche.repositoryRoot");
        assertNotNull(configured, "Maven must expose maven.multiModuleProjectDirectory to tests");
        Path root = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")), () -> "no root pom.xml below " + root);
        return root;
    }

    private static Document parse(Path path)
            throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (InputStream input = Files.newInputStream(path)) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static Set<String> buildPluginCoordinates(Element project) {
        Set<String> coordinates = new LinkedHashSet<>();
        NodeList plugins = project.getElementsByTagNameNS("*", "plugin");
        for (int index = 0; index < plugins.getLength(); index++) {
            Element plugin = (Element) plugins.item(index);
            String artifactId = directChildText(plugin, "artifactId");
            if (artifactId == null || artifactId.isBlank()) {
                continue;
            }
            String groupId = directChildText(plugin, "groupId");
            if (groupId == null || groupId.isBlank()) {
                groupId = "org.apache.maven.plugins";
            }
            coordinates.add(groupId + ":" + artifactId);
        }
        return Set.copyOf(coordinates);
    }

    private static Map<String, String> buildPluginVersions(Element project) {
        Map<String, String> pluginsByCoordinate = new LinkedHashMap<>();
        NodeList plugins = project.getElementsByTagNameNS("*", "plugin");
        for (int index = 0; index < plugins.getLength(); index++) {
            Element plugin = (Element) plugins.item(index);
            String artifactId = directChildText(plugin, "artifactId");
            if (artifactId == null || artifactId.isBlank()) {
                continue;
            }
            String groupId = directChildText(plugin, "groupId");
            if (groupId == null || groupId.isBlank()) {
                groupId = "org.apache.maven.plugins";
            }
            String coordinate = groupId + ":" + artifactId;
            String version = directChildText(plugin, "version");
            if (version != null) {
                pluginsByCoordinate.put(coordinate, version);
            }
        }
        return Map.copyOf(pluginsByCoordinate);
    }

    private static Element directChild(Element parent, String localName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static String directChildText(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        Element child = directChild(parent, localName);
        return child == null ? null : child.getTextContent().trim();
    }

    private static List<String> directChildTexts(Element parent, String localName) {
        List<String> values = new ArrayList<>();
        assertNotNull(parent);
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
                values.add(element.getTextContent().trim());
            }
        }
        return List.copyOf(values);
    }
}
