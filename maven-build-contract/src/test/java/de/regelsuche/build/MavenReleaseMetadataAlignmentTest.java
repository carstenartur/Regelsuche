package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Read-only JDK/Maven authority for the version-alignment check in the release
 * lifecycle. The older Python helper still performs version mutations; its
 * read-only check is no longer required for a fresh development release.
 */
class MavenReleaseMetadataAlignmentTest {
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final ObjectMapper JSON = new ObjectMapper(JSON_FACTORY);
    private static final Pattern VERSION_LINE = Pattern.compile(
        "(?m)^[ \\t]*version[ \\t]*=[ \\t]*(\\S+)[ \\t]*$");
    private static final Pattern MODULE = Pattern.compile(
        "[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*");
    private static final String OPENAPI = "app/src/main/resources/web/openapi/openapi.json";

    @Test
    void checkedInReactorAndPublicationMetadataAgree() throws Exception {
        Path root = MavenPomTestSupport.repositoryRoot();
        String declared = oneVersion(Files.readString(root.resolve("release.properties")));
        String requested = System.getProperty("regelsuche.expectedDevelopmentVersion", declared);
        verify(root, requested);
    }

    @Test
    void rejectsPartialMetadataAndUnlistedProfileModules(@TempDir Path temp) throws Exception {
        Path root = fixture(temp);
        assertDoesNotThrow(() -> verify(root, "1.2.3-SNAPSHOT"));
        Path openapi = root.resolve(OPENAPI);
        Files.writeString(openapi, "{\"info\":{\"version\":\"1.2.4-SNAPSHOT\"}}");
        assertThrows(IllegalArgumentException.class, () -> verify(root, "1.2.3-SNAPSHOT"));
        Files.writeString(openapi, "{\"info\":{\"version\":\"1.2.3-SNAPSHOT\"}}");
        Path properties = root.resolve("release.properties");
        Files.writeString(properties, "version=1.2.3-SNAPSHOT\nversion=1.2.3-SNAPSHOT\n");
        assertThrows(IllegalArgumentException.class, () -> verify(root, "1.2.3-SNAPSHOT"));
        Files.writeString(properties, "version=1.2.3-SNAPSHOT\n");
        Path rootPom = root.resolve("pom.xml");
        Files.writeString(rootPom, Files.readString(rootPom).replace(
            "</project>", "<profiles><profile><id>hidden</id><modules>"
                + "<module>missing</module></modules></profile></profiles></project>"));
        assertThrows(IllegalArgumentException.class, () -> verify(root, "1.2.3-SNAPSHOT"));
    }

    @Test
    void rejectsSpoofedReactorVersionAndDuplicateJson(@TempDir Path temp) throws Exception {
        Path root = fixture(temp);
        Path child = root.resolve("child/pom.xml");
        Files.writeString(child, Files.readString(child).replace(
            "<version>1.2.3-SNAPSHOT</version>",
            "<version>1.2.4-SNAPSHOT</version>"));
        assertThrows(IllegalArgumentException.class, () -> verify(root, "1.2.3-SNAPSHOT"));
        Files.writeString(child, modulePom("1.2.3-SNAPSHOT"));
        Files.writeString(root.resolve(".zenodo.json"),
            "{\"version\":\"1.2.3-SNAPSHOT\",\"version\":\"1.2.3-SNAPSHOT\"}");
        assertThrows(IOException.class, () -> verify(root, "1.2.3-SNAPSHOT"));
    }

    static void verify(Path root, String expected) throws Exception {
        require(expected != null && expected.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-SNAPSHOT)?"),
            "invalid required release version");
        require(expected.equals(oneVersion(read(root, "release.properties"))),
            "release.properties version differs from expected " + expected);
        String cff = read(root, "CITATION.cff");
        require(Pattern.compile("(?m)^version:[ \\t]*[\"']?"
            + Pattern.quote(expected) + "[\"']?[ \\t]*$")
            .matcher(cff).find(), "CITATION.cff version differs");
        String citation = read(root, "CITATION.md");
        require(citation.contains("Version " + expected + ".")
                && Pattern.compile("(?m)^[ \\t]*version[ \\t]*=[ \\t]*\\{"
                    + Pattern.quote(expected) + "\\},[ \\t]*$")
                    .matcher(citation).find(),
            "CITATION.md prose or BibTeX version differs");
        for (String path : List.of(".zenodo.json", "codemeta.json")) {
            require(expected.equals(jsonVersion(root.resolve(path), "version")),
                path + " version differs");
        }
        require(expected.equals(jsonVersion(root.resolve(OPENAPI), "info", "version")),
            "OpenAPI info.version differs");

        Set<Path> visited = new HashSet<>();
        visitPom(root.toAbsolutePath().normalize(),
            root.toAbsolutePath().normalize().resolve("pom.xml"), expected,
            new HashSet<>(), visited);
        require(!visited.isEmpty(), "no Maven POMs visited");
        System.out.println("releaseMetadata=VERIFIED version=" + expected
            + " mavenPoms=" + visited.size() + " openapi=true");
    }

    private static String jsonVersion(Path path, String... keys) throws IOException {
        checkNoSymbolicPath(path);
        try (JsonParser parser = JSON_FACTORY.createParser(path.toFile())) {
            JsonNode node = JSON.readTree(parser);
            require(node != null && node.isObject(), "not a JSON object: " + path);
            for (String key : keys) {
                require(node.isObject() && node.has(key),
                    "missing JSON metadata " + key + ": " + path);
                node = node.get(key);
            }
            require(node.isTextual(), "metadata version must be textual: " + path);
            require(parser.nextToken() == null, "trailing JSON content: " + path);
            return node.textValue();
        }
    }

    private static String oneVersion(String properties) {
        Matcher matcher = VERSION_LINE.matcher(properties);
        require(matcher.find(), "release.properties has no version");
        String version = matcher.group(1);
        require(!matcher.find(), "release.properties declares more than one version");
        return version;
    }

    private static String read(Path root, String path) throws IOException {
        Path file = root.resolve(path);
        checkNoSymbolicPath(file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static void visitPom(Path root, Path pom, String version,
            Set<Path> active, Set<Path> visited) throws Exception {
        require(pom.normalize().startsWith(root), "Maven module escaped repository");
        checkNoSymbolicPath(pom);
        require(Files.isRegularFile(pom), "missing Maven POM: " + pom);
        require(!active.contains(pom), "cyclic Maven reactor module: " + pom);
        if (visited.contains(pom)) return;
        active.add(pom);
        Document document = MavenPomTestSupport.parse(pom);
        Element project = document.getDocumentElement();
        require("project".equals(project.getLocalName())
                && "http://maven.apache.org/POM/4.0.0".equals(project.getNamespaceURI()),
            "unexpected Maven POM namespace: " + pom);
        Element parent = MavenPomTestSupport.directChild(project, "parent");
        if (pom.equals(root.resolve("pom.xml"))) {
            require("regelsuche-parent".equals(
                MavenPomTestSupport.directChildText(project, "artifactId")),
                "unexpected root Maven artifact");
            require(version.equals(MavenPomTestSupport.directChildText(project, "version")),
                "root POM version differs");
        } else {
            require(parent != null && "regelsuche-parent".equals(
                MavenPomTestSupport.directChildText(parent, "artifactId")),
                "module does not inherit Regelsuche parent: " + pom);
            require(version.equals(MavenPomTestSupport.directChildText(parent, "version")),
                "module parent version differs: " + pom);
            String ownVersion = MavenPomTestSupport.directChildText(project, "version");
            require(ownVersion == null || version.equals(ownVersion),
                "module declares conflicting own version: " + pom);
        }
        List<String> modules = new ArrayList<>();
        Element directModules = MavenPomTestSupport.directChild(project, "modules");
        if (directModules != null) {
            modules.addAll(MavenPomTestSupport.directChildTexts(directModules, "module"));
        }
        Element profiles = MavenPomTestSupport.directChild(project, "profiles");
        for (Element profile : children(profiles, "profile")) {
            Element profileModules = MavenPomTestSupport.directChild(profile, "modules");
            if (profileModules != null) {
                modules.addAll(MavenPomTestSupport.directChildTexts(profileModules, "module"));
            }
        }
        for (String name : modules) {
            require(MODULE.matcher(name).matches(), "invalid Maven module path: " + name);
            Path child = pom.getParent().resolve(name).resolve("pom.xml").normalize();
            require(child.startsWith(root), "Maven module escaped repository: " + name);
            visitPom(root, child, version, active, visited);
        }
        active.remove(pom);
        visited.add(pom);
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        if (parent != null) {
            for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && name.equals(element.getLocalName())) {
                    result.add(element);
                }
            }
        }
        return result;
    }

    private static void checkNoSymbolicPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path parent = absolute; parent != null; parent = parent.getParent()) {
            require(!Files.isSymbolicLink(parent), "symlink in metadata path: " + parent);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static Path fixture(Path root) throws IOException {
        Files.createDirectories(root.resolve("child"));
        Files.createDirectories(root.resolve("app/src/main/resources/web/openapi"));
        Files.writeString(root.resolve("release.properties"), "version=1.2.3-SNAPSHOT\n");
        Files.writeString(root.resolve("CITATION.cff"), "version: \"1.2.3-SNAPSHOT\"\n");
        Files.writeString(root.resolve("CITATION.md"),
            "Version 1.2.3-SNAPSHOT.\n  version = {1.2.3-SNAPSHOT},\n");
        Files.writeString(root.resolve(".zenodo.json"), "{\"version\":\"1.2.3-SNAPSHOT\"}");
        Files.writeString(root.resolve("codemeta.json"), "{\"version\":\"1.2.3-SNAPSHOT\"}");
        Files.writeString(root.resolve(OPENAPI),
            "{\"info\":{\"version\":\"1.2.3-SNAPSHOT\"}}");
        Files.writeString(root.resolve("pom.xml"),
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<modelVersion>4.0.0</modelVersion><groupId>de.regelsuche</groupId>"
                + "<artifactId>regelsuche-parent</artifactId><version>1.2.3-SNAPSHOT</version>"
                + "<modules><module>child</module></modules></project>");
        Files.writeString(root.resolve("child/pom.xml"), modulePom("1.2.3-SNAPSHOT"));
        return root;
    }

    private static String modulePom(String version) {
        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
            + "<modelVersion>4.0.0</modelVersion><parent><groupId>de.regelsuche</groupId>"
            + "<artifactId>regelsuche-parent</artifactId><version>" + version
            + "</version><relativePath>../pom.xml</relativePath></parent>"
            + "<artifactId>child</artifactId></project>";
    }
}
