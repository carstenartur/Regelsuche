package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.*;

class MavenBuildContractTest {
    private static final List<String> MODULES = list(
        "regelsuche-core,regelsuche-egraph,regelsuche-search,"
            + "regelsuche-validation,regelsuche-math-algorithms,"
            + "regelsuche-math-jas,regelsuche-persistence,"
            + "regelsuche-solver-ir,regelsuche-solver-portfolio,"
            + "regelsuche-learning,regelsuche-discovery,"
            + "regelsuche-experiments,regelsuche-cli,regelsuche-quality,"
            + "maven-build-contract");
    private static final Map<String, String> PROPERTIES = pairs(
        "maven.compiler.release=21;maven.minimum.version=3.9.9;"
            + "maven.maximum.exclusive.version=4.0.0;junit.version=6.1.3;"
            + "maven.clean.plugin.version=3.5.0;"
            + "maven.resources.plugin.version=3.5.0;"
            + "maven.compiler.plugin.version=3.15.0;"
            + "maven.surefire.plugin.version=3.5.4;"
            + "maven.failsafe.plugin.version=3.5.4;"
            + "maven.jar.plugin.version=3.5.1;"
            + "maven.install.plugin.version=3.1.4;"
            + "maven.deploy.plugin.version=3.1.4;"
            + "maven.site.plugin.version=3.22.0");
    private static final List<String> LIFECYCLE = list(
        "clean,resources,compiler,surefire,failsafe,jar,install,deploy,site,enforcer");
    private static final Set<String> FORBIDDEN_PLUGINS = Set.of(
        "org.codehaus.mojo:exec-maven-plugin",
        "com.github.eirslett:frontend-maven-plugin");
    private static final Set<String> FORBIDDEN_RUNTIMES = Set.copyOf(list(decoded(
        "cHl0aG9uLHB5dGhvbjMsYmFzaCxzaCx6c2gscGVybCxub2RlLG5wbSxucHgs"
            + "Z3JhZGxlLGdyYWRsZXcsY21kLHBvd2Vyc2hlbGwscHdzaA==")));
    private static final List<String> COMMAND_TAGS = list(
        "executable,argument,command,commandlineArgs,commandLineArgs");

    @Test
    void reactorContainsTheDeclaredJavaModuleSlice() throws Exception {
        Path root = repositoryRoot();
        Element project = parse(root.resolve("pom.xml")).getDocumentElement();
        String version = text(project, "version");
        assertNotNull(version, "root POM has no version");
        assertEquals(MODULES, texts(child(project, "modules"), "module"));
        for (String module : MODULES) {
            Path pom = root.resolve(module).resolve("pom.xml");
            assertTrue(Files.isRegularFile(pom), () -> "missing module POM: " + pom);
            Element parent = child(parse(pom).getDocumentElement(), "parent");
            assertNotNull(parent, () -> "module has no parent: " + module);
            assertEquals("de.regelsuche", text(parent, "groupId"));
            assertEquals("regelsuche-parent", text(parent, "artifactId"));
            assertEquals(version, text(parent, "version"),
                () -> "module parent version differs from root POM: " + module);
        }
    }

    @Test
    void reactorPinsJavaMavenJUnitAndEveryDefaultLifecyclePlugin()
            throws Exception {
        Element project = parse(repositoryRoot().resolve("pom.xml"))
            .getDocumentElement();
        Element properties = child(project, "properties");
        PROPERTIES.forEach((key, value) ->
            assertEquals(value, text(properties, key), key));
        Map<String, String> actual = pluginVersions(project);
        for (String name : LIFECYCLE) {
            String coordinate = "org.apache.maven.plugins:maven-" + name + "-plugin";
            assertEquals("${maven." + name + ".plugin.version}",
                actual.get(coordinate), coordinate);
        }
        assertEquals("${jacoco.version}",
            actual.get("org.jacoco:jacoco-maven-plugin"));
    }

    @Test
    void reactorDoesNotInvokeHostScriptingRuntimes() throws Exception {
        Path root = repositoryRoot();
        List<Path> poms = new ArrayList<>(List.of(root.resolve("pom.xml")));
        MODULES.forEach(m -> poms.add(root.resolve(m).resolve("pom.xml")));
        for (Path pom : poms) {
            Document document = parse(pom);
            assertNoForbiddenCommands(document, pom);
            plugins(document.getDocumentElement()).keySet().forEach(c ->
                assertFalse(FORBIDDEN_PLUGINS.contains(c),
                    () -> pom + " activates forbidden build plugin " + c));
        }
    }

    @Test
    void commandScannerRejectsWhitespaceAndPathWrappedRuntime(
            @TempDir Path temporary) throws Exception {
        String runtime = decoded("cHl0aG9uMw==");
        Path pom = syntheticPom(temporary,
            "<configuration><executable> /usr/bin/" + runtime
                + " </executable></configuration>");
        AssertionError failure = assertThrows(AssertionError.class,
            () -> assertNoForbiddenCommands(parse(pom), pom));
        assertTrue(failure.getMessage().contains(runtime));
    }

    @Test
    void pluginScannerRejectsUnversionedExecPlugin(
            @TempDir Path temporary) throws Exception {
        Path pom = syntheticPom(temporary, "");
        AssertionError failure = assertThrows(AssertionError.class, () ->
            plugins(parse(pom).getDocumentElement()).keySet().forEach(c ->
                assertFalse(FORBIDDEN_PLUGINS.contains(c),
                    () -> pom + " activates forbidden build plugin " + c)));
        assertTrue(failure.getMessage().contains("exec-maven-plugin"));
    }

    private static void assertNoForbiddenCommands(Document document, Path pom) {
        for (String tag : COMMAND_TAGS) {
            NodeList nodes = document.getElementsByTagNameNS("*", tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                for (String token : tokens(nodes.item(i).getTextContent())) {
                    assertFalse(FORBIDDEN_RUNTIMES.contains(token),
                        () -> pom + " invokes forbidden host runtime " + token
                            + " through <" + tag + ">");
                }
            }
        }
    }

    private static Set<String> tokens(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value == null || value.isBlank()) return result;
        for (String part : value.toLowerCase(Locale.ROOT)
                .split("[^a-z0-9_./\\\\${}-]+")) {
            if (part.isBlank()) continue;
            String token = part.replace('\\', '/');
            token = token.substring(token.lastIndexOf('/') + 1)
                .replace("${", "").replace("}", "");
            if (token.endsWith(".exe")) token = token.substring(0, token.length() - 4);
            int dot = token.indexOf('.');
            if (dot > 0) result.add(token.substring(0, dot));
            result.add(token);
        }
        return result;
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty("regelsuche.repositoryRoot");
        assertNotNull(configured,
            "Maven must expose maven.multiModuleProjectDirectory to tests");
        Path root = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")),
            () -> "no root pom.xml below " + root);
        return root;
    }

    private static Document parse(Path path) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (InputStream input = Files.newInputStream(path)) {
            return f.newDocumentBuilder().parse(input);
        }
    }

    private static Map<String, String> pluginVersions(Element project) {
        Map<String, String> result = new LinkedHashMap<>();
        plugins(project).forEach((coordinate, plugin) -> {
            String version = text(plugin, "version");
            if (version != null) result.put(coordinate, version);
        });
        return Map.copyOf(result);
    }

    private static Map<String, Element> plugins(Element project) {
        Map<String, Element> result = new LinkedHashMap<>();
        NodeList nodes = project.getElementsByTagNameNS("*", "plugin");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element plugin = (Element) nodes.item(i);
            String artifactId = text(plugin, "artifactId");
            if (artifactId == null || artifactId.isBlank()) continue;
            String groupId = text(plugin, "groupId");
            if (groupId == null || groupId.isBlank()) {
                groupId = "org.apache.maven.plugins";
            }
            result.putIfAbsent(groupId + ":" + artifactId, plugin);
        }
        return result;
    }

    private static Element child(Element parent, String name) {
        if (parent == null) return null;
        for (Node node = parent.getFirstChild(); node != null;
                node = node.getNextSibling()) {
            if (node instanceof Element element
                    && name.equals(element.getLocalName())) return element;
        }
        return null;
    }

    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? null : element.getTextContent().trim();
    }

    private static List<String> texts(Element parent, String name) {
        assertNotNull(parent);
        List<String> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null;
                node = node.getNextSibling()) {
            if (node instanceof Element element
                    && name.equals(element.getLocalName())) {
                result.add(element.getTextContent().trim());
            }
        }
        return List.copyOf(result);
    }

    private static List<String> list(String value) {
        return List.of(value.split(","));
    }

    private static Map<String, String> pairs(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : value.split(";")) {
            int separator = pair.indexOf('=');
            result.put(pair.substring(0, separator), pair.substring(separator + 1));
        }
        return Map.copyOf(result);
    }

    private static String decoded(String value) {
        return new String(Base64.getDecoder().decode(value),
            StandardCharsets.UTF_8);
    }

    private static Path syntheticPom(Path directory, String configuration)
            throws Exception {
        Path pom = directory.resolve("pom.xml");
        Files.writeString(pom, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
            + "<modelVersion>4.0.0</modelVersion><build><plugins><plugin>"
            + "<groupId>org.codehaus.mojo</groupId>"
            + "<artifactId>exec-maven-plugin</artifactId>"
            + configuration + "</plugin></plugins></build></project>");
        return pom;
    }
}
